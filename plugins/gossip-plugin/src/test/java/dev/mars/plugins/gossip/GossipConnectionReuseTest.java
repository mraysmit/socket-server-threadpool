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

import dev.mars.protocol.CommandProtocol;
import dev.mars.protocol.CommandReply;
import dev.mars.protocol.CommandRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Gossip keeps its connections to peers open between exchanges instead of opening one for every exchange. */
@Timeout(30)
class GossipConnectionReuseTest {
    private static final GossipConfig CONFIG = GossipCommandsTest.config(32768, 4, 8);

    private static MembershipTable table() {
        return new MembershipTable(CONFIG, GossipCommandsTest.member("Self", 1));
    }

    private static MemberRecord responder(String serverId, int port) {
        return new MemberRecord(new MemberKey(serverId, new UUID(0, 2)), "127.0.0.1", port, 3, MemberRecord.State.ALIVE, Map.of());
    }

    private static byte[] validReply(CommandRequest request, int port, String serverId) {
        return GossipWire.bytes(CommandReply.success(request, serverId, 1,
                new GossipWire(CONFIG).toWire(new GossipWire.ExchangeReply(responder(serverId, port), List.of(), MembershipTable.Admission.ADMITTED))));
    }

    private static byte[] validReply(CommandRequest request, int port) { return validReply(request, port, "Peer"); }

    /** A well-formed reply from the peer with the given server ID. */
    private static Reply named(String serverId) {
        return (out, port, request, ordinal) -> {
            out.write(validReply(request, port, serverId));
            out.write('\n');
            out.flush();
        };
    }

    /** How a peer writes its reply to one request. The ordinal counts requests over every connection, from 1. */
    interface Reply {
        void write(OutputStream out, int port, CommandRequest request, int ordinal) throws IOException;
    }

    private static final Reply NEWLINE = (out, port, request, ordinal) -> {
        out.write(validReply(request, port));
        out.write('\n');
        out.flush();
    };

    /** A peer that serves as many requests as a client sends down one connection, as the host does for keep-alive. */
    private static final class KeepAlivePeer implements AutoCloseable {
        private final ServerSocket listener;
        private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        private final List<Socket> open = new CopyOnWriteArrayList<>();
        final AtomicInteger accepted = new AtomicInteger();
        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger closedByClient = new AtomicInteger();
        /** Connections the client ended with a reset instead of a closing handshake. */
        final AtomicInteger endedByReset = new AtomicInteger();
        final List<Boolean> keepAliveRequested = Collections.synchronizedList(new ArrayList<>());
        /** False behaves like a host that closes after every reply. */
        volatile boolean keepOpen = true;

        private final String serverId;

        KeepAlivePeer(int port, Reply reply) throws IOException { this("Peer", port, reply); }

        KeepAlivePeer(String serverId, int port, Reply reply) throws IOException {
            this.serverId = serverId;
            listener = new ServerSocket(port, 50, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
            workers.submit(() -> {
                while (!listener.isClosed()) {
                    Socket socket;
                    try { socket = listener.accept(); } catch (IOException closed) { return null; }
                    accepted.incrementAndGet();
                    open.add(socket);
                    workers.submit(() -> serve(socket, reply));
                }
                return null;
            });
        }

        private void serve(Socket socket, Reply reply) {
            try (socket) {
                var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                var out = socket.getOutputStream();
                String line;
                while ((line = in.readLine()) != null) {
                    var parsed = CommandProtocol.parse(line);
                    keepAliveRequested.add(parsed.keepAlive());
                    reply.write(out, listener.getLocalPort(), parsed.request(), requests.incrementAndGet());
                    if (!keepOpen) { return; }
                }
                closedByClient.incrementAndGet();
            } catch (java.net.SocketException reset) {
                endedByReset.incrementAndGet();
            } catch (Exception connectionEnded) { /* The client or the test closed the connection. */ }
        }

        int port() { return listener.getLocalPort(); }
        GossipConfig.Seed endpoint() { return new GossipConfig.Seed(serverId, "127.0.0.1", port()); }

        @Override public void close() throws Exception {
            listener.close();
            for (var socket : open) { socket.close(); }
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < until, "Timed out waiting");
            Thread.sleep(10);
        }
    }

    @Test
    void successiveExchangesWithAPeerShareOneConnectionAndAskForKeepAlive() throws Exception {
        var metrics = new GossipMetrics();
        try (var peer = new KeepAlivePeer(0, NEWLINE); var transport = new GossipTransport(CONFIG, table(), metrics)) {
            for (int i = 0; i < 5; i++) { assertEquals("Peer", transport.exchange(peer.endpoint()).sender().key().serverId()); }
            assertEquals(1, peer.accepted.get(), "One connection carried every exchange");
            assertEquals(5, peer.requests.get());
            assertEquals(1, metrics.connectionsOpened.get());
            assertEquals(4, metrics.connectionsReused.get());
            assertEquals(0, metrics.staleConnections.get());
            assertEquals(List.of(true, true, true, true, true), peer.keepAliveRequested, "Every request asks the host to keep the connection open");
            assertEquals(0, transport.inflight());
        }
    }

