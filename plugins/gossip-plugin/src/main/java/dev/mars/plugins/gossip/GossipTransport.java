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
import dev.mars.protocol.CommandProtocol;
import dev.mars.protocol.CommandRequest;
import dev.mars.protocol.CommandError;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** One bounded UTF-8 JSON exchange per owned connection, with no transport-created threads. */
public final class GossipTransport implements GossipRuntime.ExchangeClient {
    static final class BusyException extends IOException {
        BusyException() { super("Gossip outbound capacity is full"); }
    }
    /** A socket error while connecting: the only failure that moves on to the host's next address. */
    private static final class ConnectFailure extends IOException {
        ConnectFailure(SocketException cause) { super(cause); }
        SocketException socketError() { return (SocketException) getCause(); }
    }
    /**
     * Version-1 peers predate structured error codes, so these exact rejections are the only way to recognise one.
     * Their wording is fixed by the deployed version-1 code; version-2 peers accept both versions and never send them.
     */
    private static final Set<String> VERSION_1_REJECTIONS = Set.of(
            "INVALID_RECORD: gossipVersion must be between 1 and 1", "INVALID_RECORD: Unsupported gossipVersion");
    /** How long a version-1 peer is sent version 1 directly before version 2 is tried again, in case it was upgraded. */
    static final Duration VERSION_1_RECHECK = Duration.ofMinutes(10);
    private final GossipConfig config;
    private final MembershipTable table;
    private final GossipWire wire;
    private final GossipMetrics metrics;
    private final GossipLog log;
    private final GossipBatchSelector requests = new GossipBatchSelector();
    private final Semaphore permits;
    private final Set<Socket> active = new HashSet<>();
    private final long connectNanos;
    private final long idleNanos;
    private final long exchangeNanos;
    private final LongSupplier clock;
    // serverId:port of peers that completed a version-1 exchange after rejecting version 2, to when that was learned.
    private final LinkedHashMap<String, Long> version1Peers = new LinkedHashMap<>();
    private boolean closed;

    public GossipTransport(GossipConfig config, MembershipTable table) {
        this(config, table, Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(15));
    }

    public GossipTransport(GossipConfig config, MembershipTable table, Duration connectTimeout,
                           Duration readIdleTimeout, Duration exchangeTimeout) {
        this(config, table, new GossipMetrics(), connectTimeout, readIdleTimeout, exchangeTimeout, System::nanoTime);
    }

    GossipTransport(GossipConfig config, MembershipTable table, GossipMetrics metrics) {
        this(config, table, metrics, System::nanoTime);
    }

    /** The clock times only the version-1 memory; socket deadlines always use System.nanoTime. */
    GossipTransport(GossipConfig config, MembershipTable table, GossipMetrics metrics, LongSupplier clock) {
        this(config, table, metrics, Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(15), clock);
    }

    private GossipTransport(GossipConfig config, MembershipTable table, GossipMetrics metrics,
                            Duration connectTimeout, Duration readIdleTimeout, Duration exchangeTimeout, LongSupplier clock) {
        this.config = java.util.Objects.requireNonNull(config);
        this.table = java.util.Objects.requireNonNull(table);
        this.clock = java.util.Objects.requireNonNull(clock);
        this.metrics = metrics;
        log = metrics.log(config, table.self());
        wire = new GossipWire(config);
        permits = new Semaphore(config.maxInflight());
        connectNanos = positiveNanos(connectTimeout);
        idleNanos = positiveNanos(readIdleTimeout);
        exchangeNanos = positiveNanos(exchangeTimeout);
    }

    public GossipWire.Exchange exchange(GossipConfig.Seed peer) throws IOException { return exchange(List.of(peer)); }

