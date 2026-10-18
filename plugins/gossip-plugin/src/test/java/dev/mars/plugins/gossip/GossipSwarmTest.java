/*
 * Copyright 2026 Mark Andrew Ray-Smith
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.mars.plugins.gossip;

import dev.mars.client.TextMessageClient;
import dev.mars.plugin.CommandPlugin;
import dev.mars.plugin.PluginContext;
import dev.mars.testing.TestServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class GossipSwarmTest {
    static GossipConfig config(String seeds) {
        var properties = GossipConfigTest.minimal();
        properties.setProperty("round.millis", "60");
        properties.setProperty("heartbeat.millis", "120");
        properties.setProperty("suspect.after.millis", "600");
        properties.setProperty("dead.after.millis", "1200");
        properties.setProperty("terminal.retention.millis", "2400");
        properties.setProperty("max.members", "8");
        properties.setProperty("max.records", "24");
        properties.setProperty("max.batch.members", "2");
        properties.setProperty("seeds", seeds);
        return GossipConfig.fromProperties(properties, "Self");
    }

    @Test void threeServersConvergeThroughSeedsAndRecoverARestartWithoutStoppingTheOthers() throws Exception {
        var first = new GossipPlugin(config(""));
        try (var a = TestServer.start("Server-A", first)) {
            String seed = "Server-A@127.0.0.1:" + a.getPort();
            var second = new GossipPlugin(config(seed));
            var third = new GossipPlugin(config("Server-A@localhost:" + a.getPort()));
            try (var b = TestServer.start("Server-B", second); var c = TestServer.start("Server-C", third)) {
                long began = System.nanoTime();
                await(() -> alive(first) == 3 && alive(second) == 3 && alive(third) == 3
                        && ((Number) ((Map<?, ?>) status(first).get("self")).get("heartbeat")).longValue() > 0);
                System.out.println("Three-node seed convergence observed in " + (System.nanoTime() - began) / 1_000_000 + " ms");
                assertFalse((Boolean) status(first).get("isolated"));
                assertTrue(((Number) ((Map<?, ?>) status(first).get("self")).get("heartbeat")).longValue() > 0);
                // Real hosts keep connections open between exchanges: a few were opened and every later exchange reused one.
                await(() -> ((Number) ((Map<?, ?>) status(first).get("exchanges")).get("outbound")).longValue() >= 15);
                var connections = (Map<?, ?>) ((Map<?, ?>) status(first).get("runtime")).get("connections");
                long opened = ((Number) connections.get("opened")).longValue();
                long reused = ((Number) connections.get("reused")).longValue();
                assertTrue(opened <= 6, "Opened " + opened + " connections for at least 15 exchanges with 2 peers: " + connections);
                assertTrue(reused >= 10, "Reused only " + reused + " connections: " + connections);
                assertEquals("Received: echo during gossip", new TextMessageClient("127.0.0.1", a.getPort(), "Server-A").send("echo during gossip").text());
                assertTrue(request(a.getPort(), "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n").contains("200"));
                assertEquals("Server status: ALIVE", request(a.getAdminPort(), "status\n"));

                String oldBoot = (String) ((Map<?, ?>) status(second).get("self")).get("bootId");
                int restartPort = b.getPort();
                b.close();
                assertEquals("STOPPED", status(second).get("workerState"));
                assertEquals(0, ((Map<?, ?>) status(second).get("runtime")).get("workers"));
                await(() -> members(first).stream().anyMatch(row -> oldBoot.equals(row.get("bootId"))
                        && List.of("SUSPECT", "DEAD").contains(row.get("assessment"))));
                var restarted = new GossipPlugin(config(seed));
                try (var newB = TestServer.start("Server-B", restartPort, restarted)) {
                    String boot = (String) ((Map<?, ?>) status(restarted).get("self")).get("bootId");
                    assertNotEquals(oldBoot, boot);
                    await(() -> members(first).stream().anyMatch(row -> boot.equals(row.get("bootId")) && "ALIVE".equals(row.get("assessment")))
                            && alive(restarted) == 3 && alive(third) == 3);
                    await(() -> members(first).stream().noneMatch(row -> oldBoot.equals(row.get("bootId"))));
                    assertEquals("Received: still alive", new TextMessageClient("127.0.0.1", c.getPort(), "Server-C").send("still alive").text());
                }
            }
        }
    }

    @Test void anUnavailableSeedCanStartLaterAndAnIsolatedNodeStillAdvancesItsHeartbeat() throws Exception {
        int latePort;
        try (var reservation = new ServerSocket(0)) { latePort = reservation.getLocalPort(); }
        var follower = new GossipPlugin(config("Late@127.0.0.1:" + latePort));
        try (var first = TestServer.start("Follower", follower)) {
            await(() -> ((Number) ((Map<?, ?>) status(follower).get("self")).get("heartbeat")).longValue() >= 2);
            assertEquals(true, status(follower).get("isolated"));
            assertTrue(((Number) ((Map<?, ?>) status(follower).get("runtime")).get("retryingPeers")).intValue() > 0);
            var late = new GossipPlugin(config(""));
            try (var second = TestServer.start("Late", latePort, late)) {
                await(() -> alive(follower) == 2 && alive(late) == 2);
                assertEquals(false, status(follower).get("isolated"));
            }
        }
    }

    @Test void adminShutdownCancelsAStalledScheduledExchangeAndRetainsTheTableForAcceptedHandlers() throws Exception {
        try (var peer = new StalledPeer()) {
            var plugin = new GossipPlugin(config("Stalled@127.0.0.1:" + peer.port()));
            try (var server = TestServer.start("Server-A", plugin)) {
                peer.request.get(5, TimeUnit.SECONDS);
                long began = System.nanoTime();
                assertEquals("Shutting down server...", request(server.getAdminPort(), "shutdown\n"));
                await(() -> "STOPPED".equals(status(plugin).get("workerState")));
                assertTrue(System.nanoTime() - began < TimeUnit.SECONDS.toNanos(4));
                assertTrue(peer.closed.get(2, TimeUnit.SECONDS));
                var runtime = (Map<?, ?>) status(plugin).get("runtime");
                assertEquals(0, runtime.get("workers"));
                assertEquals(0, runtime.get("dnsHelpers"));
                Object heartbeat = ((Map<?, ?>) status(plugin).get("self")).get("heartbeat");
                Thread.sleep(150);
                assertEquals(heartbeat, ((Map<?, ?>) status(plugin).get("self")).get("heartbeat"));
            }
        }
    }

    @Test void startupRollbackStopsGossipThatWasAlreadyExchangingWhenAnotherProviderFails() throws Exception {
        try (var peer = new StalledPeer()) {
            var plugin = new GossipPlugin(config("Stalled@127.0.0.1:" + peer.port()));
            var failure = new CommandPlugin() {
                @Override public void start(PluginContext context) throws Exception {
                    peer.request.get(5, TimeUnit.SECONDS);
                    throw new IOException("Later provider failed after gossip scheduled work");
                }
            };
            assertThrows(IOException.class, () -> TestServer.start("Rollback", plugin, failure));
            assertTrue(peer.closed.get(2, TimeUnit.SECONDS));
            assertEquals("STOPPED", status(plugin).get("workerState"));
            assertEquals(0, ((Map<?, ?>) status(plugin).get("runtime")).get("workers"));
        }
    }

    @Test void incompatibleSwarmsAreCountedAndDoNotAdmitForeignMembers() throws Exception {
        var owner = new GossipPlugin(config(""));
        try (var first = TestServer.start("Owner", owner)) {
            var properties = GossipConfigTest.minimal();
            properties.setProperty("swarm.id", "other");
            properties.setProperty("round.millis", "50");
            properties.setProperty("seeds", "Owner@127.0.0.1:" + first.getPort());
            var foreign = new GossipPlugin(GossipConfig.fromProperties(properties, "Foreign"));
            try (var second = TestServer.start("Foreign", foreign)) {
                await(() -> ((Number) ((Map<?, ?>) ((Map<?, ?>) status(foreign).get("runtime")).get("failures")).get("SWARM")).longValue() > 0);
                assertEquals(1, members(owner).size());
                assertEquals(1, members(foreign).size());
                assertEquals(true, status(foreign).get("isolated"));
            }
        }
    }

    static Map<?, ?> status(GossipPlugin plugin) throws Exception { return (Map<?, ?>) plugin.execute(Map.of("operation", "status")); }

    static List<Map<?, ?>> members(GossipPlugin plugin) throws Exception {
        var rows = new ArrayList<Map<?, ?>>();
        var cursors = new java.util.HashSet<Object>();
        Object cursor = null;
        do {
            var parameters = new java.util.LinkedHashMap<String, Object>(Map.of("operation", "members"));
            if (cursor != null) { parameters.put("cursor", cursor); }
            var page = (Map<?, ?>) plugin.execute(parameters);
            for (Object row : (List<?>) page.get("members")) { rows.add((Map<?, ?>) row); }
            cursor = page.get("nextCursor");
            // A cursor that comes round again would page forever, so fail instead of hanging the build.
            if (cursor != null) { assertTrue(cursors.add(cursor), "Members cursor repeated"); }
        } while (cursor != null);
        return rows;
    }

    private static int alive(GossipPlugin plugin) throws Exception {
        return (int) members(plugin).stream().filter(row -> "ALIVE".equals(row.get("assessment"))).count();
    }

    private static String request(int port, String command) throws Exception {
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(command.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
        }
    }

    @FunctionalInterface private interface Check { boolean test() throws Exception; }
    private static void await(Check condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) { if (condition.test()) { return; } Thread.sleep(20); }
        assertTrue(condition.test(), "Swarm condition did not converge within 10 seconds");
    }

    static final class StalledPeer implements AutoCloseable {
        private final ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
        private final java.util.concurrent.ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();
        final CompletableFuture<Boolean> request = new CompletableFuture<>();
        final CompletableFuture<String> payload = new CompletableFuture<>();
        final CompletableFuture<Boolean> closed = new CompletableFuture<>();
        private final java.util.concurrent.Future<?> task;
        private volatile Socket accepted;
        StalledPeer() throws IOException {
            task = worker.submit(() -> {
                try (var socket = listener.accept()) {
                    accepted = socket;
                    socket.setSoTimeout(10000);
                    var lines = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    String line = lines.readLine();
                    payload.complete(line);
                    request.complete(line != null);
                    closed.complete(lines.readLine() == null);
                } catch (IOException e) { request.completeExceptionally(e); closed.completeExceptionally(e); }
            });
        }
        int port() { return listener.getLocalPort(); }
        @Override public void close() throws Exception {
            listener.close(); if (accepted != null) { accepted.close(); }
            try { task.get(3, TimeUnit.SECONDS); }
            finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS)); }
        }
    }
}
