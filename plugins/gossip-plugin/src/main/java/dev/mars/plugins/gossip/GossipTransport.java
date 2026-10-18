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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Bounded UTF-8 JSON exchanges over connections to peers, kept open between exchanges so that gossip does not open and
 * close a TCP connection every round. Its only threads are watchdogs that each exchange joins.
 */
public final class GossipTransport implements GossipRuntime.ExchangeClient {
    static final class BusyException extends IOException {
        BusyException() { super("Gossip outbound capacity is full"); }
    }
    /** A socket error while connecting: the only failure that moves on to the host's next address. */
    private static final class ConnectFailure extends IOException {
        ConnectFailure(SocketException cause) { super(cause); }
        SocketException socketError() { return (SocketException) getCause(); }
    }
    /** How often the watchdog checks whether a caller on a platform thread has been interrupted. */
    private static final long INTERRUPT_CHECK_MILLIS = 50;
    private static final String OUTBOUND = "outbound"; // The direction identity log lines name.
    /** Kept-open connections held per peer; one is enough for gossip's one exchange at a time, two absorb overlap. */
    private static final int MAX_IDLE_PER_PEER = 2;
    /**
     * Shorter than the host's 5 minute idle limit, so a pooled connection is normally still open when it is reused, and
     * longer than the gap between two exchanges with the same peer: a server picks about two of its peers per round, so in
     * a swarm of a hundred it meets a given peer only about once a minute.
     */
    private static final long IDLE_LIMIT_NANOS = TimeUnit.MINUTES.toNanos(4);
    /** The host closes a kept-open connection after 1,000 requests; stop reusing one a little before that. */
    private static final int MAX_USES = 900;
    private final GossipConfig config;
    private final MembershipTable table;
    private final GossipWire wire;
    private final GossipMetrics metrics;
    private final GossipLog log;
    private final GossipBatchSelector requests = new GossipBatchSelector();
    private final Semaphore permits;
    private final Set<Socket> active = new HashSet<>();
    /** Kept-open connections waiting for the next exchange with a peer; guarded by this transport. */
    private final Map<InetSocketAddress, Deque<PeerConnection>> idle = new HashMap<>();
    private final long connectNanos;
    private final long idleNanos;
    private final long exchangeNanos;
    private final Supplier<Socket> sockets;
    private boolean closed;

    /** For callers that own a standalone table, as the design document describes. */
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

    GossipTransport(GossipConfig config, MembershipTable table, GossipMetrics metrics,
                    Duration connectTimeout, Duration readIdleTimeout, Duration exchangeTimeout) {
        this(config, table, metrics, connectTimeout, readIdleTimeout, exchangeTimeout, Socket::new);
    }

    /** Tests replace {@code sockets} to stall a write, which a real loopback socket cannot be relied on to do. */
    GossipTransport(GossipConfig config, MembershipTable table, GossipMetrics metrics, Duration connectTimeout,
                    Duration readIdleTimeout, Duration exchangeTimeout, Supplier<Socket> sockets) {
        this.config = java.util.Objects.requireNonNull(config);
        this.table = java.util.Objects.requireNonNull(table);
        this.sockets = java.util.Objects.requireNonNull(sockets);
        this.metrics = metrics;
        log = metrics.log(config, table.self());
        wire = new GossipWire(config);
        permits = new Semaphore(config.maxInflight());
        connectNanos = positiveNanos(connectTimeout);
        idleNanos = positiveNanos(readIdleTimeout);
        exchangeNanos = positiveNanos(exchangeTimeout);
    }

    public GossipWire.ExchangeReply exchange(GossipConfig.Seed peer) throws IOException {
        return exchange(List.of(peer));
    }

    /**
     * Exchanges with one peer whose host has several resolved addresses, tried in order. Only a socket error while
     * connecting moves on to the next address; timeouts, protocol and identity failures end the exchange.
     */
    @Override public GossipWire.ExchangeReply exchange(List<GossipConfig.Seed> endpoints) throws IOException {
        var peer = endpoints.getFirst();
        if (endpoints.stream()
                .anyMatch(endpoint -> !endpoint.serverId().equals(peer.serverId()) || endpoint.port() != peer.port())) {
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
            if (GossipFailure.of(e) == GossipFailure.IDENTITY) {
                log.identity(OUTBOUND, peer, conversation.messageId, e.getMessage());
            }
            throw e;
        } finally { permits.release(); }
    }

