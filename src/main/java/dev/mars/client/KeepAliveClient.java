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
package dev.mars.client;

import dev.mars.protocol.CommandError;
import dev.mars.protocol.CommandProtocol;
import dev.mars.protocol.CommandReply;
import dev.mars.protocol.CommandRequest;
import dev.mars.protocol.Json;

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
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Commands to other servers over connections kept open between requests, so that a plugin talking to its peers does not
 * open and close a TCP connection for every one. A server that closes a pooled connection (it idled out, or restarted)
 * is found out when that connection fails before any reply byte, and the request is then sent again over a new one; so
 * only send requests that are safe to repeat. A host too old to know the keep-alive field is remembered and given one
 * request per connection. Each request, with any retry, ends by its deadline; its only thread is a watchdog it joins.
 */
public final class KeepAliveClient implements AutoCloseable {
    /** How often the watchdog checks whether a caller on a platform thread has been interrupted. */
    private static final long INTERRUPT_CHECK_MILLIS = 50;
    /** A reply is read into a buffer that starts this size and grows, up to the reply limit, only as a reply needs it. */
    private static final int FIRST_BUFFER_BYTES = 8192;

    /**
     * How this client talks to its peers. {@code name} begins its error messages, such as "Gossip I/O deadline
     * exceeded".
     */
    public record Settings(String name, int maxReplyBytes, Duration connectTimeout, Duration readIdleTimeout,
                           Duration requestTimeout, int maxIdlePerPeer, Duration idleLimit, int maxUses) {
        /** Kept-open connections held per peer; one is enough for one request at a time, two absorb overlap. */
        public static final int MAX_IDLE_PER_PEER = 2;
        /**
         * Shorter than the host's 5 minute idle limit, so a pooled connection is normally still open when it is reused,
         * and longer than the gap between two exchanges with the same peer: a gossip server picks about two of its peers
         * per round, so in a swarm of a hundred it meets a given peer only about once a minute.
         */
        public static final Duration IDLE_LIMIT = Duration.ofMinutes(4);
        /** The host closes a kept-open connection after 1,000 requests; stop reusing one a little before that. */
        public static final int MAX_USES = 900;

        public Settings {
            if (name == null || name.isBlank()) { throw new IllegalArgumentException("A client name is required"); }
            if (maxReplyBytes <= 0) { throw new IllegalArgumentException("Reply limit must be positive"); }
            for (var timeout : List.of(connectTimeout, readIdleTimeout, requestTimeout, idleLimit)) {
                if (timeout.toNanos() <= 0) { throw new IllegalArgumentException("Timeout must be positive"); }
            }
            if (maxIdlePerPeer < 0) { throw new IllegalArgumentException("Idle connections per peer must not be negative"); }
            if (maxUses <= 0) { throw new IllegalArgumentException("Uses per connection must be positive"); }
        }

        /** The given limits, with the shipped pooling rules. */
        public static Settings of(String name, int maxReplyBytes, Duration connectTimeout, Duration readIdleTimeout,
                                  Duration requestTimeout) {
            return new Settings(name, maxReplyBytes, connectTimeout, readIdleTimeout, requestTimeout, MAX_IDLE_PER_PEER,
                    IDLE_LIMIT, MAX_USES);
        }
    }

    /** Told of each connection opened, each reuse of a pooled one, and each pooled one found closed by its peer. */
    public interface Listener {
        Listener NONE = new Listener() { };
        default void opened() { }
        default void reused() { }
        default void stale() { }
    }

    /** A parsed reply, and the address that answered it. */
    public record Exchange(CommandReply reply, InetSocketAddress address) { }

    /** The reply line was longer than {@link Settings#maxReplyBytes()}; nothing of it was parsed. */
    public static final class ReplyTooLargeException extends IOException {
        ReplyTooLargeException(String message) { super(message); }
    }

    /** The reply line is not a reply in the command protocol. */
    public static final class MalformedReplyException extends IOException {
        MalformedReplyException(IOException cause) { super(cause.getMessage(), cause); }
    }

    /** A socket error while connecting: the only failure that moves on to the host's next address. */
    private static final class ConnectFailure extends IOException {
        ConnectFailure(SocketException cause) { super(cause); }
        SocketException socketError() { return (SocketException) getCause(); }
    }

    /** The peer closed a pooled connection before sending any reply, so the request never reached it. */
    private static final class StaleConnection extends IOException {
        StaleConnection(IOException cause) { super(cause); }
    }

    private static final class PeerClosedException extends IOException {
        PeerClosedException() { super("Peer closed without a reply"); }
    }

    /** An injected network fault swallowed the request; to its caller this is any other timeout. */
    private static final class LostRequest extends SocketTimeoutException {
        LostRequest(String message) { super(message); }
    }