    @Test
    void aPeerThatClosesAfterEveryReplyStillWorksBecauseAStaleConnectionIsRetriedOnANewOne() throws Exception {
        var metrics = new GossipMetrics();
        try (var peer = new KeepAlivePeer(0, NEWLINE); var transport = new GossipTransport(CONFIG, table(), metrics)) {
            peer.keepOpen = false;
            for (int i = 0; i < 3; i++) { assertEquals("Peer", transport.exchange(peer.endpoint()).sender().key().serverId()); }
            assertEquals(3, peer.accepted.get());
            assertEquals(3, metrics.connectionsOpened.get());
            assertEquals(2, metrics.staleConnections.get(), "Exchanges 2 and 3 found their pooled connection closed");
            assertEquals(0, metrics.outboundFailures.get(), "A retried exchange is not a failure");
        }
    }

    @Test
    void aRestartedPeerOnTheSamePortIsReachedOverANewConnection() throws Exception {
        var metrics = new GossipMetrics();
        try (var transport = new GossipTransport(CONFIG, table(), metrics)) {
            int port;
            try (var first = new KeepAlivePeer(0, NEWLINE)) {
                port = first.port();
                transport.exchange(first.endpoint());
                transport.exchange(first.endpoint());
                assertEquals(1, first.accepted.get());
            }
            try (var second = new KeepAlivePeer(port, NEWLINE)) {
                assertEquals("Peer", transport.exchange(second.endpoint()).sender().key().serverId());
                assertEquals(1, second.accepted.get());
                assertEquals(1, metrics.staleConnections.get());
                assertEquals(0, metrics.outboundFailures.get());
            }
        }
    }

