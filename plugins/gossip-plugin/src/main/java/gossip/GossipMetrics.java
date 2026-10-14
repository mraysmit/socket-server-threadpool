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

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicLong;

final class GossipMetrics {
    final AtomicLong inbound = new AtomicLong();
    final AtomicLong outbound = new AtomicLong();
    final AtomicLong outboundFailures = new AtomicLong();
    final AtomicLong rejectedExchanges = new AtomicLong();
    final AtomicLong rounds = new AtomicLong();
    final AtomicLong roundFailures = new AtomicLong();
    final AtomicLong skipped = new AtomicLong();
    enum Failure { DNS, NETWORK, TIMEOUT, PROTOCOL, IDENTITY, CAPACITY, VERSION, SWARM }
    private final Map<Failure, AtomicLong> failures = new java.util.EnumMap<>(Failure.class);
    volatile String workerState = "INITIALIZING";
    volatile int workers;
    volatile int dnsHelpers;
    volatile int retryingPeers;
    volatile Long lastRound;
    private volatile Long lastSuccess;

    GossipMetrics() { for (var kind : Failure.values()) { failures.put(kind, new AtomicLong()); } }
    void failure(Failure kind) { failures.get(kind).incrementAndGet(); }
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
        result.put("lastRoundAgeMillis", lastRound == null ? null : Math.max(0, System.nanoTime() - lastRound) / 1_000_000);
        result.put("failures", kinds);
        return result;
    }

    void success(boolean outgoing) {
        (outgoing ? outbound : inbound).incrementAndGet();
        lastSuccess = System.nanoTime();
    }

    boolean isolated() { return lastSuccess == null; }
    Long lastSuccessAgeMillis() {
        Long previous = lastSuccess;
        return previous == null ? null : Math.max(0, System.nanoTime() - previous) / 1_000_000;
    }
    Map<String, Object> counts(int inflight) {
        return Map.of("inbound", inbound.get(), "outbound", outbound.get(), "outboundFailures", outboundFailures.get(),
                "rejected", rejectedExchanges.get(), "inflight", inflight);
    }
}