    /** One outbound exchange with a peer: a request, and its reply checked and merged, all within one deadline. */
    private final class Conversation {
        private final GossipConfig.Seed peer;
        private final List<GossipConfig.Seed> endpoints;
        private UUID messageId; // The request's, for logging a failure.

        Conversation(GossipConfig.Seed peer, List<GossipConfig.Seed> endpoints) {
            this.peer = peer;
            this.endpoints = endpoints;
        }

        GossipWire.ExchangeReply run() throws IOException {
            var self = table.self();
            if (PeerSelector.isSelf(self, peer)) { throw new IOException("Cannot exchange with the local endpoint"); }
            var members = requests.select(table.peers(), config.maxBatchMembers(), wire.requestMemberBudget(self));
            // Resolve once. Every address fallback shares one deadline, which starts after resolving.
            var candidates = endpoints.stream()
                    .map(endpoint -> new InetSocketAddress(endpoint.host(), endpoint.port())).toList();
            long deadline = System.nanoTime() + exchangeNanos;
            var result = accepted(send(new GossipWire.ExchangeRequest(self, members), candidates, deadline));
            merge(result);
            var conflicts = table.identityConflicts();
            if (!conflicts.isEmpty()) {
                log.identity(OUTBOUND, peer, messageId, "Reachable duplicate server IDs: " + conflicts);
            }
            return result;
        }

        /**
         * Sends the request and returns its reply, once the reply is correlated and comes from the peer it was meant
         * for.
         */
        private CommandReply send(GossipWire.ExchangeRequest request, List<InetSocketAddress> candidates,
                long deadline) throws IOException {
            messageId = UUID.randomUUID();
            byte[] payload = GossipWire.bytes(wire.envelope(messageId, wire.toWire(request)));
            // The batch was chosen to fit, so this only guards against an error in that arithmetic.
            if (payload.length > config.maxExchangeBytes()) {
                throw new IOException("Outgoing exchange exceeds byte budget");
            }
            var line = exchangeOverFirstReachable(candidates, payload, deadline);
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

        /** The exchange a reply carries, once the reply is a success from the sender it names. */
        private GossipWire.ExchangeReply accepted(CommandReply reply) throws IOException {
            if (reply.error() != null) { throw new CommandExecutionException(reply); }
            var result = wire.decodeReply(reply.result());
            if (!reply.serverId().equals(result.sender().key().serverId())) {
                throw new GossipExchangeException(GossipFailure.IDENTITY,
                        "Outer serverId disagrees with gossip sender");
            }
            // Every record must be forwardable before any is merged, so that one cannot break later replies.
            wire.requireForwardable(table.self(), result.records());
            return result;
        }

        /** Closing cancels uncommitted work as well as sockets, so no merge may start after close returns. */
        private void merge(GossipWire.ExchangeReply result) throws IOException {
            synchronized (GossipTransport.this) {
                if (closed) { throw new IOException("Gossip transport is closed"); }
                table.mergeDirect(result.sender(), result.members());
                metrics.success(true);
            }
        }
    }

    private record Reply(String text, InetSocketAddress address) { }