    @Test
    void aPeerThatIsGoneIsReportedAsAFailureNotRetriedForever() throws Exception {
        var metrics = new GossipMetrics();
        try (var transport = new GossipTransport(CONFIG, table(), metrics)) {
            GossipConfig.Seed endpoint;
            try (var peer = new KeepAlivePeer(0, NEWLINE)) {
                endpoint = peer.endpoint();
                transport.exchange(endpoint);
            }
            assertThrows(java.io.IOException.class, () -> transport.exchange(endpoint));
            assertEquals(1, metrics.outboundFailures.get());
            assertEquals(1, metrics.staleConnections.get());
            assertEquals(0, transport.inflight());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aReplyEndedByCarriageReturnAndLineFeedDoesNotLeaveALineFeedForTheNextReply(boolean split) throws Exception {
        Reply crlf = (out, port, request, ordinal) -> {
            out.write(validReply(request, port));
            if (split) {
                out.write('\r');
                out.flush();
                try { Thread.sleep(40); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                out.write('\n');
            } else {
                out.write('\r');
                out.write('\n');
            }
            out.flush();
        };
        var metrics = new GossipMetrics();
        try (var peer = new KeepAlivePeer(0, crlf); var transport = new GossipTransport(CONFIG, table(), metrics)) {
            for (int i = 0; i < 4; i++) { assertEquals("Peer", transport.exchange(peer.endpoint()).sender().key().serverId(), "exchange " + i); }
            assertEquals(1, peer.accepted.get(), "Every exchange used the one connection");
            assertEquals(0, metrics.staleConnections.get());
        }
    }

    @Test
    void strayBytesThatArriveLaterAreSpottedBeforeTheConnectionIsReused() throws Exception {
        Reply late = (out, port, request, ordinal) -> {
            out.write(validReply(request, port));
            out.write('\n');
            out.flush();
            if (ordinal == 1) {
                try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                out.write("stray bytes the client did not ask for\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        };
        var metrics = new GossipMetrics();
        try (var peer = new KeepAlivePeer(0, late); var transport = new GossipTransport(CONFIG, table(), metrics)) {
            transport.exchange(peer.endpoint());
            Thread.sleep(400); // The stray line arrives while the connection sits idle in the pool.
            assertEquals("Peer", transport.exchange(peer.endpoint()).sender().key().serverId());
            assertEquals(1, metrics.staleConnections.get(), "Unsolicited data made the pooled connection unusable");
            assertEquals(2, metrics.connectionsOpened.get());
            assertEquals(2, peer.accepted.get());
        }
    }

    @Test
    void bytesAfterAReplyMeanTheConnectionIsNotReused() throws Exception {
        Reply untidy = (out, port, request, ordinal) -> {
            byte[] body = validReply(request, port);
            // One write, so the reply and the stray line reach the client together.
            var all = new java.io.ByteArrayOutputStream();
            all.write(body);
            all.write('\n');
            if (ordinal == 1) { all.write("stray bytes the client did not ask for\n".getBytes(StandardCharsets.UTF_8)); }
            out.write(all.toByteArray());
            out.flush();
        };
        var metrics = new GossipMetrics();
        try (var peer = new KeepAlivePeer(0, untidy); var transport = new GossipTransport(CONFIG, table(), metrics)) {
            transport.exchange(peer.endpoint());
            transport.exchange(peer.endpoint());
            transport.exchange(peer.endpoint());
            assertEquals(2, peer.accepted.get(), "The untidy connection was dropped; the next two exchanges shared a new one");
            assertEquals(2, metrics.connectionsOpened.get());
            assertEquals(1, metrics.connectionsReused.get());
            assertEquals(0, metrics.staleConnections.get());
        }
    }

    @Test
    void aHalfReceivedReplyIsNeverReadAsTheAnswerToALaterRequest() throws Exception {
        var release = new CountDownLatch(1);
        Reply stalls = (out, port, request, ordinal) -> {
            byte[] body = validReply(request, port);
            if (ordinal == 1) {
                out.write(body, 0, body.length / 2);
                out.flush();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                out.write(body, body.length / 2, body.length - body.length / 2);
                out.write('\n');
            } else {
                out.write(body);
                out.write('\n');
            }
            out.flush();
        };
        var metrics = new GossipMetrics();
        try (var peer = new KeepAlivePeer(0, stalls);
             var transport = new GossipTransport(CONFIG, table(), metrics, Duration.ofSeconds(2), Duration.ofMillis(300), Duration.ofSeconds(3))) {
            assertThrows(java.io.IOException.class, () -> transport.exchange(peer.endpoint()), "The reply stalled half way");
            release.countDown(); // The rest of the late reply is now written to a connection the transport has closed.
            var exchange = transport.exchange(peer.endpoint());
            assertEquals("Peer", exchange.sender().key().serverId());
            assertEquals(2, peer.accepted.get(), "The stalled connection was closed, not pooled");
            assertEquals(2, metrics.connectionsOpened.get());
            assertEquals(0, metrics.connectionsReused.get());
        }
    }

    @Test
    void closingTheTransportClosesItsKeptOpenConnections() throws Exception {
        try (var peer = new KeepAlivePeer(0, NEWLINE)) {
            var transport = new GossipTransport(CONFIG, table(), new GossipMetrics());
            transport.exchange(peer.endpoint());
            assertEquals(0, peer.closedByClient.get() + peer.endedByReset.get(), "The connection is being kept open");
            transport.close();
            await(() -> peer.closedByClient.get() + peer.endedByReset.get() == 1);
        }
    }

    @Test
    void closingTheTransportResetsItsIdleConnectionsSoTheyLeaveNoTimeWaitBehind() throws Exception {
        try (var peer = new KeepAlivePeer(0, NEWLINE)) {
            var transport = new GossipTransport(CONFIG, table(), new GossipMetrics());
            transport.exchange(peer.endpoint());
            transport.exchange(peer.endpoint());
            assertEquals(0, peer.endedByReset.get());
            transport.close();
            await(() -> peer.endedByReset.get() == 1);
            assertEquals(0, peer.closedByClient.get(), "A reset, not a closing handshake");
        }
    }

    @Test
    void aConnectionIsRetiredBeforeTheHostsRequestLimitAndReplacedByANewOne() throws Exception {
        var metrics = new GossipMetrics();
        try (var peer = new KeepAlivePeer(0, NEWLINE); var transport = new GossipTransport(CONFIG, table(), metrics)) {
            for (int i = 0; i < 905; i++) { transport.exchange(peer.endpoint()); }
            assertEquals(2, peer.accepted.get(), "A second connection took over after 900 exchanges on the first");
            assertEquals(2, metrics.connectionsOpened.get());
            assertEquals(903, metrics.connectionsReused.get());
            assertEquals(0, metrics.staleConnections.get());
        }
    }

    @Test
    void exchangesWithDifferentPeersUseDifferentConnectionsAndOnlyOnePerPeer() throws Exception {
        var metrics = new GossipMetrics();
        try (var a = new KeepAlivePeer("Alpha", 0, named("Alpha")); var b = new KeepAlivePeer("Beta", 0, named("Beta"));
             var transport = new GossipTransport(CONFIG, table(), metrics)) {
            for (int i = 0; i < 3; i++) {
                transport.exchange(a.endpoint());
                transport.exchange(b.endpoint());
            }
            assertEquals(1, a.accepted.get());
            assertEquals(1, b.accepted.get());
            assertEquals(2, metrics.connectionsOpened.get());
            assertEquals(4, metrics.connectionsReused.get());
        }
    }

    @Test
    void theRuntimeStatusReportsConnectionCounts() {
        var metrics = new GossipMetrics();
        metrics.connectionsOpened.addAndGet(3);
        metrics.connectionsReused.addAndGet(40);
        metrics.staleConnections.addAndGet(2);
        assertEquals(Map.of("opened", 3L, "reused", 40L, "stale", 2L), metrics.runtime().get("connections"));
    }
}