    /**
     * Exchanges with one peer whose host has several resolved addresses, tried in order. Only a socket error while
     * connecting moves on to the next address; timeouts, protocol and identity failures end the exchange.
     */
    @Override public GossipWire.Exchange exchange(List<GossipConfig.Seed> endpoints) throws IOException {
        var peer = endpoints.getFirst();
        if (endpoints.stream().anyMatch(endpoint -> !endpoint.serverId().equals(peer.serverId()) || endpoint.port() != peer.port())) {
            throw new IllegalArgumentException("Fallback addresses must share the peer's server ID and port");
        }
        synchronized (this) { if (closed) { throw new IOException("Gossip transport is closed"); } }
        if (!permits.tryAcquire()) { throw new BusyException(); }
        UUID messageId = null;
        try {
            var self = table.self();
            if (PeerSelector.isSelf(self, peer)) {
                throw new IOException("Cannot exchange with the local endpoint");
            }
            var members = requests.select(table.peers(), config.maxBatchMembers(), wire.requestMemberBudget(self));
            // Resolve once; both protocol attempts and all address fallbacks share one permit and one deadline.
            var candidates = endpoints.stream().map(endpoint -> new InetSocketAddress(endpoint.host(), endpoint.port())).toList();
            long deadline = System.nanoTime() + exchangeNanos;
            String peerKey = peer.serverId() + ":" + peer.port();
            int version = knownVersion1(peerKey) ? 1 : 2;
            boolean downgraded = false;
            while (true) {
                messageId = UUID.randomUUID();
                byte[] payload = GossipWire.bytes(new CommandRequest(1, messageId, "gossip", wire.parameters(self, members, version)));
                if (payload.length > config.maxExchangeBytes()) { throw new IOException("Outgoing exchange exceeds byte budget"); }
                var line = exchangeBytes(candidates, payload, deadline);
                candidates = List.of(line.address()); // A version retry reuses the address that answered.
                var reply = CommandProtocol.parseReply(line.text());
                if (!messageId.equals(reply.messageId())) { throw new IOException("Gossip message ID mismatch"); }
                if (!peer.serverId().equals(reply.serverId())) { throw new IOException("Gossip server ID mismatch"); }
                if (reply.error() != null) {
                    if (version == 2 && reply.error().code() == CommandError.Code.INVALID_PARAMETERS
                            && VERSION_1_REJECTIONS.contains(reply.error().message())) {
                        version = 1;
                        downgraded = true;
                        continue;
                    }
                    throw new CommandExecutionException(reply);
                }
                var result = wire.decode(reply.result(), false);
                if (result.gossipVersion() != version) { throw GossipWire.invalid("Reply gossipVersion does not match request"); }
                if (!reply.serverId().equals(result.sender().key().serverId())) {
                    throw new IOException("Outer serverId disagrees with gossip sender");
                }
                wire.requireForwardable(table.self(), result.records());
                // Closing cancels uncommitted work as well as sockets. No merge may start after close returns.
                synchronized (this) {
                    if (closed) { throw new IOException("Gossip transport is closed"); }
                    table.mergeDirect(result.sender(), result.members());
                    metrics.success(true);
                }
                // Remember only after a correlated, identity-checked version-1 exchange has fully succeeded.
                if (downgraded) { rememberVersion1(peerKey); }
                var conflicts = table.identityConflicts();
                if (!conflicts.isEmpty()) {
                    log.identity("outbound", peer, messageId, "Reachable duplicate server IDs: " + conflicts);
                }
                return result;
            }
        } catch (MembershipException e) {
            metrics.outboundFailures.incrementAndGet();
            if (e.reason() == MembershipException.Reason.CONFLICT) { log.identity("outbound", peer, messageId, e.getMessage()); }
            throw new IOException("Gossip " + e.reason() + ": " + e.getMessage(), e);
        } catch (IOException e) {
            metrics.outboundFailures.incrementAndGet();
            if (GossipRuntime.classify(e) == GossipMetrics.Failure.IDENTITY) { log.identity("outbound", peer, messageId, e.getMessage()); }
            throw e;
        } finally { permits.release(); }
    }

    private boolean knownVersion1(String peerKey) {
        synchronized (version1Peers) {
            Long learned = version1Peers.get(peerKey);
            if (learned == null) { return false; }
            if (clock.getAsLong() - learned >= VERSION_1_RECHECK.toNanos()) { version1Peers.remove(peerKey); return false; }
            return true;
        }
    }

    /** Bounded like the peer selector's retry state: at most one entry per member or seed. */
    private void rememberVersion1(String peerKey) {
        synchronized (version1Peers) {
            version1Peers.remove(peerKey);
            version1Peers.put(peerKey, clock.getAsLong());
            while (version1Peers.size() > config.maxMembers() + config.seeds().size()) { version1Peers.pollFirstEntry(); }
        }
    }