    private final Settings settings;
    private final Listener listener;
    private final Supplier<Socket> sockets;
    /** The server this client sends for, which injected network faults are decided by; null for none. */
    private final String source;
    private final long connectNanos;
    private final long idleNanos;
    private final long idleLimitNanos;
    private final Set<Socket> active = new HashSet<>();
    /** Kept-open connections waiting for the next request to a peer; guarded by this client. */
    private final Map<InetSocketAddress, Deque<PeerConnection>> idle = new HashMap<>();
    /** Peers whose host rejected the keep-alive field (an older build), so they get one request per connection. */
    private final Set<InetSocketAddress> legacyPeers = new HashSet<>();
    private boolean closed;

    public KeepAliveClient(Settings settings) { this(settings, Listener.NONE); }

    public KeepAliveClient(Settings settings, Listener listener) { this(settings, listener, Socket::new); }

    /** Tests replace {@code sockets} to stall a write, which a real loopback socket cannot be relied on to do. */
    public KeepAliveClient(Settings settings, Listener listener, Supplier<Socket> sockets) {
        this(settings, listener, sockets, null);
    }

    /**
     * A client that sends for the server {@code source}, so that any {@link NetworkFaults} installed for testing apply
     * to its requests. Without a source, none do.
     */
    public KeepAliveClient(Settings settings, Listener listener, Supplier<Socket> sockets, String source) {
        this.settings = Objects.requireNonNull(settings);
        this.listener = Objects.requireNonNull(listener);
        this.sockets = Objects.requireNonNull(sockets);
        this.source = source;
        connectNanos = settings.connectTimeout().toNanos();
        idleNanos = settings.readIdleTimeout().toNanos();
        idleLimitNanos = settings.idleLimit().toNanos();
    }

    /**
     * Runs one instruction on the server at {@code address} within the request timeout, and returns its successful
     * reply. An error reply is thrown as a {@link CommandExecutionException}.
     */
    public CommandReply execute(InetSocketAddress address, String instruction, Map<String, Object> parameters)
            throws IOException {
        var request = new CommandRequest(1, UUID.randomUUID(), instruction, parameters);
        long deadline = System.nanoTime() + settings.requestTimeout().toNanos();
        var reply = exchange(List.of(address), envelope(request, true), envelope(request, false), deadline).reply();
        if (!request.messageId().equals(reply.messageId())) {
            throw new IOException("Message ID mismatch: sent " + request.messageId() + ", received " + reply.messageId());
        }
        if (reply.error() != null) { throw new CommandExecutionException(reply); }
        return reply;
    }

