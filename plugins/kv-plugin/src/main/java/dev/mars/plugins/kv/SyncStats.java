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
package dev.mars.plugins.kv;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** What the comparisons have done: rounds run, rounds that found a difference, failures, and the last result with each peer. */
final class SyncStats {
    /** The last comparison with one peer: when it ended, how, and why it failed if it did. */
    record PeerSync(String serverId, long atMillis, String result, String detail) { }

    private final LongSupplier clock;
    private final AtomicLong rounds = new AtomicLong();
    private final AtomicLong differences = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final Map<String, PeerSync> peers = new ConcurrentHashMap<>();

    SyncStats(LongSupplier clock) { this.clock = clock; }

    void roundRan() { rounds.incrementAndGet(); }

    void differenceFound() { differences.incrementAndGet(); }

    void failed() { failures.incrementAndGet(); }

    void peerSynced(String serverId, String result, String detail) { peers.put(serverId, new PeerSync(serverId, clock.getAsLong(), result, detail)); }

    long rounds() { return rounds.get(); }

    long differences() { return differences.get(); }

    long failures() { return failures.get(); }

    List<PeerSync> peers() { return peers.values().stream().sorted(Comparator.comparing(PeerSync::serverId)).toList(); }

    /** As a status reply shows it. */
    Map<String, Object> toMap() {
        var reply = new LinkedHashMap<String, Object>();
        reply.put("rounds", rounds());
        reply.put("differences", differences());
        reply.put("failures", failures());
        var list = new ArrayList<Map<String, Object>>();
        for (var peer : peers()) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("serverId", peer.serverId());
            entry.put("lastSync", Instant.ofEpochMilli(peer.atMillis()).toString());
            entry.put("lastResult", peer.result());
            if (!peer.detail().isEmpty()) { entry.put("detail", peer.detail()); }
            list.add(entry);
        }
        reply.put("peers", list);
        return reply;
    }
}
