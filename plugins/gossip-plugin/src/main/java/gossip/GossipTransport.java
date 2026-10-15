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
import dev.mars.protocol.CommandProtocol;
import dev.mars.protocol.CommandRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/** One bounded UTF-8 JSON exchange per owned connection, with no transport-created threads. */
public final class GossipTransport implements GossipRuntime.ExchangeClient {
    static final class BusyException extends IOException {
        BusyException() { super("Gossip outbound capacity is full"); }
    }
    private final GossipConfig config;
    private final MembershipTable table;
    private final GossipWire wire;
    private final GossipMetrics metrics;
    private final GossipLog log;
    private final GossipBatchSelector requests = new GossipBatchSelector();
    private final Semaphore permits;
    private final Set<Connection> active = new HashSet<>();
    private final long connectNanos;
    private final long idleNanos;
    private final long exchangeNanos;
    private boolean closed;

    public GossipTransport(GossipConfig config, MembershipTable table) {
        this(config, table, Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(15));
    }

    public GossipTransport(GossipConfig config, MembershipTable table, Duration connectTimeout,
                           Duration readIdleTimeout, Duration exchangeTimeout) {
        this(config, table, new GossipMetrics(), connectTimeout, readIdleTimeout, exchangeTimeout);
    }

    GossipTransport(GossipConfig config, MembershipTable table, GossipMetrics metrics) {
        this(config, table, metrics, Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(15));
    }

    private GossipTransport(GossipConfig config, MembershipTable table, GossipMetrics metrics,
                            Duration connectTimeout, Duration readIdleTimeout, Duration exchangeTimeout) {
        this.config = java.util.Objects.requireNonNull(config);
        this.table = java.util.Objects.requireNonNull(table);
        this.metrics = metrics;
        log = metrics.log(config, table.snapshot().self());
        wire = new GossipWire(config);
        permits = new Semaphore(config.maxInflight());
        connectNanos = positiveNanos(connectTimeout);
        idleNanos = positiveNanos(readIdleTimeout);
        exchangeNanos = positiveNanos(exchangeTimeout);
    }

    public GossipWire.Exchange exchange(GossipConfig.Seed peer) throws IOException {
        synchronized (this) { if (closed) { throw new IOException("Gossip transport is closed"); } }
        if (!permits.tryAcquire()) { throw new BusyException(); }
        UUID messageId = null;
        try {
            var snapshot = table.snapshot();
            if (peer.serverId().equals(snapshot.self().key().serverId())
                    && peer.port() == snapshot.self().port() && peer.host().equals(snapshot.self().host())) {
                throw new IOException("Cannot exchange with the local endpoint");
            }
            var members = requests.select(snapshot, config.maxBatchMembers(), batch ->
                    wire.requestBytes(wire.parameters(snapshot.self(), batch)).length <= config.maxExchangeBytes());
            messageId = UUID.randomUUID();
            byte[] payload = GossipWire.bytes(new CommandRequest(1, messageId, "gossip", wire.parameters(snapshot.self(), members)));
            if (payload.length > config.maxExchangeBytes()) { throw new IOException("Outgoing exchange exceeds byte budget"); }
            String response;
            try (var connection = new Connection()) {
                synchronized (this) {
                    if (closed) { throw new IOException("Gossip transport is closed"); }
                    active.add(connection);
                }
                try {
                    // DNS resolution is a platform call; it is not covered by socket cancellation/deadlines.
                    var address = new InetSocketAddress(peer.host(), peer.port());
                    long started = System.nanoTime();
                    long deadline = started + exchangeNanos;
                    connection.channel.configureBlocking(false);
                    if (!connection.channel.connect(address)) {
                        long connectDeadline = started + Math.min(connectNanos, exchangeNanos);
                        while (!connection.channel.finishConnect()) { await(connection, SelectionKey.OP_CONNECT, connectDeadline); }
                    }
                    var output = ByteBuffer.allocate(payload.length + 1).put(payload).put((byte) '\n');
                    output.flip();
                    while (output.hasRemaining()) {
                        checkDeadline(deadline);
                        if (connection.channel.write(output) == 0) { await(connection, SelectionKey.OP_WRITE, deadline); }
                    }
                    response = readLine(connection, deadline);
                } finally { synchronized (this) { active.remove(connection); } }
            }
            var reply = CommandProtocol.parseReply(response);
            if (!messageId.equals(reply.messageId())) { throw new IOException("Gossip message ID mismatch"); }
            if (!peer.serverId().equals(reply.serverId())) { throw new IOException("Gossip server ID mismatch"); }
            if (reply.error() != null) { throw new CommandExecutionException(reply); }
            var result = wire.decode(reply.result(), false);
            if (!reply.serverId().equals(result.sender().key().serverId())) {
                throw new IOException("Outer serverId disagrees with gossip sender");
            }
            wire.requireForwardable(table.snapshot().self(), result.records());
            // Closing cancels uncommitted work as well as sockets. No merge may start after close returns.
            synchronized (this) {
                if (closed) { throw new IOException("Gossip transport is closed"); }
                table.mergeDirect(result.sender(), result.members());
                metrics.success(true);
            }
            var merged = table.snapshot();
            if (!merged.identityConflicts().isEmpty()) {
                log.identity("outbound", peer, messageId, "Reachable duplicate server IDs: " + merged.identityConflicts());
            }
            return result;
        } catch (MembershipException e) {
            metrics.outboundFailures.incrementAndGet();
            if (e.reason() == MembershipException.Reason.CONFLICT) { log.identity("outbound", peer, messageId, e.getMessage()); }
            throw new IOException("Gossip " + e.reason() + ": " + e.getMessage(), e);
        } catch (CancelledKeyException | ClosedSelectorException | UnresolvedAddressException e) {
            metrics.outboundFailures.incrementAndGet();
            throw new IOException("Gossip connection closed or endpoint unresolved", e);
        } catch (IOException e) {
            metrics.outboundFailures.incrementAndGet();
            if (GossipRuntime.classify(e) == GossipMetrics.Failure.IDENTITY) { log.identity("outbound", peer, messageId, e.getMessage()); }
            throw e;
        } finally { permits.release(); }
    }

