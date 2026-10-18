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

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;

final class GossipMetrics {
    final AtomicLong inbound = new AtomicLong();
    final AtomicLong outbound = new AtomicLong();
    final AtomicLong outboundFailures = new AtomicLong();
    final AtomicLong rejectedExchanges = new AtomicLong();
    final AtomicLong rounds = new AtomicLong();
    final AtomicLong roundFailures = new AtomicLong();
    final AtomicLong skipped = new AtomicLong();
    /** Outbound connections opened, outbound exchanges that reused one, and reused ones the peer had already closed. */
    final AtomicLong connectionsOpened = new AtomicLong();
    final AtomicLong connectionsReused = new AtomicLong();
    final AtomicLong staleConnections = new AtomicLong();
    private final Map<GossipFailure, AtomicLong> failures = new java.util.EnumMap<>(GossipFailure.class);
    volatile int workers;
    volatile int dnsHelpers;
    volatile int retryingPeers;
    // Marks a time not yet taken, so recording one boxes nothing; a nanoTime reading of exactly this value would only
    // read as unknown until the next. It never leaves this class: ages are reported as OptionalLong.
    private static final long NEVER = Long.MIN_VALUE;
    volatile long lastRound = NEVER;
    private volatile long lastSuccess = NEVER;
    private GossipLog log;

    GossipMetrics() { this(null); }
    GossipMetrics(GossipLog log) {
        this.log = log;
        for (var kind : GossipFailure.values()) { failures.put(kind, new AtomicLong()); }
    }
    synchronized GossipLog log(GossipConfig config, MemberRecord self) {
        if (log == null) { log = new GossipLog(config, self); }
        return log;
    }
    void failure(GossipFailure kind) { failures.get(kind).incrementAndGet(); }
    Map<String, Object> runtime() {
        var kinds = new LinkedHashMap<String, Long>();
        failures.forEach((kind, count) -> kinds.put(kind.name(), count.get()));
        var result = new LinkedHashMap<String, Object>();
        result.put("rounds", rounds.get());
        result.put("roundFailures", roundFailures.get());
        result.put("skipped", skipped.get());
        result.put("workers", workers);
        result.put("dnsHelpers", dnsHelpers);
        result.put("retryingPeers", retryingPeers);
        result.put("lastRoundAgeMillis", GossipWire.orNull(ageMillis(lastRound)));
        result.put("failures", kinds);
        result.put("connections", Map.of("opened", connectionsOpened.get(), "reused", connectionsReused.get(),
                "stale", staleConnections.get()));
        return result;
    }

    void success(boolean outgoing) {
        (outgoing ? outbound : inbound).incrementAndGet();
        lastSuccess = System.nanoTime();
    }

    boolean isolated() { return lastSuccess == NEVER; }
    OptionalLong lastSuccessAgeMillis() { return ageMillis(lastSuccess); }

    /** Milliseconds since a nanoTime reading, or empty if none has been taken. */
    private static OptionalLong ageMillis(long then) {
        return then == NEVER
                ? OptionalLong.empty() : OptionalLong.of(TimeUnit.NANOSECONDS.toMillis(Math.max(0,
                System.nanoTime() - then)));
    }
    Map<String, Object> counts(int inflight) {
        return Map.of("inbound", inbound.get(), "outbound", outbound.get(), "outboundFailures", outboundFailures.get(),
                "rejected", rejectedExchanges.get(), "inflight", inflight);
    }
}
