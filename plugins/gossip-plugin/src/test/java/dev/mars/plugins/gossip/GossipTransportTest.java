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

import dev.mars.client.CommandExecutionException;
import dev.mars.client.TextMessageClient;
import dev.mars.protocol.CommandError;
import dev.mars.protocol.CommandProtocol;
import dev.mars.protocol.CommandReply;
import dev.mars.protocol.CommandRequest;
import dev.mars.testing.TestServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
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
        DUPLICATE, UNKNOWN_FIELD, MALFORMED_JSON, EMPTY_REPLY, INVALID_UTF8, OVERSIZE_BYTES, OVERSIZE_UNICODE, REMOTE_CAPACITY,
        ADMISSION_MISSING, ADMISSION_INVALID }

    private static MembershipTable table(GossipConfig config) {
        return new MembershipTable(config, GossipCommandsTest.member("Self", 1));
    }

    private static MemberRecord responder(int port, long boot) {
        var key = new MemberKey("Peer", new UUID(0, boot));
        return new MemberRecord(key, "127.0.0.1", port, 3, MemberRecord.State.ALIVE, Map.of());
    }

    private static byte[] reply(GossipConfig config, CommandRequest request, MemberRecord sender) {
        return line(GossipWire.bytes(CommandReply.success(request, "Peer", 1, new GossipWire(config).result(sender, List.of(),
                ((Number) request.parameters().get("gossipVersion")).intValue(), MembershipTable.Admission.ADMITTED))));
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
                assertEquals(category(failure), GossipFailure.of(error), "Metrics category");
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

    @Test void outgoingIdentityMismatchLogsItsPeerAndMessageIdWithoutChangingTheFailure() throws Exception {
        var config = GossipCommandsTest.config(32768, 4, 8);
        var table = table(config);
        var lines = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var metrics = new GossipMetrics(new GossipLog(config, table.snapshot().self(), System::nanoTime, lines::add));
        var correlation = new CompletableFuture<UUID>();
        try (var transport = new GossipTransport(config, table, metrics);
             var peer = new ProtocolPeer((socket, port, request) -> {
                 correlation.complete(request.messageId());
                 socket.getOutputStream().write(badReply(config, port, request, Failure.SERVER_ID));
             })) {
            var failure = assertThrows(IOException.class, () -> transport.exchange(peer.endpoint()));
            assertEquals("Gossip server ID mismatch", failure.getMessage());
            assertEquals(1, table.snapshot().retainedIdentities());
            assertEquals(0, transport.inflight());
            assertEquals(1, metrics.outboundFailures.get());
            assertEquals(1, lines.size());
            for (var field : List.of("event=IDENTITY_CONFLICT", "direction=outbound", "peerServerId=Peer",
                    "peerPort=" + peer.endpoint().port(), "messageId=" + correlation.get(2, TimeUnit.SECONDS))) {
                assertTrue(lines.getFirst().contains(field), lines.getFirst());
            }
        }
    }

    @Test void reachableDuplicateLogicalIdsAreLoggedAfterSuccessfulValidatedExchanges() throws Exception {
        var config = GossipCommandsTest.config(32768, 4, 8);
        var table = table(config);
        var lines = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var metrics = new GossipMetrics(new GossipLog(config, table.snapshot().self(), System::nanoTime, lines::add));
        try (var transport = new GossipTransport(config, table, metrics)) {
            for (int boot = 2; boot <= 3; boot++) {
                int identity = boot;
                try (var peer = new ProtocolPeer((socket, port, request) ->
                        socket.getOutputStream().write(reply(config, request, responder(port, identity))))) {
                    transport.exchange(peer.endpoint());
                }
            }
            assertEquals(java.util.Set.of("Peer"), table.snapshot().identityConflicts());
            assertEquals(2, metrics.outbound.get());
            assertEquals(1, lines.size());
            assertTrue(lines.getFirst().contains("Reachable duplicate server IDs: [Peer]"));
        }
    }

    /** The metrics category each bad reply is counted under. */
    private static GossipFailure category(Failure failure) {
        return switch (failure) {
            case MESSAGE_ID, SERVER_ID, SENDER_ID -> GossipFailure.IDENTITY;
            case VERSION -> GossipFailure.VERSION;
            case SWARM -> GossipFailure.SWARM;
            case REMOTE_CAPACITY -> GossipFailure.CAPACITY;
            case EMPTY_REPLY -> GossipFailure.NETWORK;
            case BOOT_ID, FRACTIONAL_HEARTBEAT, DUPLICATE, UNKNOWN_FIELD, MALFORMED_JSON, INVALID_UTF8, OVERSIZE_BYTES,
                 OVERSIZE_UNICODE, ADMISSION_MISSING, ADMISSION_INVALID -> GossipFailure.PROTOCOL;
        };
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
        var result = new LinkedHashMap<>(new GossipWire(config).result(sender, List.of(),
                ((Number) request.parameters().get("gossipVersion")).intValue(), MembershipTable.Admission.ADMITTED));
        var fields = new LinkedHashMap<>(sender.toWire());
        UUID id = request.messageId();
        String outer = "Peer";
        switch (failure) {
            case MESSAGE_ID -> id = UUID.randomUUID();
            case SERVER_ID -> outer = "Other";
            case SENDER_ID -> fields.put("serverId", "Other");
            case VERSION -> result.put("gossipVersion", 3);
            case SWARM -> result.put("swarmId", "other");
            case BOOT_ID -> fields.put("bootId", "1-1-1-1-1");
            case FRACTIONAL_HEARTBEAT -> fields.put("heartbeat", 3.5);
            case DUPLICATE -> result.put("members", List.of(sender.toWire()));
            case UNKNOWN_FIELD -> result.put("unexpected", true);
            case ADMISSION_MISSING -> result.remove("senderAdmission");
            case ADMISSION_INVALID -> result.put("senderAdmission", true);
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

    @ParameterizedTest @ValueSource(strings = {"INVALID_RECORD: gossipVersion must be between 1 and 1", "INVALID_RECORD: Unsupported gossipVersion"})
    void legacyVersionRejectionRetriesOnceWithFreshCorrelationAndPreservesSuccessfulMetrics(String rejection) throws Exception {
        var config = GossipCommandsTest.config(32768, 4, 8);
        var table = table(config);
        var metrics = new GossipMetrics();
        var attempts = new java.util.concurrent.CopyOnWriteArrayList<CommandRequest>();
        try (var transport = new GossipTransport(config, table, metrics);
             var peer = new ProtocolPeer(2, (socket, port, request) -> {
                 attempts.add(request);
                 if (attempts.size() == 1) {
                     assertEquals(2, ((Number) request.parameters().get("gossipVersion")).intValue());
                     socket.getOutputStream().write(line(GossipWire.bytes(CommandReply.failure(request.messageId(), "Peer", 1,
                             CommandError.Code.INVALID_PARAMETERS, rejection))));
                 } else {
                     assertEquals(1, ((Number) request.parameters().get("gossipVersion")).intValue());
                     socket.getOutputStream().write(reply(config, request, responder(port, 2)));
                 }
             })) {
            var result = transport.exchange(peer.endpoint());
            assertEquals(1, result.gossipVersion());
            assertNull(result.senderAdmission());
            assertEquals(2, attempts.size());
            assertNotEquals(attempts.getFirst().messageId(), attempts.getLast().messageId());
            assertEquals(attempts.getFirst().parameters().get("members"), attempts.getLast().parameters().get("members"));
            assertEquals(1, metrics.outbound.get());
            assertEquals(0, metrics.outboundFailures.get());
            assertEquals(0, transport.inflight());
            System.out.println("Admission compatibility test PASSED | attemptedVersion=2 | fallbackVersion=1 | admission=unknown");
        }
    }

    /** Rejects version 2 exactly as deployed version-1 code does, unless upgraded; answers version 1 normally. */
    private static Answer version1Peer(GossipConfig config, List<Integer> versions, java.util.function.BooleanSupplier upgraded, long boot) {
        return (socket, port, request) -> {
            int version = ((Number) request.parameters().get("gossipVersion")).intValue();
            versions.add(version);
            if (version == 2 && !upgraded.getAsBoolean()) {
                socket.getOutputStream().write(line(GossipWire.bytes(CommandReply.failure(request.messageId(), "Peer", 1,
                        CommandError.Code.INVALID_PARAMETERS, "INVALID_RECORD: Unsupported gossipVersion"))));
            } else { socket.getOutputStream().write(reply(config, request, responder(port, boot))); }
        };
    }

    @Test void aPeerThatRejectsVersion2IsSentVersion1DirectlyAfterwards() throws Exception {
        var config = GossipCommandsTest.config(32768, 4, 8);
        var versions = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
        // Four connections in total: the peer fixture fails on close if the transport opens more or fewer.
        try (var transport = new GossipTransport(config, table(config), new GossipMetrics());
             var peer = new ProtocolPeer(4, version1Peer(config, versions, () -> false, 2))) {
            for (int i = 0; i < 3; i++) { assertEquals(1, transport.exchange(peer.endpoint()).gossipVersion()); }
            assertEquals(List.of(2, 1, 1, 1), versions, "Only the first exchange pays for the rejected version-2 attempt");
            assertEquals(1, transport.version1PeerCount());
        }
    }

    @Test void aRememberedVersion1PeerIsTriedWithVersion2AgainAfterTheRecheckInterval() throws Exception {
        var config = GossipCommandsTest.config(32768, 4, 8);
        var clock = new java.util.concurrent.atomic.AtomicLong();
        var upgraded = new java.util.concurrent.atomic.AtomicBoolean();
        var versions = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
        try (var transport = new GossipTransport(config, table(config), new GossipMetrics(), Duration.ofSeconds(5),
                Duration.ofSeconds(10), Duration.ofSeconds(15), clock::get);
             var peer = new ProtocolPeer(4, version1Peer(config, versions, upgraded::get, 2))) {
            transport.exchange(peer.endpoint());
            clock.addAndGet(GossipTransport.VERSION_1_RECHECK.toNanos() - 1);
            assertEquals(1, transport.exchange(peer.endpoint()).gossipVersion(), "Still within the recheck interval");
            upgraded.set(true);
            clock.addAndGet(1);
            var current = transport.exchange(peer.endpoint());
            assertEquals(2, current.gossipVersion(), "The upgraded peer is offered version 2 again");
            assertEquals(MembershipTable.Admission.ADMITTED, current.senderAdmission());
            assertEquals(List.of(2, 1, 1, 2), versions);
            assertEquals(0, transport.version1PeerCount());
        }
    }

    @Test void aFailedVersion1RetryIsNotRememberedAsAVersion1Peer() throws Exception {
        var config = GossipCommandsTest.config(32768, 4, 8);
        var versions = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
        try (var transport = new GossipTransport(config, table(config), new GossipMetrics());
             var peer = new ProtocolPeer(4, (socket, port, request) -> {
                 int version = ((Number) request.parameters().get("gossipVersion")).intValue();
                 versions.add(version);
                 socket.getOutputStream().write(line(GossipWire.bytes(CommandReply.failure(request.messageId(), "Peer", 1,
                         CommandError.Code.INVALID_PARAMETERS, version == 2 ? "INVALID_RECORD: Unsupported gossipVersion" : "CAPACITY: full"))));
             })) {
            for (int i = 0; i < 2; i++) { assertThrows(CommandExecutionException.class, () -> transport.exchange(peer.endpoint())); }
            assertEquals(List.of(2, 1, 2, 1), versions, "Without a completed version-1 exchange, version 2 is tried first again");
            assertEquals(0, transport.version1PeerCount());
        }
    }

    @Test void version1MemoryHoldsAtMostOneEntryPerMemberOrSeed() throws Exception {
        var config = GossipCommandsTest.config(32768, 4, 2);
        assertEquals(2, config.maxMembers() + config.seeds().size());
        try (var transport = new GossipTransport(config, table(config), new GossipMetrics())) {
            for (int i = 0; i < 3; i++) {
                var versions = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
                try (var peer = new ProtocolPeer(2, version1Peer(config, versions, () -> false, 10L + i))) {
                    transport.exchange(peer.endpoint());
                    assertEquals(List.of(2, 1), versions);
                }
                assertEquals(Math.min(i + 1, 2), transport.version1PeerCount());
            }
        }
    }

    @Test void uncorrelatedVersionErrorsAndUnsolicitedLegacySuccessCannotDowngradeTheExchange() throws Exception {
        var config = GossipCommandsTest.config(32768, 4, 8);
        for (String kind : List.of("messageId", "serverId", "errorCode", "unrelatedError", "legacySuccess")) {
            var table = table(config);
            try (var transport = new GossipTransport(config, table, Duration.ofMillis(200), Duration.ofMillis(200), Duration.ofMillis(400));
                 var peer = new ProtocolPeer((socket, port, request) -> {
                     Object response = kind.equals("legacySuccess")
                             ? CommandReply.success(request, "Peer", 1, new GossipWire(config).result(responder(port, 2), List.of()))
                             : CommandReply.failure(kind.equals("messageId") ? UUID.randomUUID() : request.messageId(),
                             kind.equals("serverId") ? "Other" : "Peer", 1,
                             kind.equals("errorCode") ? CommandError.Code.UNKNOWN_INSTRUCTION : CommandError.Code.INVALID_PARAMETERS,
                             kind.equals("unrelatedError") ? "INVALID_RECORD: Invalid exchange fields" : "INVALID_RECORD: Unsupported gossipVersion");
                     socket.getOutputStream().write(line(GossipWire.bytes(response)));
                 })) {
                var failure = assertThrows(IOException.class, () -> transport.exchange(peer.endpoint()));
                if (kind.equals("errorCode") || kind.equals("unrelatedError")) { assertInstanceOf(CommandExecutionException.class, failure); }
                else if (kind.equals("legacySuccess")) { assertEquals(GossipFailure.VERSION, GossipFailure.of(failure)); }
                else { assertEquals(GossipFailure.IDENTITY, GossipFailure.of(failure)); }
                assertEquals(1, table.snapshot().retainedIdentities());
                assertEquals(0, transport.inflight());
            }
        }
    }

    @Test void unreachableAddressFallsBackToTheHostsNextAddressWithinOneExchange() throws Exception {
        var config = GossipCommandsTest.config(32768, 4, 8);
        var metrics = new GossipMetrics();
        try (var transport = new GossipTransport(config, table(config), metrics);
             var peer = new ProtocolPeer((socket, port, request) -> socket.getOutputStream().write(reply(config, request, responder(port, 2))))) {
            // The peer listens on 127.0.0.1 only, so the same port on 127.0.0.2 cannot be connected.
            var unreachable = new GossipConfig.Seed("Peer", "127.0.0.2", peer.endpoint().port());
            var result = transport.exchange(List.of(unreachable, peer.endpoint()));
            assertEquals("Peer", result.sender().key().serverId());
            assertEquals(1, metrics.outbound.get());
            assertEquals(0, metrics.outboundFailures.get());
            assertEquals(0, transport.inflight());
        }
    }

    @Test void whenNoAddressConnectsTheFirstConnectionErrorIsReportedWithTheRestSuppressed() throws Exception {
        var config = GossipCommandsTest.config(32768, 4, 8);
        var metrics = new GossipMetrics();
        int closed;
        try (var reservation = new ServerSocket(0, 1, java.net.InetAddress.ofLiteral("127.0.0.1"))) { closed = reservation.getLocalPort(); }
        try (var transport = new GossipTransport(config, table(config), metrics)) {
            var failure = assertThrows(java.net.SocketException.class, () -> transport.exchange(List.of(
                    new GossipConfig.Seed("Peer", "127.0.0.1", closed), new GossipConfig.Seed("Peer", "127.0.0.2", closed))));
            assertEquals(1, failure.getSuppressed().length);
            assertEquals(GossipFailure.NETWORK, GossipFailure.of(failure));
            assertEquals(1, metrics.outboundFailures.get(), "One exchange failed, however many addresses it tried");
            assertEquals(0, transport.inflight());
        }
    }

    @Test void timeoutsDoNotFallBackToAnotherAddress() throws Exception {
        var config = GossipCommandsTest.config(32768, 4, 8);
        try (var transport = new GossipTransport(config, table(config), Duration.ofMillis(200), Duration.ofMillis(200), Duration.ofMillis(400));
             var peer = new ProtocolPeer((socket, port, request) -> TimeUnit.MILLISECONDS.sleep(1000))) {
            // A fallback to 127.0.0.2 would surface as a connection error instead of the timeout.
            var unreachable = new GossipConfig.Seed("Peer", "127.0.0.2", peer.endpoint().port());
            assertThrows(SocketTimeoutException.class, () -> transport.exchange(List.of(peer.endpoint(), unreachable)));
            assertEquals(0, transport.inflight());
        }
    }

    @Test void fallbackAddressesMustBelongToTheSamePeerAndPort() throws Exception {
        var config = GossipCommandsTest.config(32768, 4, 8);
        try (var transport = new GossipTransport(config, table(config))) {
            assertThrows(IllegalArgumentException.class, () -> transport.exchange(List.of(
                    new GossipConfig.Seed("Peer", "127.0.0.1", 9100), new GossipConfig.Seed("Other", "127.0.0.2", 9100))));
            assertThrows(IllegalArgumentException.class, () -> transport.exchange(List.of(
                    new GossipConfig.Seed("Peer", "127.0.0.1", 9100), new GossipConfig.Seed("Peer", "127.0.0.2", 9200))));
            assertEquals(0, transport.inflight());
        }
    }

    @Test void versionFallbackSharesTheOriginalOverallDeadlineAndReleasesItsPermit() throws Exception {
        var config = GossipCommandsTest.config(32768, 4, 8);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        try (var transport = new GossipTransport(config, table(config), Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(1));
             var peer = new ProtocolPeer(2, (socket, port, request) -> {
                 int attempt = attempts.incrementAndGet();
                 TimeUnit.MILLISECONDS.sleep(600);
                 if (attempt == 1) {
                     socket.getOutputStream().write(line(GossipWire.bytes(CommandReply.failure(request.messageId(), "Peer", 1,
                             CommandError.Code.INVALID_PARAMETERS, "INVALID_RECORD: Unsupported gossipVersion"))));
                 } else { socket.getOutputStream().write(reply(config, request, responder(port, 2))); }
             })) {
            assertThrows(SocketTimeoutException.class, () -> transport.exchange(peer.endpoint()));
            assertEquals(2, attempts.get());
            assertEquals(0, transport.inflight());
        }
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

    @Test void replyArrivingInSmallChunksKeepsTheExactByteLimitAndTerminators() throws Exception {
        // Each chunk is a separate read, so the terminator scan must resume where the previous read stopped.
        var config = GossipCommandsTest.config(1024, 32, 128);
        // A short line leaves room for the bytes after its terminator to arrive in the same read.
        for (int extra : List.of(-400, 0, 1)) {
            for (String terminator : List.of("\n", "\r\n", "\r", "")) {
                // A fresh table per case: each peer reuses one identity on a new port.
                try (var transport = new GossipTransport(config, table(config));
                     var peer = new ProtocolPeer((socket, port, request) -> {
                    byte[] ordinary = reply(config, request, responder(port, 2));
                    byte[] padded = new byte[1024 + extra];
                    Arrays.fill(padded, (byte) ' ');
                    System.arraycopy(ordinary, 0, padded, 0, ordinary.length - 1);
                    // Bytes after the terminator belong to no reply and must be ignored.
                    byte[] tail = (terminator + (terminator.isEmpty() ? "" : "trailing garbage")).getBytes(StandardCharsets.UTF_8);
                    byte[] framed = Arrays.copyOf(padded, padded.length + tail.length);
                    System.arraycopy(tail, 0, framed, padded.length, tail.length);
                    var output = socket.getOutputStream();
                    for (int offset = 0; offset < framed.length; offset += 37) {
                        output.write(framed, offset, Math.min(37, framed.length - offset));
                        output.flush();
                        TimeUnit.MILLISECONDS.sleep(1);
                    }
                    // No terminator at all: the peer closing the connection ends the reply.
                    if (terminator.isEmpty()) { socket.shutdownOutput(); }
                })) {
                    if (extra <= 0) {
                        assertEquals("Peer", transport.exchange(peer.endpoint()).sender().key().serverId(),
                                "terminator=" + terminator.replace("\r", "\\r").replace("\n", "\\n"));
                    } else {
                        var failure = assertThrows(IOException.class, () -> transport.exchange(peer.endpoint()));
                        assertEquals("Gossip reply exceeds max.exchange.bytes before parsing", failure.getMessage());
                    }
                    assertEquals(0, transport.inflight());
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
        try (var a = TestServer.start("Server-A", first); var b = TestServer.start("Server-B", second)) {
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
        try (var _ = TestServer.start("Server-A", first); var b = TestServer.start("Server-B", second)) {
            var client = new TextMessageClient("127.0.0.1", b.getPort(), "Server-B");
            client.execute("gossip", new GossipWire(config).parameters(GossipCommandsTest.member("Third", 3), List.of()));
            var reply = first.exchangeWith(new GossipConfig.Seed("Server-B", "127.0.0.1", b.getPort()));
            assertEquals("Server-B", reply.sender().key().serverId());
            assertEquals(2, reply.gossipVersion());
            assertEquals(MembershipTable.Admission.CAPACITY, reply.senderAdmission());
            var status = GossipCommandsTest.execute(first, Map.of("operation", "status"));
            assertEquals(false, status.get("isolated"));
            assertEquals(2, ((Map<?, ?>) status.get("capacity")).get("retainedIdentities"));
            assertEquals(2, ((Map<?, ?>) GossipCommandsTest.execute(second, Map.of("operation", "status")).get("capacity")).get("retainedIdentities"));
            assertTrue(GossipSwarmTest.members(first).stream().anyMatch(row -> "Server-B".equals(row.get("serverId"))));
            assertTrue(GossipSwarmTest.members(second).stream().noneMatch(row -> "Server-A".equals(row.get("serverId"))));
            assertEquals(1L, ((Map<?, ?>) GossipCommandsTest.execute(second, Map.of("operation", "status")).get("capacity")).get("admissionsSkipped"));
            System.out.println("Admission hint test PASSED | receiver=Server-B | sender=Server-A | senderAdmission=CAPACITY | admissionsSkipped=1");
        }
    }

    @Test void anAliasForTheLocalEndpointCannotTurnASelfExchangeIntoBootstrapSuccess() throws Exception {
        var config = GossipCommandsTest.config(32768, 32, 128);
        var plugin = new GossipPlugin(config, false);
        try (var server = TestServer.start("Self", plugin)) {
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

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void interruptingTheCallerEndsItsExchangeAndReleasesItsPermit(boolean virtual) throws Exception {
        var config = GossipCommandsTest.config(32768, 32, 128);
        var release = new CountDownLatch(1);
        try (var peer = new ProtocolPeer((socket, port, request) -> release.await(5, TimeUnit.SECONDS));
             var transport = new GossipTransport(config, table(config))) {
            var completed = new CompletableFuture<Throwable>();
            // Unlike a virtual thread, a platform thread is not woken from blocking socket I/O by its own interrupt.
            Thread.Builder threads = virtual ? Thread.ofVirtual() : Thread.ofPlatform();
            Thread caller = threads.start(() -> {
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

    @Test void watchdogEndsAWriteStillBlockedAtTheExchangeDeadline() throws Exception {
        var config = GossipCommandsTest.config(32768, 32, 128);
        var writing = new CountDownLatch(1);
        try (var acceptor = Executors.newVirtualThreadPerTaskExecutor();
             var listener = new ServerSocket(0, 1, java.net.InetAddress.getByAddress(new byte[] {127, 0, 0, 1}))) {
            Future<Socket> accepted = acceptor.submit(listener::accept);
            var peer = new GossipConfig.Seed("Peer", "127.0.0.1", listener.getLocalPort());
            try (var transport = new GossipTransport(config, table(config), new GossipMetrics(), Duration.ofSeconds(1),
                    Duration.ofSeconds(10), Duration.ofSeconds(1), System::nanoTime, () -> new StalledWriteSocket(writing))) {
                var completed = new CompletableFuture<Throwable>();
                long start = System.nanoTime();
                Thread.ofVirtual().start(() -> {
                    try { transport.exchange(peer); completed.complete(new AssertionError("Expected the deadline to end the write")); }
                    catch (Throwable failure) { completed.complete(failure); }
                });
                assertTrue(writing.await(2, TimeUnit.SECONDS));
                // Connects and reads have socket timeouts; only the watchdog can end a write.
                var failure = assertInstanceOf(SocketTimeoutException.class, completed.get(3, TimeUnit.SECONDS));
                assertEquals("Gossip I/O deadline exceeded", failure.getMessage());
                assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(950), "Ended by the one-second deadline");
                assertEquals(0, transport.inflight());
            } finally {
                if (accepted.state() == Future.State.SUCCESS) { accepted.resultNow().close(); }
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void exchangeDoesNotReturnUntilItsWatchdogHasEnded(boolean interrupted) throws Exception {
        var config = GossipCommandsTest.config(32768, 32, 128);
        var polled = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var peer = new ProtocolPeer((socket, port, request) -> { });
             var transport = new GossipTransport(config, table(config))) {
            var completed = new CompletableFuture<Throwable>();
            var interruptKept = new java.util.concurrent.atomic.AtomicBoolean();
            // The watchdog of a platform caller polls the caller's interrupt status; this caller holds it in that poll.
            var caller = new Thread(() -> {
                try { transport.exchange(peer.endpoint()); completed.complete(new AssertionError("Expected the peer to hang up")); }
                catch (Throwable failure) { interruptKept.set(Thread.currentThread().isInterrupted()); completed.complete(failure); }
            }) {
                @Override public boolean isInterrupted() {
                    if (Thread.currentThread() != this) {
                        boolean first = polled.getCount() > 0;
                        polled.countDown();
                        if (first && interrupted) { interrupt(); }
                        boolean woken = false;
                        while (release.getCount() > 0) {
                            try { release.await(); } catch (InterruptedException _) { woken = true; }
                        }
                        if (woken) { Thread.currentThread().interrupt(); }
                    }
                    return super.isInterrupted();
                }
            };
            caller.start();
            try {
                assertTrue(polled.await(2, TimeUnit.SECONDS));
                peer.request.get(2, TimeUnit.SECONDS);
                // The peer hangs up at once, so only the held watchdog can keep the exchange from returning.
                assertThrows(java.util.concurrent.TimeoutException.class, () -> completed.get(300, TimeUnit.MILLISECONDS));
                release.countDown();
                assertInstanceOf(IOException.class, completed.get(2, TimeUnit.SECONDS));
                assertEquals(interrupted, interruptKept.get());
                assertTrue(caller.join(Duration.ofSeconds(2)));
                assertEquals(0, transport.inflight());
            } finally { release.countDown(); }
        }
    }

    /**
     * A socket whose writes block until it is closed, as a real write does once a peer that never reads has filled
     * the buffers. A real loopback socket cannot show this: Windows, for one, accepts a whole request regardless of
     * its buffer sizes.
     */
    private static final class StalledWriteSocket extends Socket {
        private final CountDownLatch writing;
        private final CountDownLatch closed = new CountDownLatch(1);

        StalledWriteSocket(CountDownLatch writing) { this.writing = writing; }

        @Override public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override public void write(int b) throws IOException { write(new byte[] {(byte) b}, 0, 1); }

                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    writing.countDown();
                    boolean interrupted = false;
                    while (closed.getCount() > 0) {
                        try { closed.await(); } catch (InterruptedException _) { interrupted = true; }
                    }
                    if (interrupted) { Thread.currentThread().interrupt(); }
                    throw new SocketException("Socket closed");
                }
            };
        }

        @Override public void close() throws IOException {
            closed.countDown();
            super.close();
        }
    }

    @Test void aClosedTransportRefusesWorkWithoutCountingAFailedExchange() throws Exception {
        var config = GossipCommandsTest.config(32768, 4, 8);
        var metrics = new GossipMetrics();
        var transport = new GossipTransport(config, table(config), metrics);
        transport.close();
        var refused = assertThrows(IOException.class, () -> transport.exchange(new GossipConfig.Seed("Peer", "127.0.0.1", 9)));
        assertEquals("Gossip transport is closed", refused.getMessage());
        assertEquals(0, metrics.outboundFailures.get(), "Refusing work is not a failed exchange");
        assertEquals(0, transport.inflight());
    }

    @Test void anExchangeWithThisProcessIsRefusedBeforeAnyConnection() throws Exception {
        var config = GossipCommandsTest.config(32768, 4, 8);
        var table = table(config);
        try (var transport = new GossipTransport(config, table)) {
            var self = table.self();
            var refused = assertThrows(IOException.class, () -> transport.exchange(new GossipConfig.Seed("Anyone", self.host(), self.port())));
            assertEquals("Cannot exchange with the local endpoint", refused.getMessage());
        }
    }

    @Test void aVersion1RetryGoesOnlyToTheAddressThatAnswered() throws Exception {
        var config = GossipCommandsTest.config(32768, 4, 8);
        var versions = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
        var stray = new java.util.concurrent.atomic.AtomicReference<ServerSocket>();
        var legacy = version1Peer(config, versions, () -> false, 2);
        try (var peer = new ProtocolPeer(2, (socket, port, request) -> {
                 // The address that refused the first connection listens from now on, so a retry there would reach it.
                 if (stray.get() == null) { stray.set(new ServerSocket(port, 1, java.net.InetAddress.getByAddress(new byte[] {127, 0, 0, 2}))); }
                 legacy.run(socket, port, request);
             });
             var transport = new GossipTransport(config, table(config), Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(15))) {
            var answering = peer.endpoint();
            var refusing = new GossipConfig.Seed(answering.serverId(), "127.0.0.2", answering.port());
            try {
                transport.exchange(List.of(refusing, answering));
                assertEquals(List.of(2, 1), versions);
                stray.get().setSoTimeout(1);
                assertThrows(SocketTimeoutException.class, () -> stray.get().accept().close(), "The retry must not try the address that refused");
            } finally { if (stray.get() != null) { stray.get().close(); } }
        }
    }

    @Test void recordsThatCouldNotBeForwardedAreRejectedBeforeMerging() throws Exception {
        var config = GossipCommandsTest.config(4096, 4, 8);
        // A large local record leaves too little room beside it, in this process's own replies, for the large member.
        var self = new MemberRecord(new MemberKey("Self", new UUID(0, 1)), "127.0.0.1", 9000, 1, MemberRecord.State.ALIVE,
                Map.of("text", "x".repeat(1800)));
        var table = new MembershipTable(config, self);
        var large = new MemberRecord(new MemberKey("Large", new UUID(0, 7)), "127.0.0.1", 9300, 1, MemberRecord.State.ALIVE,
                Map.of("text", "y".repeat(2300)));
        try (var transport = new GossipTransport(config, table);
             var peer = new ProtocolPeer((socket, port, request) -> socket.getOutputStream().write(line(GossipWire.bytes(
                     CommandReply.success(request, "Peer", 1, new GossipWire(config).result(responder(port, 2), List.of(large),
                             ((Number) request.parameters().get("gossipVersion")).intValue(), MembershipTable.Admission.ADMITTED))))))) {
            var rejected = assertThrows(IOException.class, () -> transport.exchange(peer.endpoint()));
            assertTrue(rejected.getMessage().contains("Complete reply exceeds max.exchange.bytes"), rejected.getMessage());
            assertEquals(1, table.snapshot().retainedIdentities(), "Neither the responder nor the large member is merged");
        }
    }

    @Test void aTransportClosedWhileItsReplyArrivesMergesNothing() throws Exception {
        var config = GossipCommandsTest.config(32768, 4, 8);
        var table = table(config);
        var owner = new java.util.concurrent.atomic.AtomicReference<GossipTransport>();
        Runnable closeTransport = () -> {
            try { owner.get().close(); } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        };
        try (var peer = new ProtocolPeer((socket, port, request) -> socket.getOutputStream().write(reply(config, request, responder(port, 2))));
             var transport = new GossipTransport(config, table, new GossipMetrics(), Duration.ofSeconds(1), Duration.ofSeconds(2),
                     Duration.ofSeconds(5), System::nanoTime, () -> new CloseOnReplySocket(closeTransport))) {
            owner.set(transport);
            var refused = assertThrows(IOException.class, () -> transport.exchange(peer.endpoint()));
            assertEquals("Gossip transport is closed", refused.getMessage());
            assertEquals(1, table.snapshot().retainedIdentities(), "The reply arrived, but nothing was merged");
        }
    }

    @Test void anIdentityConflictFoundWhileMergingAReplyIsLogged() throws Exception {
        var config = GossipCommandsTest.config(32768, 4, 8);
        var table = table(config);
        var lines = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var metrics = new GossipMetrics(new GossipLog(config, table.snapshot().self(), System::nanoTime, lines::add));
        try (var transport = new GossipTransport(config, table, metrics);
             var peer = new ProtocolPeer((socket, port, request) -> socket.getOutputStream().write(reply(config, request, responder(port, 2))))) {
            // The table already holds the responder's version with another endpoint, so its reply conflicts.
            var held = responder(peer.endpoint().port(), 2);
            table.merge(List.of(new MemberRecord(held.key(), held.host(), held.port() + 1, held.heartbeat(), held.state(), held.metadata())));
            var conflict = assertThrows(IOException.class, () -> transport.exchange(peer.endpoint()));
            assertTrue(conflict.getMessage().startsWith("Gossip CONFLICT"), conflict.getMessage());
            assertEquals(1, lines.size());
            assertTrue(lines.getFirst().contains("event=IDENTITY_CONFLICT") && lines.getFirst().contains("direction=outbound"), lines.getFirst());
        }
    }

    /** A socket that closes the transport once the reply's last line has been read, before the exchange can merge it. */
    private static final class CloseOnReplySocket extends Socket {
        private final Runnable onReply;
        private boolean done;

        CloseOnReplySocket(Runnable onReply) { this.onReply = onReply; }

        @Override public java.io.InputStream getInputStream() throws IOException {
            return new java.io.FilterInputStream(super.getInputStream()) {
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    int count = super.read(bytes, offset, length);
                    for (int i = 0; i < count && !done; i++) {
                        if (bytes[offset + i] == '\n') { done = true; onReply.run(); }
                    }
                    return count;
                }
            };
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
            this(1, answer);
        }

        ProtocolPeer(int attempts, Answer answer) throws IOException {
            task = worker.submit(() -> {
                for (int attempt = 0; attempt < attempts; attempt++) {
                    try (var socket = listener.accept()) {
                        accepted = socket;
                        socket.setSoTimeout(3000);
                        var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                        var parsed = CommandProtocol.parseRequest(reader.readLine());
                        request.complete(parsed);
                        try { answer.run(socket, listener.getLocalPort(), parsed); }
                        catch (IOException _) { /* Bounded clients may close while a fixture is writing. */ }
                    } catch (Exception e) { request.completeExceptionally(e); throw e; }
                }
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