    private String readLine(Connection connection, long deadline) throws IOException {
        var bytes = new ByteArrayOutputStream(Math.min(4096, config.maxExchangeBytes()));
        var input = ByteBuffer.allocate(4096);
        long idleDeadline = System.nanoTime() + idleNanos;
        while (true) {
            checkDeadline(deadline);
            checkDeadline(idleDeadline);
            input.clear();
            int count = connection.channel.read(input);
            if (count < 0) {
                if (bytes.size() == 0) { throw new IOException("Peer closed without a reply"); }
                return decodeUtf8(bytes);
            }
            if (count == 0) {
                long now = System.nanoTime();
                await(connection, SelectionKey.OP_READ,
                        deadline - now < idleDeadline - now ? deadline : idleDeadline);
                continue;
            }
            idleDeadline = System.nanoTime() + idleNanos;
            input.flip();
            while (input.hasRemaining()) {
                byte value = input.get();
                if (value == '\n' || value == '\r') { return decodeUtf8(bytes); }
                if (bytes.size() == config.maxExchangeBytes()) {
                    throw new IOException("Gossip reply exceeds max.exchange.bytes before parsing");
                }
                bytes.write(value);
            }
        }
    }

    private static String decodeUtf8(ByteArrayOutputStream bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
    }

    private static void await(Connection connection, int operation, long deadline) throws IOException {
        connection.channel.register(connection.selector, operation);
        while (true) {
            checkDeadline(deadline);
            long millis = Math.max(1, (deadline - System.nanoTime()) / 1_000_000);
            int ready = connection.selector.select(millis);
            connection.selector.selectedKeys().clear();
            if (ready > 0) { return; }
        }
    }

    private static void checkDeadline(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted()) { throw new InterruptedIOException("Gossip exchange interrupted"); }
        if (deadline - System.nanoTime() <= 0) { throw new SocketTimeoutException("Gossip I/O deadline exceeded"); }
    }

    private static long positiveNanos(Duration timeout) {
        long nanos = timeout.toNanos();
        if (nanos <= 0) { throw new IllegalArgumentException("Timeout must be positive"); }
        return nanos;
    }

    public int inflight() { return config.maxInflight() - permits.availablePermits(); }

    /** Close owned I/O resources synchronously; callers own their threads, and DNS may still be running. */
    @Override public void close() throws IOException {
        List<Connection> connections;
        synchronized (this) {
            if (closed) { return; }
            closed = true;
            connections = List.copyOf(active);
        }
        IOException failure = null;
        for (Connection connection : connections) {
            try { connection.close(); }
            catch (IOException e) { if (failure == null) { failure = e; } else { failure.addSuppressed(e); } }
        }
        if (failure != null) { throw failure; }
    }

    private static final class Connection implements AutoCloseable {
        final Selector selector;
        final SocketChannel channel;
        Connection() throws IOException {
            selector = Selector.open();
            try { channel = SocketChannel.open(); }
            catch (IOException | RuntimeException e) { selector.close(); throw e; }
        }
        @Override public void close() throws IOException {
            try { channel.close(); }
            finally { selector.close(); }
        }
    }
}