    /**
     * The request's line with, or without, the field asking the host to keep the connection open after replying. Callers
     * that budget their bytes exactly build the same envelope themselves and call {@link #exchange}.
     */
    public static byte[] envelope(CommandRequest request, boolean keepAlive) throws IOException {
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("version", request.version());
        envelope.put("messageId", request.messageId());
        envelope.put("instruction", request.instruction());
        envelope.put("parameters", request.parameters());
        if (keepAlive) { envelope.put(CommandProtocol.CONNECTION_FIELD, CommandProtocol.KEEP_ALIVE); }
        return Json.write(envelope).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Sends one request to a peer whose host has several addresses, tried in order, and returns its parsed reply. Only a
     * socket error while connecting moves on to the next address; timeouts and protocol failures end the request.
     * {@code keepAlive} is the request line asking for the connection to stay open, {@code plain} the same request
     * without that field, for a host that does not know it. The reply is not checked against the request: that is the
     * caller's.
     */
    public Exchange exchange(List<InetSocketAddress> candidates, byte[] keepAlive, byte[] plain, long deadline)
            throws IOException {
        if (candidates.isEmpty()) { throw new IllegalArgumentException("At least one address is required"); }
        synchronized (this) { if (closed) { throw closedFailure(null); } }
        SocketException unreachable = null;
        for (var address : candidates) {
            try { return exchange(address, keepAlive, plain, deadline); }
            catch (ConnectFailure failure) {
                if (unreachable == null) { unreachable = failure.socketError(); }
                else { unreachable.addSuppressed(failure.socketError()); }
            }
        }
        throw unreachable;
    }

    private Exchange exchange(InetSocketAddress address, byte[] keepAlive, byte[] plain, long deadline) throws IOException {
        var call = source == null ? null : PeerTraffic.begin(source, address, plain);
        try {
            var exchange = exchangeAfterFaults(address, keepAlive, plain, deadline);
            var error = exchange.reply().error();
            if (error == null) { PeerTraffic.end(call, PeerTraffic.Outcome.OK, null); }
            else { PeerTraffic.end(call, PeerTraffic.Outcome.ERROR_REPLY, error.code().name()); }
            return exchange;
        } catch (IOException | RuntimeException failure) {
            PeerTraffic.end(call, outcomeOf(failure), failure.getMessage());
            throw failure;
        }
    }

    private static PeerTraffic.Outcome outcomeOf(Exception failure) {
        if (failure instanceof LostRequest) { return PeerTraffic.Outcome.LOST; }
        if (failure instanceof SocketTimeoutException) { return PeerTraffic.Outcome.TIMEOUT; }
        if (failure instanceof ConnectFailure) { return PeerTraffic.Outcome.REFUSED; }
        return PeerTraffic.Outcome.FAILED;
    }

    private Exchange exchangeAfterFaults(InetSocketAddress address, byte[] keepAlive, byte[] plain, long deadline) throws IOException {
        injectFaults(address, deadline);
        var reply = parse(exchangeOver(address, keepAlive, plain, deadline));
        if (rejectsKeepAlive(reply)) {
            // An older host rejects the field. Remember that, and send this request again without it; from now on the
            // peer is asked for one request per connection.
            rememberLegacy(address);
            reply = parse(exchangeOver(address, keepAlive, plain, deadline));
        }
        return new Exchange(reply, address);
    }

    /**
     * Applies an injected network fault to a request about to be sent: waits out its delay, and for a lost request waits
     * as long as an unanswered connect would and then times out, as a sender behind a partition does.
     */
    private void injectFaults(InetSocketAddress address, long deadline) throws IOException {
        if (source == null) { return; }
        var fault = NetworkFaults.fault(source, address);
        if (fault.delayMillis() > 0) { sleepUntil(Math.min(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(fault.delayMillis()))); }
        if (fault.lost()) {
            sleepUntil(Math.min(deadline, System.nanoTime() + connectNanos));
            throw new LostRequest(settings.name() + " request to " + address + " was lost (injected network fault)");
        }
        checkDeadline(deadline);
    }

    private static void sleepUntil(long until) throws InterruptedIOException {
        try {
            long left = until - System.nanoTime();
            if (left > 0) { TimeUnit.NANOSECONDS.sleep(left); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while an injected network fault was delaying a request");
        }
    }

    private static CommandReply parse(String line) throws IOException {
        try { return CommandProtocol.parseReply(line); }
        catch (IOException unreadable) { throw new MalformedReplyException(unreadable); }
    }

    /**
     * One request over a connection to the peer: a kept-open one from the pool when there is one, otherwise a new one.
     * A pooled connection may have been closed by the peer since it was last used; that shows up as the connection
     * failing before any reply byte, and the request is then sent again over a new connection.
     */
    private String exchangeOver(InetSocketAddress address, byte[] keepAlive, byte[] plain, long deadline)
            throws IOException {
        checkDeadline(deadline);
        if (isLegacy(address)) {
            // This peer's host does not know the keep-alive field and closes after every reply.
            return converse(new PeerConnection(sockets.get()), address, plain, deadline, false);
        }
        var pooled = takeIdle(address);
        if (pooled != null) {
            try { return converse(pooled, address, keepAlive, deadline, true); }
            catch (StaleConnection stale) { listener.stale(); }
            checkDeadline(deadline);
        }
        return converse(new PeerConnection(sockets.get()), address, keepAlive, deadline, true);
    }

    /** An older host answers the keep-alive field with INVALID_REQUEST, "Unknown field: connection". */
    private static boolean rejectsKeepAlive(CommandReply reply) {
        return reply.error() != null && reply.error().code() == CommandError.Code.INVALID_REQUEST
                && reply.error().message().contains("Unknown field: " + CommandProtocol.CONNECTION_FIELD);
    }

    private synchronized boolean isLegacy(InetSocketAddress address) { return legacyPeers.contains(address); }

    /** Whether the host at this address rejected the keep-alive field, so is sent one request per connection. */
    public boolean oneRequestPerConnection(InetSocketAddress address) { return isLegacy(address); }

    /** Records a peer whose host rejected the keep-alive field, and closes any connection kept for it. */
    private synchronized void rememberLegacy(InetSocketAddress address) {
        legacyPeers.add(address);
        var kept = idle.remove(address);
        if (kept != null) { kept.forEach(peer -> closeQuietly(peer.socket)); }
    }

    /**
     * One blocking request and reply. Connects and reads have socket timeouts; a blocking write has none, so a
     * watchdog closes the socket once the overall deadline passes. An interrupt also ends the request: on a virtual
     * thread it closes the socket itself, and for a platform thread, which an interrupt does not wake from blocking
     * socket I/O, the watchdog closes the socket within {@value #INTERRUPT_CHECK_MILLIS} ms. The request waits for its
     * watchdog to end before returning, so no thread outlives it. A connection that carried a clean reply goes back to
     * the pool; any other is closed, so a late or partial reply can never be read as the answer to a later request.
     */
    private String converse(PeerConnection peer, InetSocketAddress address, byte[] payload, long deadline,
                            boolean keepAlive) throws IOException {
        var socket = peer.socket;
        boolean reused = socket.isConnected();
        boolean clean = false;
        try {
            synchronized (this) {
                if (closed) { throw closedFailure(null); }
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
                } catch (InterruptedException | IOException finished) { /* The request ended first. */ }
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
                    listener.reused();
                } else {
                    try { socket.connect(address, millis(Math.min(connectNanos, deadline - System.nanoTime()))); }
                    catch (SocketException refusedOrUnreachable) {
                        checkDeadline(deadline);
                        throw new ConnectFailure(refusedOrUnreachable);
                    }
                    listener.opened();
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
                synchronized (this) { if (closed) { throw closedFailure(closedUnderneath); } }
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
            // A request that did not ask for keep-alive is closed by the host after its reply, so it is never pooled.
            if (clean && keepAlive) { release(address, peer); } else { closeQuietly(socket); }
        }
    }

    /** A connection to a peer, with what is needed to reuse it for another request. */
    private static final class PeerConnection {
        final Socket socket;
        long idleSince;
        int uses;
        /** Set when the connection cannot safely carry another request, whatever its age or use count. */
        boolean unusable;
        /** The last reply ended with a carriage return whose line feed has not been read yet. */
        boolean pendingLineFeed;

        PeerConnection(Socket socket) { this.socket = socket; }
    }

    private synchronized PeerConnection takeIdle(InetSocketAddress address) {
        pruneIdle();
        var queue = idle.get(address);
        if (queue == null) { return null; }
        var peer = queue.pollLast();
        if (queue.isEmpty()) { idle.remove(address); }
        return peer;
    }

    /** Keeps a connection that carried a clean reply for the next request to the same peer. */
    private synchronized void release(InetSocketAddress address, PeerConnection peer) {
        if (closed || peer.unusable || peer.uses >= settings.maxUses() || peer.socket.isClosed()) {
            closeQuietly(peer.socket);
            return;
        }
        peer.idleSince = System.nanoTime();
        var queue = idle.computeIfAbsent(address, key -> new ArrayDeque<>());
        queue.addLast(peer);
        while (queue.size() > settings.maxIdlePerPeer()) { closeQuietly(queue.pollFirst().socket); }
        if (queue.isEmpty()) { idle.remove(address); }
        pruneIdle();
    }

    /** Closes pooled connections that have waited longer than the host keeps them; callers hold the lock. */
    private void pruneIdle() {
        long now = System.nanoTime();
        for (var queues = idle.values().iterator(); queues.hasNext();) {
            var queue = queues.next();
            while (!queue.isEmpty() && now - queue.peekFirst().idleSince > idleLimitNanos) {
                closeQuietly(queue.pollFirst().socket);
            }
            if (queue.isEmpty()) { queues.remove(); }
        }
    }

    /** Connections now held open for later requests, across all peers. */
    public synchronized int idleConnections() {
        return idle.values().stream().mapToInt(Deque::size).sum();
    }

    private static void closeQuietly(Socket socket) {
        try { socket.close(); } catch (IOException alreadyClosed) { /* Nothing more to release. */ }
    }

    /**
     * Reads one reply line into a bounded buffer, scanning only newly read bytes for the terminator. The buffer grows
     * as the line does, to one byte past the limit: that spare byte tells a full-length line followed by its terminator
     * apart from an oversized line.
     */
    private String readLine(PeerConnection peer, long deadline) throws IOException {
        var socket = peer.socket;
        int limit = settings.maxReplyBytes() + 1;
        byte[] bytes = new byte[Math.min(FIRST_BUFFER_BYTES, limit)];
        var input = socket.getInputStream();
        int length = 0;
        int scanned = 0;
        while (true) {
            checkDeadline(deadline);
            if (length == bytes.length) { bytes = Arrays.copyOf(bytes, (int) Math.min(limit, 2L * bytes.length)); }
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
            if (length == limit) {
                throw new ReplyTooLargeException(settings.name() + " reply exceeds " + settings.maxReplyBytes()
                        + " bytes before parsing");
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

    private void checkDeadline(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException(settings.name() + " exchange interrupted");
        }
        if (deadline - System.nanoTime() <= 0) {
            throw new SocketTimeoutException(settings.name() + " I/O deadline exceeded");
        }
    }

    private IOException closedFailure(IOException cause) {
        return new IOException(settings.name() + " transport is closed", cause);
    }

    /** Closes every connection, idle or in use, synchronously; a request in progress then fails as closed. */
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
