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
import dev.mars.client.KeepAliveClient;
import dev.mars.protocol.CommandReply;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Bounded UTF-8 JSON exchanges over connections to peers, kept open between exchanges so that gossip does not open and
 * close a TCP connection every round. The connections, their reuse and each exchange's deadline belong to the host's
 * {@link KeepAliveClient}; this transport chooses what to send, checks who answered, and merges the reply.
 */
public final class GossipTransport implements GossipRuntime.ExchangeClient {
    static final class BusyException extends IOException {
        BusyException() { super("Gossip outbound capacity is full"); }
    }
    private static final String OUTBOUND = "outbound"; // The direction identity log lines name.
    private final GossipConfig config;
    private final MembershipTable table;
    private final GossipWire wire;
    private final GossipMetrics metrics;
    private final GossipLog log;
    private final GossipBatchSelector requests = new GossipBatchSelector();
    private final Semaphore permits;
    private final KeepAliveClient connections;
    private final long exchangeNanos;
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
        this.metrics = metrics;
        log = metrics.log(config, table.self());
        wire = new GossipWire(config);
        permits = new Semaphore(config.maxInflight());
        exchangeNanos = positiveNanos(exchangeTimeout);
        positiveNanos(connectTimeout);
        positiveNanos(readIdleTimeout);
        var settings = KeepAliveClient.Settings.of("Gossip", config.maxExchangeBytes(), connectTimeout, readIdleTimeout,
                exchangeTimeout);
        connections = new KeepAliveClient(settings, new KeepAliveClient.Listener() {
            @Override public void opened() { metrics.connectionsOpened.incrementAndGet(); }
            @Override public void reused() { metrics.connectionsReused.incrementAndGet(); }
            @Override public void stale() { metrics.staleConnections.incrementAndGet(); }
        }, java.util.Objects.requireNonNull(sockets));
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
            var parameters = wire.toWire(request);
            byte[] payload = GossipWire.bytes(wire.envelope(messageId, parameters));
            // The same request without the keep-alive field, for a peer whose host does not know it.
            byte[] plain = GossipWire.bytes(wire.envelope(messageId, parameters, false));
            // The batch was chosen to fit, so this only guards against an error in that arithmetic.
            if (payload.length > config.maxExchangeBytes()) {
                throw new IOException("Outgoing exchange exceeds byte budget");
            }
            CommandReply reply = exchangeOverFirstReachable(candidates, payload, plain, deadline);
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

    /**
     * Sends the request over a kept-open connection when there is one. A pooled connection the peer has closed is
     * replaced and the request sent again, which is safe because merging an exchange is idempotent. Failures keep the
     * kinds gossip counts them by.
     */
    private CommandReply exchangeOverFirstReachable(List<InetSocketAddress> candidates, byte[] payload, byte[] plain,
            long deadline) throws IOException {
        try { return connections.exchange(candidates, payload, plain, deadline).reply(); }
        catch (KeepAliveClient.ReplyTooLargeException tooLarge) {
            throw new GossipExchangeException(GossipFailure.PROTOCOL,
                    "Gossip reply exceeds max.exchange.bytes before parsing", tooLarge);
        } catch (KeepAliveClient.MalformedReplyException unreadable) {
            throw new GossipExchangeException(GossipFailure.PROTOCOL, unreadable.getMessage(), unreadable);
        }
    }

    private static long positiveNanos(Duration timeout) {
        long nanos = timeout.toNanos();
        if (nanos <= 0) { throw new IllegalArgumentException("Timeout must be positive"); }
        return nanos;
    }

    public int inflight() { return config.maxInflight() - permits.availablePermits(); }

    /** Close owned I/O resources synchronously; callers own their threads, and DNS may still be running. */
    @Override public void close() throws IOException {
        synchronized (this) {
            if (closed) { return; }
            closed = true;
        }
        connections.close();
    }
}
