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
import dev.mars.protocol.CommandReply;
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
import java.util.function.Supplier;

/** One bounded UTF-8 JSON exchange per owned connection. Its only threads are watchdogs that each exchange joins. */
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
    /** How often the watchdog checks whether a caller on a platform thread has been interrupted. */
    private static final long INTERRUPT_CHECK_MILLIS = 50;
    private static final String OUTBOUND = "outbound"; // The direction identity log lines name.
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
    private final Supplier<Socket> sockets;
    // serverId:port of peers that completed a version-1 exchange after rejecting version 2, to when that was learned.
    private final LinkedHashMap<String, Long> version1Peers = new LinkedHashMap<>();
    private boolean closed;

    /** For callers that own a standalone table, as the design document describes. */
    public GossipTransport(GossipConfig config, MembershipTable table) {
        this(config, table, Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(15));
    }

    public GossipTransport(GossipConfig config, MembershipTable table, Duration connectTimeout,
                           Duration readIdleTimeout, Duration exchangeTimeout) {
        this(config, table, new GossipMetrics(), connectTimeout, readIdleTimeout, exchangeTimeout, System::nanoTime);
    }

    GossipTransport(GossipConfig config, MembershipTable table, GossipMetrics metrics) {
        this(config, table, metrics, Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(15), System::nanoTime);
    }

    /** The clock times only the version-1 memory; socket deadlines always use System.nanoTime. */
    GossipTransport(GossipConfig config, MembershipTable table, GossipMetrics metrics,
                    Duration connectTimeout, Duration readIdleTimeout, Duration exchangeTimeout, LongSupplier clock) {
        this(config, table, metrics, connectTimeout, readIdleTimeout, exchangeTimeout, clock, Socket::new);
    }

    /** Tests replace {@code sockets} to stall a write, which a real loopback socket cannot be relied on to do. */
    GossipTransport(GossipConfig config, MembershipTable table, GossipMetrics metrics, Duration connectTimeout,
                    Duration readIdleTimeout, Duration exchangeTimeout, LongSupplier clock, Supplier<Socket> sockets) {
        this.config = java.util.Objects.requireNonNull(config);
        this.table = java.util.Objects.requireNonNull(table);
        this.clock = java.util.Objects.requireNonNull(clock);
        this.sockets = java.util.Objects.requireNonNull(sockets);
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
        var conversation = new Conversation(peer, endpoints);
        try {
            return conversation.run();
        } catch (MembershipException e) {
            metrics.outboundFailures.incrementAndGet();
            if (e.reason() == MembershipException.Reason.CONFLICT) {
                log.identity(OUTBOUND, peer, conversation.messageId, e.getMessage());
            }
            throw new IOException("Gossip " + e.reason() + ": " + e.getMessage(), e);
        } catch (IOException e) {
            metrics.outboundFailures.incrementAndGet();
            if (GossipFailure.of(e) == GossipFailure.IDENTITY) { log.identity(OUTBOUND, peer, conversation.messageId, e.getMessage()); }
            throw e;
        } finally { permits.release(); }
    }

    /**
     * One outbound exchange with a peer: a request, its reply checked and merged, and at most one retry, at gossip
     * version 1, when the peer rejects version 2. Both attempts share the caller's permit and one deadline.
     */
    private final class Conversation {
        private final GossipConfig.Seed peer;
        private final List<GossipConfig.Seed> endpoints;
        private final String peerKey;
        private List<InetSocketAddress> candidates;
        private long deadline;
        private UUID messageId; // The latest request's, for logging a failure.

        Conversation(GossipConfig.Seed peer, List<GossipConfig.Seed> endpoints) {
            this.peer = peer;
            this.endpoints = endpoints;
            peerKey = peer.serverId() + ":" + peer.port();
        }

        GossipWire.Exchange run() throws IOException {
            var self = table.self();
            if (PeerSelector.isSelf(self, peer)) { throw new IOException("Cannot exchange with the local endpoint"); }
            var members = requests.select(table.peers(), config.maxBatchMembers(), wire.requestMemberBudget(self));
            // Resolve once. Every attempt and address fallback shares one deadline, which starts after resolving.
            candidates = endpoints.stream().map(endpoint -> new InetSocketAddress(endpoint.host(), endpoint.port())).toList();
            deadline = System.nanoTime() + exchangeNanos;
            int version = knownVersion1(peerKey) ? 1 : 2;
            var reply = send(self, members, version);
            boolean downgraded = version == 2 && rejectsVersion2(reply);
            if (downgraded) {
                version = 1;
                reply = send(self, members, version);
            }
            var result = accepted(reply, version);
            merge(result);
            // Remember only after a correlated, identity-checked version-1 exchange has fully succeeded.
            if (downgraded) { rememberVersion1(peerKey); }
            var conflicts = table.identityConflicts();
            if (!conflicts.isEmpty()) { log.identity(OUTBOUND, peer, messageId, "Reachable duplicate server IDs: " + conflicts); }
            return result;
        }

        /** Sends one request and returns its reply, once the reply is correlated and comes from the peer it was meant for. */
        private CommandReply send(MemberRecord self, List<MemberRecord> members, int version) throws IOException {
            messageId = UUID.randomUUID();
            byte[] payload = GossipWire.bytes(new CommandRequest(1, messageId, "gossip", wire.parameters(self, members, version)));
            // The batch was chosen to fit, so this only guards against an error in that arithmetic.
            if (payload.length > config.maxExchangeBytes()) { throw new IOException("Outgoing exchange exceeds byte budget"); }
            var line = exchangeOverFirstReachable(candidates, payload, deadline);
            candidates = List.of(line.address()); // A retry reuses the address that answered.
            CommandReply reply;
            try { reply = CommandProtocol.parseReply(line.text()); }
            catch (IOException unreadable) {
                throw new GossipExchangeException(GossipFailure.PROTOCOL, unreadable.getMessage(), unreadable);
            }
            if (!messageId.equals(reply.messageId())) {
                throw new GossipExchangeException(GossipFailure.IDENTITY, "Gossip message ID mismatch");
            }
            if (!peer.serverId().equals(reply.serverId())) {
                throw new GossipExchangeException(GossipFailure.IDENTITY, "Gossip server ID mismatch");
            }
            return reply;
        }

        /** The exchange a reply carries, once the reply is a success, in the version asked for, from the sender it names. */
        private GossipWire.Exchange accepted(CommandReply reply, int version) throws IOException {
            if (reply.error() != null) { throw new CommandExecutionException(reply); }
            var result = wire.decode(reply.result(), false);
            if (result.gossipVersion() != version) {
                throw MembershipException.invalidRecord(GossipFailure.VERSION, "Reply gossipVersion does not match request");
            }
            if (!reply.serverId().equals(result.sender().key().serverId())) {
                throw new GossipExchangeException(GossipFailure.IDENTITY, "Outer serverId disagrees with gossip sender");
            }
            // Every record must be forwardable before any is merged, so that one cannot break later replies.
            wire.requireForwardable(table.self(), result.records());
            return result;
        }

        /** Closing cancels uncommitted work as well as sockets, so no merge may start after close returns. */
        private void merge(GossipWire.Exchange result) throws IOException {
            synchronized (GossipTransport.this) {
                if (closed) { throw new IOException("Gossip transport is closed"); }
                table.mergeDirect(result.sender(), result.members());
                metrics.success(true);
            }
        }
    }

    /** A version-2 request rejected in the exact words a deployed version-1 peer uses, so it is asked again in version 1. */
    private static boolean rejectsVersion2(CommandReply reply) {
        return reply.error() != null && reply.error().code() == CommandError.Code.INVALID_PARAMETERS
                && VERSION_1_REJECTIONS.contains(reply.error().message());
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
    private Reply exchangeOverFirstReachable(List<InetSocketAddress> candidates, byte[] payload, long deadline) throws IOException {
        SocketException unreachable = null;
        for (var address : candidates) {
            try { return new Reply(exchangeOver(address, payload, deadline), address); }
            catch (ConnectFailure failure) {
                if (unreachable == null) { unreachable = failure.socketError(); }
                else { unreachable.addSuppressed(failure.socketError()); }
            }
        }
        throw unreachable;
    }

    /**
     * One blocking connection. Connects and reads have socket timeouts; a blocking write has none, so a watchdog
     * closes the socket once the overall deadline passes. An interrupt also ends the exchange: on a virtual thread it
     * closes the socket itself, and for a platform thread, which an interrupt does not wake from blocking socket I/O,
     * the watchdog closes the socket within {@value #INTERRUPT_CHECK_MILLIS} ms. The exchange waits for its watchdog
     * to end before returning, so no thread outlives it.
     */
    private String exchangeOver(InetSocketAddress address, byte[] payload, long deadline) throws IOException {
        checkDeadline(deadline);
        try (var socket = sockets.get()) {
            synchronized (this) {
                if (closed) { throw new IOException("Gossip transport is closed"); }
                active.add(socket);
            }
            var caller = Thread.currentThread();
            long check = caller.isVirtual() ? Long.MAX_VALUE : TimeUnit.MILLISECONDS.toNanos(INTERRUPT_CHECK_MILLIS);
            var watchdog = Thread.ofVirtual().start(() -> {
                try {
                    long left = deadline - System.nanoTime();
                    while (left > 0 && !caller.isInterrupted()) {
                        TimeUnit.NANOSECONDS.sleep(Math.min(left, check));
                        left = deadline - System.nanoTime();
                    }
                    socket.close();
                } catch (InterruptedException | IOException finished) { /* The exchange ended first. */ }
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
                joinUninterruptibly(watchdog);
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
            if (length == bytes.length) {
                throw new GossipExchangeException(GossipFailure.PROTOCOL, "Gossip reply exceeds max.exchange.bytes before parsing");
            }
        }
    }

    /** Socket timeouts are whole milliseconds, and zero would mean no timeout at all. */
    private static int millis(long nanos) { return (int) Math.max(1, Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toMillis(nanos))); }

    private static String decodeUtf8(byte[] bytes, int length) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, length)).toString();
    }

    /** Waits for a thread to end even if the caller is interrupted, keeping the caller's interrupt status. */
    private static void joinUninterruptibly(Thread thread) {
        boolean interrupted = Thread.interrupted();
        while (thread.isAlive()) {
            try { thread.join(); }
            catch (InterruptedException e) { interrupted = true; }
        }
        if (interrupted) { Thread.currentThread().interrupt(); }
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
