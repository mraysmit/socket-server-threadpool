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
import dev.mars.server.ServerInstance;
import dev.mars.testing.TestServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real servers in this process, each with the plugin, finding each other through a fixed peer list standing in for gossip,
 * and settling their differences on their own. Pushes can be made to fail, so that only the periodic comparison is at work.
 */
@Timeout(120)
class KvConvergenceTest {
    private static final String SWARM = "alpha";
    @TempDir Path folder;
    private final List<ServerInstance> servers = new ArrayList<>();
    private final Map<String, ServerInstance> byId = new HashMap<>();

    @AfterEach void stopServers() { servers.forEach(ServerInstance::close); }

    /** Every server except this one, as far as it exists right now. */
    private PeerSource everyoneElse(String self) {
        return () -> byId.entrySet().stream().filter(entry -> !entry.getKey().equals(self))
                .map(entry -> new Peer(entry.getKey(), "localhost", entry.getValue().getPort())).toList();
    }

    private static final PeerLink PUSHES_ALWAYS_FAIL = (peer, records) -> { throw new IOException("the push was lost"); };

    private ServerInstance start(String id, PeerLink pushes, String... settings) throws Exception {
        var lines = new ArrayList<String>(List.of("replication=on", "sync.round.millis=100"));
        lines.addAll(List.of(settings));
        var file = Files.writeString(folder.resolve(id + "-" + System.nanoTime() + ".properties"), String.join(System.lineSeparator(), lines) + System.lineSeparator());
        var kv = new KvPlugin(file, everyoneElse(id), pushes, Executors.newVirtualThreadPerTaskExecutor(), () -> SWARM);
        var server = TestServer.start(id, kv);
        servers.add(server);
        byId.put(id, server);
        return server;
    }

    private void stop(String id) {
        var server = byId.remove(id);
        server.close();
        servers.remove(server);
    }