    /** Tries each address in order; if none connects, throws the first connection error with the rest suppressed. */
    private Reply exchangeOverFirstReachable(List<InetSocketAddress> candidates, byte[] payload,
            long deadline) throws IOException {
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
     * One exchange over a connection to the peer: a kept-open one from the pool when there is one, otherwise a new one.
     * A pooled connection may have been closed by the peer since it was last used (it idled out, or the peer
     * restarted); that shows up as the connection failing before any reply byte, and the exchange is then sent again
     * over a new connection. Sending again is safe because merging an exchange is idempotent.
     */
    private String exchangeOver(InetSocketAddress address, byte[] payload, long deadline) throws IOException {
        checkDeadline(deadline);
        var pooled = takeIdle(address);
        if (pooled != null) {
            try { return converse(pooled, address, payload, deadline); }
            catch (StaleConnection stale) { metrics.staleConnections.incrementAndGet(); }
            checkDeadline(deadline);
        }
        return converse(new PeerConnection(sockets.get()), address, payload, deadline);
    }

    /**
     * One blocking request and reply. Connects and reads have socket timeouts; a blocking write has none, so a
     * watchdog closes the socket once the overall deadline passes. An interrupt also ends the exchange: on a virtual
     * thread it closes the socket itself, and for a platform thread, which an interrupt does not wake from blocking
     * socket I/O, the watchdog closes the socket within {@value #INTERRUPT_CHECK_MILLIS} ms. The exchange waits for its
     * watchdog to end before returning, so no thread outlives it. A connection that carried a clean reply goes back to
     * the pool; any other is closed, so a late or partial reply can never be read as the answer to a later request.
     */
    private String converse(PeerConnection peer, InetSocketAddress address, byte[] payload, long deadline) throws IOException {
        var socket = peer.socket;
        boolean reused = socket.isConnected();
        boolean clean = false;
        try {
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
                if (reused) {
                    // Nothing should be waiting on an idle connection. Data that is means the peer sent something we did
                    // not ask for, and it would be read as the reply to this request.
                    var idleInput = socket.getInputStream();
                    if (peer.pendingLineFeed && idleInput.available() > 0) {
                        // The line feed that completes the previous reply's carriage return has arrived: expected.
                        if (idleInput.read() == '\n') { peer.pendingLineFeed = false; }
                        else { throw new StaleConnection(new IOException("Unsolicited data on an idle connection")); }
                    }
                    if (idleInput.available() > 0) {
                        throw new StaleConnection(new IOException("Unsolicited data on an idle connection"));
                    }
                    metrics.connectionsReused.incrementAndGet();
                } else {
                    try { socket.connect(address, millis(Math.min(connectNanos, deadline - System.nanoTime()))); }
                    catch (SocketException refusedOrUnreachable) {
                        checkDeadline(deadline);
                        throw new ConnectFailure(refusedOrUnreachable);
                    }
                    metrics.connectionsOpened.incrementAndGet();
                }
                var output = socket.getOutputStream();
                output.write(payload);
                output.write('\n');
                output.flush();
                String line = readLine(peer, deadline);
                peer.uses++;
                clean = true;
                return line;
            } catch (SocketException closedUnderneath) {
                // Closed by the watchdog, by an interrupt, or by close(): report which, rather than a bare socket
                // error.
                checkDeadline(deadline);
                synchronized (this) {
                    if (closed) { throw new IOException("Gossip transport is closed", closedUnderneath); }
                }
                // Otherwise the peer reset or closed the connection; on a reused one that only means it went stale.
                if (reused) { throw new StaleConnection(closedUnderneath); }
                throw closedUnderneath;
            } catch (PeerClosedException closedBeforeReply) {
                if (reused) { throw new StaleConnection(closedBeforeReply); }
                throw closedBeforeReply;
            } finally {
                watchdog.interrupt();
                joinUninterruptibly(watchdog);
                synchronized (this) { active.remove(socket); }
            }
        } finally {
            if (clean) { release(address, peer); } else { closeQuietly(socket); }
        }
    }

    /** A connection to a peer, with what is needed to reuse it for another exchange. */
    private static final class PeerConnection {
        final Socket socket;
        long idleSince;
        int uses;
        /** Set when the connection cannot safely carry another exchange, whatever its age or use count. */
        boolean unusable;
        /** The last reply ended with a carriage return whose line feed has not been read yet. */
        boolean pendingLineFeed;

        PeerConnection(Socket socket) { this.socket = socket; }
    }

    /** The peer closed a pooled connection before sending any reply, so the exchange never reached it. */
    private static final class StaleConnection extends IOException {
        StaleConnection(IOException cause) { super(cause); }
    }

    private static final class PeerClosedException extends IOException {
        PeerClosedException() { super("Peer closed without a reply"); }
    }

