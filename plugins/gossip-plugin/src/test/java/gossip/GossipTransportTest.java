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
package gossip;

import dev.mars.client.CommandExecutionException;
import dev.mars.client.TextMessageClient;
import dev.mars.protocol.CommandError;
import dev.mars.protocol.CommandProtocol;
import dev.mars.protocol.CommandReply;
import dev.mars.protocol.CommandRequest;
import dev.mars.server.GossipServerFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class GossipTransportTest {
    enum Failure { MESSAGE_ID, SERVER_ID, SENDER_ID, VERSION, SWARM, BOOT_ID, FRACTIONAL_HEARTBEAT,
        DUPLICATE, UNKNOWN_FIELD, MALFORMED_JSON, EMPTY_REPLY, INVALID_UTF8, OVERSIZE_BYTES, OVERSIZE_UNICODE, REMOTE_CAPACITY }

    private static MembershipTable table(GossipConfig config) {
        return new MembershipTable(config, GossipCommandsTest.member("Self", 1));
    }

    private static MemberRecord responder(int port, long boot) {
        var key = new MemberKey("Peer", new UUID(0, boot));
        return new MemberRecord(key, "127.0.0.1", port, 3, MemberRecord.State.ALIVE, Map.of());
    }

    private static byte[] reply(GossipConfig config, CommandRequest request, MemberRecord sender) {
        return line(GossipWire.bytes(CommandReply.success(request, "Peer", 1, new GossipWire(config).result(sender, List.of()))));
    }

    private static byte[] line(byte[] body) {
        byte[] framed = Arrays.copyOf(body, body.length + 1);
        framed[body.length] = '\n';
        return framed;
    }

    @ParameterizedTest @EnumSource(Failure.class)
    void protocolFailuresCannotMutateMembershipAndPermitsAreReleasedForTheNextExchange(Failure failure) throws Exception {
        var config = GossipCommandsTest.config(1024, 32, 128);
        var table = table(config);
        try (var transport = new GossipTransport(config, table)) {
            try (var peer = new ProtocolPeer((socket, port, request) -> {
                assertEquals("gossip", request.instruction());
                assertTrue(GossipWire.bytes(request).length <= 1024);
                socket.getOutputStream().write(badReply(config, port, request, failure));
            })) {
                var error = assertThrows(IOException.class, () -> transport.exchange(peer.endpoint()));
                if (failure == Failure.REMOTE_CAPACITY) {
                    assertEquals(CommandError.Code.INVALID_PARAMETERS, assertInstanceOf(CommandExecutionException.class, error).reply().error().code());
                }
                assertEquals(1, table.snapshot().retainedIdentities());
                assertEquals(0, transport.inflight());
            }
            try (var peer = new ProtocolPeer((socket, port, request) -> socket.getOutputStream().write(reply(config, request, responder(port, 2))))) {
                var exchange = transport.exchange(peer.endpoint());
                assertEquals("Peer", exchange.sender().key().serverId());
                assertEquals(2, table.snapshot().retainedIdentities());
                assertEquals(MembershipTable.Assessment.ALIVE, table.snapshot().members().getLast().assessment());
            }
        }
    }

    private static byte[] badReply(GossipConfig config, int port, CommandRequest request, Failure failure) {
        if (failure == Failure.MALFORMED_JSON) { return "{\n".getBytes(StandardCharsets.UTF_8); }
        if (failure == Failure.EMPTY_REPLY) { return new byte[0]; }
        if (failure == Failure.INVALID_UTF8) { return new byte[] {(byte) 0xc3, 0x28, '\n'}; }
        if (failure == Failure.OVERSIZE_BYTES) { return line("a".repeat(1025).getBytes(StandardCharsets.UTF_8)); }
        if (failure == Failure.REMOTE_CAPACITY) {
            return line(GossipWire.bytes(CommandReply.failure(request.messageId(), "Peer", 1,
                    CommandError.Code.INVALID_PARAMETERS, "CAPACITY: membership is full")));
        }
        var sender = responder(port, 2);
        var result = new LinkedHashMap<>(new GossipWire(config).result(sender, List.of()));
        var fields = new LinkedHashMap<>(sender.toWire());
        UUID id = request.messageId();
        String outer = "Peer";
        switch (failure) {
            case MESSAGE_ID -> id = UUID.randomUUID();
            case SERVER_ID -> outer = "Other";
            case SENDER_ID -> fields.put("serverId", "Other");
            case VERSION -> result.put("gossipVersion", 2);
            case SWARM -> result.put("swarmId", "other");
            case BOOT_ID -> fields.put("bootId", "1-1-1-1-1");
            case FRACTIONAL_HEARTBEAT -> fields.put("heartbeat", 3.5);
            case DUPLICATE -> result.put("members", List.of(sender.toWire()));
            case UNKNOWN_FIELD -> result.put("unexpected", true);
            case OVERSIZE_UNICODE -> fields.put("metadata", Map.of("text", "雪".repeat(350)));
            default -> throw new AssertionError(failure);
        }
        result.put("sender", fields);
        byte[] body = GossipWire.bytes(new CommandReply(1, id, outer, 1, "OK", result, null));
        if (failure == Failure.OVERSIZE_UNICODE) {
            assertTrue(body.length > 1024);
            assertTrue(new String(body, StandardCharsets.UTF_8).length() < 1024);
        }
        return line(body);
    }

    @Test void rawReplyExactlyAtTheByteLimitIsAcceptedButOneMoreByteIsRejected() throws Exception {
        var config = GossipCommandsTest.config(1024, 32, 128);
        var table = table(config);
        try (var transport = new GossipTransport(config, table)) {
            for (int extra : List.of(0, 1)) {
                try (var peer = new ProtocolPeer((socket, port, request) -> {
                    byte[] ordinary = reply(config, request, responder(port, 2));
                    byte[] padded = new byte[1024 + extra];
                    Arrays.fill(padded, (byte) ' ');
                    System.arraycopy(ordinary, 0, padded, 0, ordinary.length - 1);
                    socket.getOutputStream().write(line(padded));
                })) {
                    if (extra == 0) { transport.exchange(peer.endpoint()); }
                    else { assertThrows(IOException.class, () -> transport.exchange(peer.endpoint())); }
                }
            }
        }
    }

    @Test void closingTransportCancelsStalledReadsAndRejectsFurtherWork() throws Exception {
        var config = GossipCommandsTest.config(32768, 32, 128);
        var release = new CountDownLatch(1);
        try (var peer = new ProtocolPeer((socket, port, request) -> release.await(5, TimeUnit.SECONDS));
             var transport = new GossipTransport(config, table(config));
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> exchange = workers.submit(() -> { transport.exchange(peer.endpoint()); return null; });
            peer.request.get(2, TimeUnit.SECONDS);
            try {
                assertEquals(1, transport.inflight());
                long before = System.nanoTime();
                transport.close();
                var failure = assertThrows(ExecutionException.class, () -> exchange.get(2, TimeUnit.SECONDS));
                assertInstanceOf(IOException.class, failure.getCause());
                assertTrue(System.nanoTime() - before < TimeUnit.SECONDS.toNanos(2));
                assertEquals(0, transport.inflight());
                assertThrows(IOException.class, () -> transport.exchange(peer.endpoint()));
                transport.close();
            } finally { release.countDown(); }
        }
    }

    @Test void readIdleAndOverallDeadlinesBoundBothSilentAndTricklingPeers() throws Exception {
        var config = GossipCommandsTest.config(32768, 32, 128);
        for (boolean trickle : List.of(false, true)) {
            var release = new CountDownLatch(1);
            try (var peer = new ProtocolPeer((socket, port, request) -> {
                if (!trickle) { release.await(3, TimeUnit.SECONDS); }
                else {
                    for (int i = 0; i < 30; i++) {
                        socket.getOutputStream().write(' ');
                        socket.getOutputStream().flush();
                        Thread.sleep(20);
                    }
                }
            }); var transport = new GossipTransport(config, table(config), Duration.ofSeconds(1),
                    Duration.ofMillis(100), Duration.ofMillis(300))) {
                try {
                    assertThrows(SocketTimeoutException.class, () -> transport.exchange(peer.endpoint()));
                    assertEquals(0, transport.inflight());
                } finally { release.countDown(); }
            }
        }
    }

    @Test void fullOutboundBudgetRejectsOverlappingWorkWithoutQueueingMoreSockets() throws Exception {
        var properties = GossipConfigTest.minimal();
        properties.setProperty("max.inflight", "1");
        var config = GossipConfig.fromProperties(properties, "Self");
        var release = new CountDownLatch(1);
        try (var peer = new ProtocolPeer((socket, port, request) -> release.await(5, TimeUnit.SECONDS));
             var transport = new GossipTransport(config, table(config));
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> { transport.exchange(peer.endpoint()); return null; });
            peer.request.get(2, TimeUnit.SECONDS);
            try {
                var full = assertThrows(IOException.class, () -> transport.exchange(peer.endpoint()));
                assertTrue(full.getMessage().contains("capacity"));
                assertEquals(1, transport.inflight());
                transport.close();
                assertThrows(ExecutionException.class, () -> first.get(2, TimeUnit.SECONDS));
            } finally { release.countDown(); }
        }
    }

    @Test void realTwoNodeExchangesPreserveMetadataAndExposePaginatedDirectReachability() throws Exception {
        var config = GossipCommandsTest.config(32768, 2, 128);
        var first = new GossipPlugin(config, false);
        var second = new GossipPlugin(config, false);
        try (var a = GossipServerFixture.start("Server-A", first); var b = GossipServerFixture.start("Server-B", second)) {
            var bEndpoint = new GossipConfig.Seed("Server-B", "127.0.0.1", b.getPort());
            first.exchangeWith(bEndpoint);
            second.exchangeWith(new GossipConfig.Seed("Server-A", "127.0.0.1", a.getPort()));
            var metadata = new LinkedHashMap<String, Object>();
            metadata.put("text", "雪\n🚀");
            metadata.put("null", null);
            var third = new MemberRecord(new MemberKey("Third", new UUID(0, 3)), "127.0.0.1", 1, 7,
                    MemberRecord.State.ALIVE, metadata);
            var bClient = new TextMessageClient("127.0.0.1", b.getPort(), "Server-B");
            bClient.execute("gossip", new GossipWire(config).parameters(third, List.of()));
            for (int i = 0; i < 3; i++) { first.exchangeWith(bEndpoint); }
            var aClient = new TextMessageClient("127.0.0.1", a.getPort(), "Server-A");
            var status = assertInstanceOf(Map.class, aClient.execute("gossip", Map.of("operation", "status")).result());
            assertEquals(false, status.get("isolated"));
            assertEquals(BigInteger.valueOf(3), ((Map<?, ?>) status.get("capacity")).get("activeMembers"));
            var rows = new LinkedHashMap<String, Map<?, ?>>();
            Object cursor = null;
            var cursors = new HashSet<Object>();
            do {
                var parameters = new LinkedHashMap<String, Object>(Map.of("operation", "members", "limit", 1));
                if (cursor != null) { parameters.put("cursor", cursor); }
                var page = assertInstanceOf(Map.class, aClient.execute("gossip", parameters).result());
                for (Object item : (List<?>) page.get("members")) {
                    var row = (Map<?, ?>) item;
                    assertNull(rows.put((String) row.get("serverId"), row));
                }
                cursor = page.get("nextCursor");
                if (cursor != null) { assertTrue(cursors.add(cursor)); }
            } while (cursor != null);
            assertEquals(3, rows.size());
            assertEquals("ALIVE", rows.get("Server-B").get("assessment"));
            assertEquals(true, rows.get("Server-B").get("endpointVerified"));
            assertEquals("CANDIDATE", rows.get("Third").get("assessment"));
            assertEquals(metadata, rows.get("Third").get("metadata"));
            assertEquals("Received: still working", aClient.send("still working").text());
            a.close();
            assertThrows(IOException.class, () -> first.exchangeWith(bEndpoint));
            assertEquals("Received: second alive", bClient.send("second alive").text());
        }
    }

    @Test void aFullRealPeerStillRepliesAndTheInitiatorLearnsItWithoutExceedingItsOwnCap() throws Exception {
        var config = GossipCommandsTest.config(32768, 32, 2);
        var first = new GossipPlugin(config, false);
        var second = new GossipPlugin(config, false);
        try (var a = GossipServerFixture.start("Server-A", first); var b = GossipServerFixture.start("Server-B", second)) {
            var client = new TextMessageClient("127.0.0.1", b.getPort(), "Server-B");
            client.execute("gossip", new GossipWire(config).parameters(GossipCommandsTest.member("Third", 3), List.of()));
            var reply = first.exchangeWith(new GossipConfig.Seed("Server-B", "127.0.0.1", b.getPort()));
            assertEquals("Server-B", reply.sender().key().serverId());
            var status = GossipCommandsTest.execute(first, Map.of("operation", "status"));
            assertEquals(false, status.get("isolated"));
            assertEquals(2, ((Map<?, ?>) status.get("capacity")).get("retainedIdentities"));
            assertEquals(2, ((Map<?, ?>) GossipCommandsTest.execute(second, Map.of("operation", "status")).get("capacity")).get("retainedIdentities"));
            assertTrue(GossipSwarmTest.members(first).stream().anyMatch(row -> "Server-B".equals(row.get("serverId"))));
            assertTrue(GossipSwarmTest.members(second).stream().noneMatch(row -> "Server-A".equals(row.get("serverId"))));
        }
    }

    @Test void anAliasForTheLocalEndpointCannotTurnASelfExchangeIntoBootstrapSuccess() throws Exception {
        var config = GossipCommandsTest.config(32768, 32, 128);
        var plugin = new GossipPlugin(config, false);
        try (var server = GossipServerFixture.start("Self", plugin)) {
            assertThrows(IOException.class, () -> plugin.exchangeWith(new GossipConfig.Seed("Self", "localhost", server.getPort())));
            var status = GossipCommandsTest.execute(plugin, Map.of("operation", "status"));
            assertEquals(true, status.get("isolated"));
            assertEquals(1, ((Map<?, ?>) status.get("capacity")).get("retainedIdentities"));
        }
    }

    @Test void outgoingWireBatchesRotateIndependentlyOfIncomingReplies() throws Exception {
        var config = GossipCommandsTest.config(32768, 2, 128);
        var table = table(config);
        for (int i = 0; i < 5; i++) { table.merge(List.of(GossipCommandsTest.member("Extra-" + i, 10 + i))); }
        var seen = new HashSet<String>();
        try (var transport = new GossipTransport(config, table)) {
            for (int i = 0; i < 5; i++) {
                int boot = 100 + i;
                try (var peer = new ProtocolPeer((socket, port, request) -> {
                    var incoming = new GossipWire(config).decode(request.parameters(), true);
                    assertEquals(1, incoming.members().size());
                    assertEquals("Self", incoming.sender().key().serverId());
                    seen.add(incoming.members().getFirst().key().serverId());
                    socket.getOutputStream().write(reply(config, request, responder(port, boot)));
                })) { transport.exchange(peer.endpoint()); }
            }
        }
        assertEquals(java.util.Set.of("Extra-0", "Extra-1", "Extra-2", "Extra-3", "Extra-4"), seen);
    }

    @Test void interruptingTheCallerEndsSelectorWaitAndReleasesItsPermit() throws Exception {
        var config = GossipCommandsTest.config(32768, 32, 128);
        var release = new CountDownLatch(1);
        try (var peer = new ProtocolPeer((socket, port, request) -> release.await(5, TimeUnit.SECONDS));
             var transport = new GossipTransport(config, table(config))) {
            var completed = new CompletableFuture<Throwable>();
            Thread caller = Thread.ofVirtual().start(() -> {
                try { transport.exchange(peer.endpoint()); completed.complete(new AssertionError("Expected interruption")); }
                catch (Throwable failure) { completed.complete(failure); }
            });
            try {
                peer.request.get(2, TimeUnit.SECONDS);
                caller.interrupt();
                assertInstanceOf(java.io.InterruptedIOException.class, completed.get(2, TimeUnit.SECONDS));
                assertTrue(caller.join(Duration.ofSeconds(2)));
                assertEquals(0, transport.inflight());
            } finally {
                caller.interrupt();
                release.countDown();
            }
        }
    }

    @FunctionalInterface private interface Answer { void run(Socket socket, int port, CommandRequest request) throws Exception; }

    private static final class ProtocolPeer implements AutoCloseable {
        private final ServerSocket listener = new ServerSocket(0, 1, java.net.InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
        private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();
        private final Future<?> task;
        private volatile Socket accepted;
        private final CompletableFuture<CommandRequest> request = new CompletableFuture<>();

        ProtocolPeer(Answer answer) throws IOException {
            task = worker.submit(() -> {
                try (var socket = listener.accept()) {
                    accepted = socket;
                    socket.setSoTimeout(3000);
                    var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    var parsed = CommandProtocol.parseRequest(reader.readLine());
                    request.complete(parsed);
                    try { answer.run(socket, listener.getLocalPort(), parsed); }
                    catch (IOException expectedDisconnect) { /* Bounded clients may close while a fixture is writing. */ }
                } catch (Exception e) { request.completeExceptionally(e); throw e; }
                return null;
            });
        }

        GossipConfig.Seed endpoint() { return new GossipConfig.Seed("Peer", "127.0.0.1", listener.getLocalPort()); }

        @Override public void close() throws Exception {
            listener.close();
            if (accepted != null) { accepted.close(); }
            try { task.get(3, TimeUnit.SECONDS); }
            finally {
                worker.shutdownNow();
                assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
            }
        }
    }
}