    private static Map<String, Object> run(ServerInstance server, Map<String, Object> parameters) throws IOException {
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) new TextMessageClient("localhost", server.getPort()).execute("kv", parameters).result();
        return result;
    }

    private static void put(ServerInstance server, String key, Object value) throws IOException { run(server, Map.of("operation", "put", "key", key, "value", value)); }

    private static boolean found(ServerInstance server, String key) {
        try { return Boolean.TRUE.equals(run(server, Map.of("operation", "get", "key", key)).get("found")); }
        catch (IOException e) { throw new RuntimeException(e); }
    }

    private static Object value(ServerInstance server, String key) throws IOException { return run(server, Map.of("operation", "get", "key", key)).get("value"); }

    private static String root(ServerInstance server) {
        try { return (String) run(server, Map.of("operation", "status")).get("root"); }
        catch (IOException e) { throw new RuntimeException(e); }
    }

    private static void eventually(String what, BooleanSupplier condition) throws Exception {
        long until = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < until) {
            try { if (condition.getAsBoolean()) { return; } } catch (RuntimeException notYet) { /* try again */ }
            Thread.sleep(50);
        }
        fail("Timed out waiting for " + what);
    }

    @Test void aServerThatStartsLaterPullsEverythingBeforeItsFirstRound() throws Exception {
        var a = start("Server-A", PUSHES_ALWAYS_FAIL, "sync.round.millis=600000");
        for (int i = 0; i < 30; i++) { put(a, String.format("k%02d", i), "value " + i); }
        var b = start("Server-B", PUSHES_ALWAYS_FAIL, "sync.round.millis=600000");
        eventually("Server-B to hold everything", () -> root(a).equals(root(b)) && found(b, "k29"));
        @SuppressWarnings("unchecked")
        var sync = (Map<String, Object>) run(b, Map.of("operation", "status")).get("sync");
        assertEquals(0L, ((Number) sync.get("rounds")).longValue(), "No round has run: the pull happened as it started");
        assertEquals(1, ((List<?>) sync.get("peers")).size());
    }

    @Test void aWriteWhosePushFailedIsRepairedByTheNextRound() throws Exception {
        var a = start("Server-A", PUSHES_ALWAYS_FAIL);
        var b = start("Server-B", PUSHES_ALWAYS_FAIL);
        put(a, "colour", "blue");
        eventually("the pair to reach Server-B", () -> found(b, "colour"));
        run(a, Map.of("operation", "delete", "key", "colour"));
        eventually("the deletion to reach Server-B", () -> !found(b, "colour"));
    }

    @Test void aRestartedServerIsRestoredFromItsPeers() throws Exception {
        var a = start("Server-A", PUSHES_ALWAYS_FAIL);
        start("Server-B", PUSHES_ALWAYS_FAIL);
        for (int i = 0; i < 10; i++) { put(a, "k" + i, i); }
        eventually("both servers to hold the same", () -> root(a).equals(root(byId.get("Server-B"))) && found(byId.get("Server-B"), "k9"));
        stop("Server-B");
        var restarted = start("Server-B", PUSHES_ALWAYS_FAIL);
        eventually("the restarted server to be restored", () -> root(restarted).equals(root(a)) && found(restarted, "k9"));
    }

    @Test void aStoppedServerCatchesUpWithWhatHappenedWhileItWasAway() throws Exception {
        var a = start("Server-A", PUSHES_ALWAYS_FAIL);
        start("Server-B", PUSHES_ALWAYS_FAIL);
        put(a, "before", "1");
        eventually("Server-B to have it", () -> found(byId.get("Server-B"), "before"));
        stop("Server-B");
        put(a, "during", "2");
        run(a, Map.of("operation", "delete", "key", "before"));
        var returned = start("Server-B", PUSHES_ALWAYS_FAIL);
        eventually("the returned server to catch up", () -> root(returned).equals(root(a)));
        assertTrue(found(returned, "during"));
        assertFalse(found(returned, "before"), "The deletion made while it was away reached it");
    }

    @Test void twoWritersOfOneKeyEndWithTheSameValueEverywhere() throws Exception {
        var a = start("Server-A", PUSHES_ALWAYS_FAIL);
        var b = start("Server-B", PUSHES_ALWAYS_FAIL);
        put(a, "k", "from-a");
        put(b, "k", "from-b");
        eventually("both servers to hold the same value", () -> {
            try { return value(a, "k").equals(value(b, "k")); } catch (IOException e) { return false; }
        });
        assertEquals("from-b", value(a, "k"), "The later write wins");
    }

    @Test void aSwarmOfThreeConvergesThroughRoundsAlone() throws Exception {
        var a = start("Server-A", PUSHES_ALWAYS_FAIL);
        var b = start("Server-B", PUSHES_ALWAYS_FAIL);
        var c = start("Server-C", PUSHES_ALWAYS_FAIL);
        put(a, "x", "1");
        put(b, "y", "2");
        put(c, "z", "3");
        eventually("all three to hold all three pairs", () -> root(a).equals(root(b)) && root(b).equals(root(c)) && found(a, "z") && found(c, "x"));
    }

    @Test void statusNamesEachPeerThatWasComparedWithAndHowItWentAndTheRootsMatchOnceSettled() throws Exception {
        var a = start("Server-A", PUSHES_ALWAYS_FAIL);
        var b = start("Server-B", PUSHES_ALWAYS_FAIL);
        put(a, "x", "1");
        eventually("the servers to agree", () -> root(a).equals(root(b)));
        eventually("a round to have found them equal", () -> {
            try {
                @SuppressWarnings("unchecked")
                var peers = (List<Map<String, Object>>) ((Map<String, Object>) run(a, Map.of("operation", "status")).get("sync")).get("peers");
                return peers.size() == 1 && "Server-B".equals(peers.getFirst().get("serverId")) && "EQUAL".equals(peers.getFirst().get("lastResult"));
            } catch (IOException e) { return false; }
        });
    }
}
