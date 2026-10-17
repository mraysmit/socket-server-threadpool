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

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * Cached hostname lookups with a timeout and a bound on how many run at once. Each lookup runs on its own daemon
 * thread. Stop releases waiting callers at once and interrupts lookups; one blocked inside the platform resolver
 * cannot be interrupted, so it is abandoned and still counts toward the bound until it returns.
 */
final class GossipDnsResolver implements AutoCloseable {
    @FunctionalInterface interface Lookup { InetAddress[] lookup(String host) throws Exception; }
    private static final long CACHE_NANOS = Duration.ofSeconds(60).toNanos();
    private static final int MAX_ADDRESSES = 16;
    private final int capacity;
    private final int maxHelpers;
    private final long timeoutNanos;
    private final LongSupplier clock;
    private final Lookup lookup;
    // Lookups in flight by hostname; a concurrent request for the same host shares the one already running.
    private final Map<String, InFlight> active = new LinkedHashMap<>();
    private final LinkedHashMap<String, Cached> cache = new LinkedHashMap<>(16, 0.75f, true);
    private boolean closed;

    GossipDnsResolver(int capacity, int maxHelpers) {
        this(capacity, maxHelpers, Duration.ofSeconds(2), System::nanoTime, InetAddress::getAllByName);
    }

    GossipDnsResolver(int capacity, int maxHelpers, Duration timeout, LongSupplier clock, Lookup lookup) {
        if (capacity < 1 || maxHelpers < 1 || timeout.toMillis() < 1) { throw new IllegalArgumentException("Positive resolver limits required"); }
        this.capacity = capacity;
        this.maxHelpers = maxHelpers;
        timeoutNanos = timeout.toNanos();
        this.clock = clock;
        this.lookup = lookup;
    }

    /**
     * Every address of the peer's host. The first address rotates between calls to spread load; the rest are
     * connection fallbacks, so one unreachable address (for example an unrouted IPv6 record) cannot fail the peer.
     */
    List<GossipConfig.Seed> resolveAll(GossipConfig.Seed peer) throws IOException {
        String host = peer.host().toLowerCase(Locale.ROOT);
        InFlight lookup;
        synchronized (this) {
            if (closed) { throw new IOException("Gossip resolver is closed"); }
            var literal = GossipValidation.literal(host);
            if (literal != null) { return List.of(numeric(peer, literal)); }
            Cached previous = cache.get(host);
            if (previous != null && clock.getAsLong() - previous.created < CACHE_NANOS) { return numeric(peer, previous.rotation()); }
            lookup = active.get(host);
            if (lookup == null) {
                if (running() >= maxHelpers) { throw new IOException("Gossip resolver capacity is full"); }
                var result = new CompletableFuture<List<InetAddress>>();
                lookup = new InFlight(Thread.ofPlatform().daemon().name("gossip-dns-lookup").unstarted(() -> {
                    try { result.complete(addresses(host)); }
                    catch (Throwable failure) { result.completeExceptionally(failure); }
                    finally { synchronized (this) { active.remove(host); } }
                }), result);
                active.put(host, lookup);
                lookup.thread().start();
            }
        }
        List<InetAddress> addresses;
        try { addresses = lookup.result().get(timeoutNanos, TimeUnit.NANOSECONDS); }
        catch (TimeoutException e) { lookup.thread().interrupt(); throw new SocketTimeoutException("Gossip DNS lookup timed out"); }
        catch (InterruptedException e) {
            lookup.thread().interrupt();
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Gossip DNS lookup interrupted");
        } catch (ExecutionException e) {
            throw e.getCause() instanceof IOException failure ? failure : new IOException("Gossip DNS lookup failed", e.getCause());
        }
        synchronized (this) {
            if (closed) { throw new IOException("Gossip resolver is closed"); }
            var resolved = new Cached(addresses, clock.getAsLong());
            cache.put(host, resolved);
            while (cache.size() > capacity) { cache.pollFirstEntry(); }
            return numeric(peer, resolved.rotation());
        }
    }

    /** Distinct addresses, IPv4 first, at most {@value #MAX_ADDRESSES}. */
    private List<InetAddress> addresses(String host) throws Exception {
        var found = Arrays.stream(lookup.lookup(host)).distinct()
                .sorted(Comparator.comparingInt(address -> address.getAddress().length)).limit(MAX_ADDRESSES).toList();
        if (found.isEmpty()) { throw new IOException("Empty DNS reply"); }
        return found;
    }

    private static GossipConfig.Seed numeric(GossipConfig.Seed peer, InetAddress address) {
        return new GossipConfig.Seed(peer.serverId(), address.getHostAddress(), peer.port());
    }

    private static List<GossipConfig.Seed> numeric(GossipConfig.Seed peer, List<InetAddress> addresses) {
        return addresses.stream().map(address -> numeric(peer, address)).toList();
    }

    synchronized int activeHelpers() { return running(); }

    /**
     * Lookups still running. A finished one stops counting at once: its thread removes it from {@code active} only
     * after waking its callers, and one of them may already be asking for the next host.
     */
    private int running() { return (int) active.values().stream().filter(lookup -> !lookup.result().isDone()).count(); }
    synchronized int cacheSize() { return cache.size(); }

    /** Releases waiting callers at once, then gives interruptible lookups a moment to end. */
    @Override public void close() {
        List<InFlight> lookups;
        synchronized (this) {
            closed = true;
            cache.clear();
            lookups = List.copyOf(active.values());
        }
        for (var lookup : lookups) {
            lookup.result().completeExceptionally(new IOException("Gossip resolver is closed"));
            lookup.thread().interrupt();
        }
        try { for (var lookup : lookups) { lookup.thread().join(Duration.ofSeconds(1)); } }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private record InFlight(Thread thread, CompletableFuture<List<InetAddress>> result) { }

    private static final class Cached {
        final List<InetAddress> addresses;
        final long created;
        int cursor;
        Cached(List<InetAddress> addresses, long created) { this.addresses = addresses; this.created = created; }
        /** All addresses starting at the cursor, which then advances. Callers hold the resolver monitor. */
        List<InetAddress> rotation() {
            var result = new ArrayList<InetAddress>(addresses.size());
            for (int i = 0; i < addresses.size(); i++) { result.add(addresses.get((cursor + i) % addresses.size())); }
            cursor = (cursor + 1) % addresses.size();
            return result;
        }
    }
}
