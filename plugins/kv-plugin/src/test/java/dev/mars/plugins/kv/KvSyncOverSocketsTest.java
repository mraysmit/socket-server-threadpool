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
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** A store comparing itself with a real server over sockets: the real link, the real operations, real servers in this process. */
@Timeout(60)
class KvSyncOverSocketsTest {
    @TempDir Path folder;
    private final List<ServerInstance> servers = new ArrayList<>();

    @AfterEach void stopServers() { servers.forEach(ServerInstance::close); }

    /** A server with replication on in the given swarm, no peers of its own, so it only answers. */
    private ServerInstance server(String id, String swarm, String... settings) throws Exception {
        var lines = new ArrayList<String>(List.of("replication=on"));
        lines.addAll(List.of(settings));
        var file = Files.writeString(folder.resolve(id + ".properties"), String.join(System.lineSeparator(), lines) + System.lineSeparator());
        var kv = new KvPlugin(file, () -> List.of(), new TcpPeerLink(() -> swarm), Executors.newVirtualThreadPerTaskExecutor(), () -> swarm);
        var server = TestServer.start(id, kv);
        servers.add(server);
        return server;
    }

    private static Peer peer(ServerInstance server) { return new Peer("remote", "localhost", server.getPort()); }

    private static Map<String, Object> run(ServerInstance server, Map<String, Object> parameters) throws IOException {
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) new TextMessageClient("localhost", server.getPort()).execute("kv", parameters).result();
        return result;
    }

    private static String rootOf(ServerInstance server, String swarm) throws IOException {
        return (String) run(server, Map.of("operation", "sync-root", "swarmId", swarm, "root", "0000000000000000")).get("root");
    }

    private static VersionedStore localStore() { return new VersionedStore("Local", System::currentTimeMillis, KvConfig.defaults()); }

    @Test void aLocalStoreHandsItsRecordsToARealServerAndTheRootsThenMatch() throws Exception {
        var remote = server("Server-R", "alpha");
        var local = localStore();
        local.put("colour", "\"blue\"");
        local.put("size", "{\"n\":2}");
        local.delete("gone");
        var outcome = new Syncer(local, new TcpPeerLink(() -> "alpha"), 32_768).syncWith(peer(remote));
        assertFalse(outcome.equal());
        assertEquals(3, outcome.sent());
        assertEquals("blue", run(remote, Map.of("operation", "get", "key", "colour")).get("value"));
        assertEquals(local.root(), rootOf(remote, "alpha"));
        assertTrue(new Syncer(local, new TcpPeerLink(() -> "alpha"), 32_768).syncWith(peer(remote)).equal(), "a second comparison finds nothing to do");
    }

    @Test void aLocalStoreReceivesWhatARealServerHolds() throws Exception {
        var remote = server("Server-R", "alpha");
        run(remote, Map.of("operation", "put", "key", "colour", "value", "green"));
        run(remote, Map.of("operation", "put", "key", "obj", "value", Map.of("a", List.of(1, 2))));
        run(remote, Map.of("operation", "delete", "key", "never-held"));
        var local = localStore();
        var outcome = new Syncer(local, new TcpPeerLink(() -> "alpha"), 32_768).syncWith(peer(remote));
        assertEquals(3, outcome.received());
        assertEquals("\"green\"", local.get("colour").orElseThrow().valueJson());
        assertTrue(local.raw("never-held").orElseThrow().deleted());
        assertEquals(local.root(), rootOf(remote, "alpha"));
    }

    @Test void aLargeStoreOnBothSidesIsSettledInSeveralMessagesWithinTheBudget() throws Exception {
        var remote = server("Server-R", "alpha", "sync.max.bytes=800");
        for (int i = 0; i < 120; i++) { run(remote, Map.of("operation", "put", "key", String.format("remote-%03d", i), "value", "r".repeat(60))); }
        var local = localStore();
        for (int i = 0; i < 120; i++) { local.put(String.format("local-%03d", i), "\"" + "l".repeat(60) + "\""); }
        var outcome = new Syncer(local, new TcpPeerLink(() -> "alpha"), 800).syncWith(peer(remote));
        assertEquals(120, outcome.received());
        assertEquals(120, outcome.sent());
        assertTrue(outcome.pages() > 5, "pages: " + outcome.pages());
        assertEquals(local.root(), rootOf(remote, "alpha"));
        assertEquals(240, local.liveCount());
    }

    @Test void aServerInAnotherSwarmRefusesAndTheCallerSeesWhy() throws Exception {
        var remote = server("Server-R", "beta");
        var refused = assertThrows(IOException.class, () -> new Syncer(localStore(), new TcpPeerLink(() -> "alpha"), 32_768).syncWith(peer(remote)));
        assertTrue(refused.getMessage().toLowerCase().contains("swarm"), refused.getMessage());
    }

    @Test void aLinkThatDoesNotKnowItsSwarmCannotStartAComparison() throws Exception {
        var remote = server("Server-R", "alpha");
        var refused = assertThrows(IOException.class, () -> new TcpPeerLink().syncRoot(peer(remote), "0000000000000000"));
        assertTrue(refused.getMessage().toLowerCase().contains("swarm"), refused.getMessage());
    }

    @Test void aPushStillGoesWithoutASwarmIdWhenThisServerCannotSayWhichSwarmItIsIn() throws Exception {
        var remote = server("Server-R", "alpha");
        SwarmIdSource gossipDown = () -> { throw new IOException("gossip is not answering"); };
        var record = new KvRecord("a", "\"1\"", new Version(System.currentTimeMillis(), 0, "Local"), false);
        new TcpPeerLink(gossipDown).send(peer(remote), List.of(record));
        assertEquals("1", run(remote, Map.of("operation", "get", "key", "a")).get("value"));
        assertThrows(IOException.class, () -> new TcpPeerLink(gossipDown).syncRoot(peer(remote), "0000000000000000"),
                "A comparison has to name the swarm, so it cannot go ahead");
    }

    @Test void pushesAndComparisonsWithOnePeerShareOneConnection() throws Exception {
        var remote = server("Server-R", "alpha");
        var opened = new java.util.concurrent.atomic.AtomicInteger();
        var reused = new java.util.concurrent.atomic.AtomicInteger();
        var settings = dev.mars.client.KeepAliveClient.Settings.of("Key-value", 1 << 20, java.time.Duration.ofSeconds(5),
                java.time.Duration.ofSeconds(10), java.time.Duration.ofSeconds(15));
        try (var connections = new dev.mars.client.KeepAliveClient(settings, new dev.mars.client.KeepAliveClient.Listener() {
            @Override public void opened() { opened.incrementAndGet(); }
            @Override public void reused() { reused.incrementAndGet(); }
        })) {
            var link = new TcpPeerLink(() -> "alpha", connections);
            for (int i = 0; i < 50; i++) {
                link.send(peer(remote), List.of(new KvRecord("k" + i, "\"" + i + "\"", new Version(System.currentTimeMillis() + i, 0, "Local"), false)));
            }
            link.syncRoot(peer(remote), "0000000000000000");
            assertEquals(1, opened.get(), "Fifty pushes and a comparison over one connection");
            assertEquals(50, reused.get());
            assertEquals("49", run(remote, Map.of("operation", "get", "key", "k49")).get("value"));
        }
    }

    @Test void aPushAndAGraftNameTheSenderAndARealServerAcceptsBoth() throws Exception {
        var remote = server("Server-R", "alpha");
        try (var connections = TcpPeerLink.newClient(null)) {
            var link = new TcpPeerLink(() -> "alpha", connections, "Local");
            link.graft(peer(remote));
            link.send(peer(remote), List.of(new KvRecord("a", "\"1\"", new Version(System.currentTimeMillis(), 0, "Local"), false)));
            assertEquals("1", run(remote, Map.of("operation", "get", "key", "a")).get("value"));
        }
    }

    @Test void aRealServerAsksOnlyForTheAnnouncedRecordsItLacks() throws Exception {
        var remote = server("Server-R", "alpha");
        try (var connections = TcpPeerLink.newClient(null)) {
            var link = new TcpPeerLink(() -> "alpha", connections, "Local");
            var held = new KvRecord("held", "\"1\"", new Version(System.currentTimeMillis(), 0, "Local"), false);
            link.send(peer(remote), List.of(held));
            var fresh = new KvRecord("fresh", "\"2\"", new Version(System.currentTimeMillis(), 0, "Local"), false);
            assertEquals(List.of("fresh"), link.announce(peer(remote), List.of(held, fresh)));
        }
    }

    @Test void pushesNameTheSwarmSoAServerInAnotherSwarmRefusesThem() throws Exception {
        var sameSwarm = server("Server-R", "alpha");
        var otherSwarm = server("Server-O", "beta");
        var record = new KvRecord("a", "\"1\"", new Version(System.currentTimeMillis(), 0, "Local"), false);
        new TcpPeerLink(() -> "alpha").send(peer(sameSwarm), List.of(record));
        assertEquals("1", run(sameSwarm, Map.of("operation", "get", "key", "a")).get("value"));
        var refused = assertThrows(IOException.class, () -> new TcpPeerLink(() -> "alpha").send(peer(otherSwarm), List.of(record)));
        assertTrue(refused.getMessage().toLowerCase().contains("swarm"), refused.getMessage());
        assertEquals(Map.of("key", "a", "found", false), run(otherSwarm, Map.of("operation", "get", "key", "a")));
    }
}
