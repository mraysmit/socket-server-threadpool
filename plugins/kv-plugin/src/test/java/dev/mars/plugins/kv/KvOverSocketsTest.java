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
package dev.mars.plugins.kv;

import dev.mars.client.TextMessageClient;
import dev.mars.plugin.CommandPlugin;
import dev.mars.server.ServerInstance;
import dev.mars.testing.TestServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Two real servers in this process, each with the plugin, talking over real sockets. Gossip is replaced by a fixed peer list. */
@Timeout(60)
class KvOverSocketsTest {
    @TempDir Path folder;
    private final List<ServerInstance> servers = new ArrayList<>();
    private ServerInstance a, b;

    @AfterEach void stopServers() { servers.forEach(ServerInstance::close); }

    private ServerInstance server(String id, PeerSource peers) throws Exception {
        var file = Files.writeString(folder.resolve(id + ".properties"), "replication=on" + System.lineSeparator());
        var kv = new KvPlugin(file, peers, new TcpPeerLink(), Executors.newVirtualThreadPerTaskExecutor());
        var server = TestServer.start(id, kv);
        servers.add(server);
        return server;
    }

    private void twoServers() throws Exception {
        a = server("Server-A", () -> List.of(new Peer("Server-B", "localhost", b.getPort())));
        b = server("Server-B", () -> List.of(new Peer("Server-A", "localhost", a.getPort())));
    }

    private static Map<String, Object> run(ServerInstance server, Map<String, Object> parameters) throws IOException {
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) new TextMessageClient("localhost", server.getPort()).execute("kv", parameters).result();
        return result;
    }

    private static void eventually(String what, BooleanSupplier condition) throws Exception {
        long until = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < until) {
            try { if (condition.getAsBoolean()) { return; } } catch (RuntimeException notYet) { /* try again */ }
            Thread.sleep(50);
        }
        fail("Timed out waiting for " + what);
    }

    private static boolean found(ServerInstance server, String key) {
        try { return Boolean.TRUE.equals(run(server, Map.of("operation", "get", "key", key)).get("found")); }
        catch (IOException e) { throw new RuntimeException(e); }
    }

    private static long counter(ServerInstance server, String name) {
        try {
            @SuppressWarnings("unchecked")
            var counters = (Map<String, Object>) run(server, Map.of("operation", "status")).get("counters");
            return ((Number) counters.get(name)).longValue();
        } catch (IOException e) { throw new RuntimeException(e); }
    }

    @Test void aWriteOnOneServerBecomesReadableOnTheOther() throws Exception {
        twoServers();
        run(a, Map.of("operation", "put", "key", "colour", "value", "blue"));
        eventually("the pair to reach Server-B", () -> found(b, "colour"));
        assertEquals("blue", run(b, Map.of("operation", "get", "key", "colour")).get("value"));
    }

    @Test void anObjectValueSurvivesTheJourneyExactly() throws Exception {
        twoServers();
        var value = new LinkedHashMap<String, Object>();
        value.put("name", "node");
        value.put("ports", List.of(BigInteger.valueOf(9000), BigInteger.valueOf(9001)));
        value.put("nested", Map.of("live", true));
        run(a, Map.of("operation", "put", "key", "limits", "value", value));
        eventually("the pair to reach Server-B", () -> found(b, "limits"));
        assertEquals(value, run(b, Map.of("operation", "get", "key", "limits")).get("value"));
    }

    @Test void aDeleteOnOneServerRemovesTheKeyOnTheOther() throws Exception {
        twoServers();
        run(a, Map.of("operation", "put", "key", "a", "value", "1"));
        eventually("the pair to reach Server-B", () -> found(b, "a"));
        run(a, Map.of("operation", "delete", "key", "a"));
        eventually("the deletion to reach Server-B", () -> !found(b, "a"));
    }

    @Test void aWriteStillSucceedsWhenThePeerIsDownAndTheFailureIsCounted() throws Exception {
        twoServers();
        b.close();
        assertEquals(Map.of("key", "a", "created", true), run(a, Map.of("operation", "put", "key", "a", "value", "1")));
        eventually("the failed push to be counted", () -> counter(a, "pushFailures") >= 1);
    }

    /** A stand-in for the gossip plugin that answers the members operation with a fixed two-page list. */
    private static CommandPlugin fakeGossip() {
        return new CommandPlugin() {
            @Override public String instruction() { return "gossip"; }
            @Override public Object execute(Map<String, Object> parameters) {
                boolean second = "page-2".equals(parameters.get("cursor"));
                var member = new LinkedHashMap<String, Object>();
                member.put("serverId", second ? "Server-3" : "Server-2");
                member.put("host", "127.0.0.1");
                member.put("port", second ? 9300 : 9200);
                member.put("assessment", "ALIVE");
                var page = new LinkedHashMap<String, Object>();
                page.put("members", List.of(member));
                page.put("nextCursor", second ? null : "page-2");
                return page;
            }
        };
    }

    /** A stand-in gossip plugin that lists one other server, rated alive, once that server exists. */
    private static CommandPlugin gossipListing(java.util.function.Supplier<ServerInstance> other, String otherId) {
        return new CommandPlugin() {
            @Override public String instruction() { return "gossip"; }
            @Override public Object execute(Map<String, Object> parameters) {
                var member = new LinkedHashMap<String, Object>();
                member.put("serverId", otherId);
                member.put("host", "localhost");
                member.put("port", other.get().getPort());
                member.put("assessment", "ALIVE");
                var page = new LinkedHashMap<String, Object>();
                page.put("members", List.of(member));
                page.put("nextCursor", null);
                return page;
            }
        };
    }

    @Test void withNothingWiredInThePluginFindsItsPeersThroughItsOwnServersGossip() throws Exception {
        var fileA = Files.writeString(folder.resolve("A.properties"), "replication=on" + System.lineSeparator());
        var fileB = Files.writeString(folder.resolve("B.properties"), "replication=on" + System.lineSeparator());
        a = TestServer.start("Server-A", new KvPlugin(fileA), gossipListing(() -> b, "Server-B"));
        servers.add(a);
        b = TestServer.start("Server-B", new KvPlugin(fileB), gossipListing(() -> a, "Server-A"));
        servers.add(b);
        run(a, Map.of("operation", "put", "key", "colour", "value", "blue"));
        eventually("the pair to reach Server-B", () -> found(b, "colour"));
        run(b, Map.of("operation", "put", "key", "size", "value", "large"));
        eventually("the pair to reach Server-A", () -> found(a, "size"));
    }

    @Test void theRealGossipCallerReadsMembersFromThisServersOwnGossipInstruction() throws Exception {
        var host = TestServer.start("Server-1", fakeGossip());
        servers.add(host);
        var peers = new GossipPeers(new LoopbackGossip(host.getPort()), "Server-1").activePeers();
        assertEquals(List.of(new Peer("Server-2", "127.0.0.1", 9200), new Peer("Server-3", "127.0.0.1", 9300)), peers);
    }

    @Test void aServerWithoutGossipReportsThatToTheCaller() throws Exception {
        var host = TestServer.start("Server-1");
        servers.add(host);
        var failure = assertThrows(IOException.class, () -> new GossipPeers(new LoopbackGossip(host.getPort()), "Server-1").activePeers());
        assertTrue(failure.getMessage().toLowerCase().contains("gossip"), failure.getMessage());
    }
}
