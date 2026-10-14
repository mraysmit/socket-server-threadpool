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
package example.gossip;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

final class GossipMetrics {
    final AtomicLong inbound = new AtomicLong();
    final AtomicLong outbound = new AtomicLong();
    final AtomicLong outboundFailures = new AtomicLong();
    final AtomicLong rejectedExchanges = new AtomicLong();
    private volatile Long lastSuccess;

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