    int version1PeerCount() { synchronized (version1Peers) { return version1Peers.size(); } }

    private record Reply(String text, InetSocketAddress address) { }

    /** Tries each address in order; if none connects, throws the first connection error with the rest suppressed. */
    private Reply exchangeBytes(List<InetSocketAddress> candidates, byte[] payload, long deadline) throws IOException {
        SocketException unreachable = null;
        for (var address : candidates) {
            try { return new Reply(exchangeBytes(address, payload, deadline), address); }
            catch (ConnectFailure failure) {
                if (unreachable == null) { unreachable = failure.socketError(); }
                else { unreachable.addSuppressed(failure.socketError()); }
            }
        }
        throw unreachable;
    }

    /**
     * One blocking connection. Connects and reads have socket timeouts; a blocking write has none, so a watchdog
     * closes the socket once the overall deadline passes. On a virtual thread, an interrupt also closes it.
     */
    private String exchangeBytes(InetSocketAddress address, byte[] payload, long deadline) throws IOException {
        checkDeadline(deadline);
        try (var socket = new Socket()) {
            synchronized (this) {
                if (closed) { throw new IOException("Gossip transport is closed"); }
                active.add(socket);
            }
            var watchdog = Thread.ofVirtual().start(() -> {
                try { TimeUnit.NANOSECONDS.sleep(deadline - System.nanoTime()); socket.close(); }
                catch (InterruptedException | IOException finished) { /* The exchange ended first. */ }
            });
            try {
                try { socket.connect(address, millis(Math.min(connectNanos, deadline - System.nanoTime()))); }
                catch (SocketException refusedOrUnreachable) {
                    checkDeadline(deadline);
                    throw new ConnectFailure(refusedOrUnreachable);
                }
                var output = socket.getOutputStream();
                output.write(payload);
                output.write('\n');
                output.flush();
                return readLine(socket, deadline);
            } catch (SocketException closedUnderneath) {
                // Closed by the watchdog, by an interrupt, or by close(): report which, rather than a bare socket error.
                checkDeadline(deadline);
                synchronized (this) { if (closed) { throw new IOException("Gossip transport is closed", closedUnderneath); } }
                throw closedUnderneath;
            } finally {
                watchdog.interrupt();
                synchronized (this) { active.remove(socket); }
            }
        }
    }

    /** Reads one reply line into a bounded buffer, scanning only newly read bytes for the terminator. */
    private String readLine(Socket socket, long deadline) throws IOException {
        // One spare byte tells a full-length line followed by its terminator apart from an oversized line.
        byte[] bytes = new byte[config.maxExchangeBytes() + 1];
        var input = socket.getInputStream();
        int length = 0;
        int scanned = 0;
        while (true) {
            checkDeadline(deadline);
            // Each read waits at most the idle limit, and never past the overall deadline.
            socket.setSoTimeout(millis(Math.min(idleNanos, deadline - System.nanoTime())));
            int count = input.read(bytes, length, bytes.length - length);
            if (count < 0) {
                if (length == 0) { throw new IOException("Peer closed without a reply"); }
                return decodeUtf8(bytes, length);
            }
            length += count;
            for (; scanned < length; scanned++) {
                if (bytes[scanned] == '\n' || bytes[scanned] == '\r') { return decodeUtf8(bytes, scanned); }
            }
            if (length == bytes.length) { throw new IOException("Gossip reply exceeds max.exchange.bytes before parsing"); }
        }
    }

    /** Socket timeouts are whole milliseconds, and zero would mean no timeout at all. */
    private static int millis(long nanos) { return (int) Math.max(1, Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toMillis(nanos))); }

    private static String decodeUtf8(byte[] bytes, int length) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, length)).toString();
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
        List<Socket> sockets;
        synchronized (this) {
            if (closed) { return; }
            closed = true;
            sockets = List.copyOf(active);
        }
        IOException failure = null;
        for (Socket socket : sockets) {
            try { socket.close(); }
            catch (IOException e) { if (failure == null) { failure = e; } else { failure.addSuppressed(e); } }
        }
        if (failure != null) { throw failure; }
    }
}