    private synchronized PeerConnection takeIdle(InetSocketAddress address) {
        pruneIdle();
        var queue = idle.get(address);
        if (queue == null) { return null; }
        var peer = queue.pollLast();
        if (queue.isEmpty()) { idle.remove(address); }
        return peer;
    }

    /** Keeps a connection that carried a clean reply for the next exchange with the same peer. */
    private synchronized void release(InetSocketAddress address, PeerConnection peer) {
        if (closed || peer.unusable || peer.uses >= MAX_USES || peer.socket.isClosed()) { closeQuietly(peer.socket); return; }
        peer.idleSince = System.nanoTime();
        var queue = idle.computeIfAbsent(address, key -> new ArrayDeque<>());
        queue.addLast(peer);
        while (queue.size() > MAX_IDLE_PER_PEER) { closeQuietly(queue.pollFirst().socket); }
        pruneIdle();
    }

    /** Closes pooled connections that have waited longer than the host keeps them; callers hold the lock. */
    private void pruneIdle() {
        long now = System.nanoTime();
        for (var queues = idle.values().iterator(); queues.hasNext();) {
            var queue = queues.next();
            while (!queue.isEmpty() && now - queue.peekFirst().idleSince > IDLE_LIMIT_NANOS) {
                closeQuietly(queue.pollFirst().socket);
            }
            if (queue.isEmpty()) { queues.remove(); }
        }
    }

    private static void closeQuietly(Socket socket) {
        try { socket.close(); } catch (IOException alreadyClosed) { /* Nothing more to release. */ }
    }

    /** Reads one reply line into a bounded buffer, scanning only newly read bytes for the terminator. */
    private String readLine(PeerConnection peer, long deadline) throws IOException {
        var socket = peer.socket;
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
                if (length == 0) { throw new PeerClosedException(); }
                peer.unusable = true; // The peer closed after the reply, so the connection cannot be reused.
                return decodeUtf8(bytes, length);
            }
            if (peer.pendingLineFeed && length == 0) {
                // The previous reply ended with a carriage return; its line feed is not part of this reply.
                peer.pendingLineFeed = false;
                if (bytes[0] == '\n') {
                    System.arraycopy(bytes, 1, bytes, 0, count - 1);
                    if (--count == 0) { continue; }
                }
            }
            length += count;
            for (; scanned < length; scanned++) {
                byte b = bytes[scanned];
                if (b != '\n' && b != '\r') { continue; }
                String line = decodeUtf8(bytes, scanned);
                int end = scanned + 1;
                if (b == '\r') {
                    if (end < length && bytes[end] == '\n') { end++; }
                    else if (end == length) { peer.pendingLineFeed = true; }
                }
                // Anything after the reply means the stream is not in step with the requests; do not reuse it.
                if (end < length) { peer.unusable = true; }
                return line;
            }
            if (length == bytes.length) {
                throw new GossipExchangeException(GossipFailure.PROTOCOL,
                        "Gossip reply exceeds max.exchange.bytes before parsing");
            }
        }
    }

    /** Socket timeouts are whole milliseconds, and zero would mean no timeout at all. */
    private static int millis(long nanos) {
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toMillis(nanos)));
    }

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
        List<Socket> idleSockets = new ArrayList<>();
        synchronized (this) {
            if (closed) { return; }
            closed = true;
            var all = new ArrayList<>(active);
            idle.values().forEach(queue -> queue.forEach(peer -> idleSockets.add(peer.socket)));
            all.addAll(idleSockets);
            idle.clear();
            sockets = List.copyOf(all);
        }
        // An idle connection has nothing in flight, so it is closed with a reset: a closing handshake would leave the port
        // in TIME_WAIT for two minutes, and a server with a hundred peers would then leave a hundred of them every time
        // it stops. The host treats a reset between requests as a normal end.
        for (Socket socket : idleSockets) {
            try { socket.setSoLinger(true, 0); } catch (IOException cannotReset) { /* an ordinary close then */ }
        }
        IOException failure = null;
        for (Socket socket : sockets) {
            try { socket.close(); }
            catch (IOException e) { if (failure == null) { failure = e; } else { failure.addSuppressed(e); } }
        }
        if (failure != null) { throw failure; }
    }
}
