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

import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

/** Bounded candidate/retry state, random ordinary fanout, and fair reserved recovery opportunities. */
final class PeerSelector {
    private static final long MAX_BACKOFF = Duration.ofSeconds(30).toNanos();
    private final GossipConfig config;
    private final LongSupplier clock;
    private final RandomGenerator random;
    private final long roundNanos;
    private final Map<GossipConfig.Seed, Retry> retries = new LinkedHashMap<>();
    private int recoveryCursor;
    private long selections;

    PeerSelector(GossipConfig config, LongSupplier clock, RandomGenerator random) {
        this.config = config;
        this.clock = clock;
        this.random = random;
        roundNanos = config.roundMillis() * 1_000_000;
    }

    synchronized List<GossipConfig.Seed> select(MembershipTable.Snapshot snapshot, Set<GossipConfig.Seed> busy, int slots) {
        var candidates = new LinkedHashMap<GossipConfig.Seed, Boolean>();
        var self = snapshot.self();
        for (var member : snapshot.members()) {
            if (member.record().key().equals(self.key()) || member.record().state() == MemberRecord.State.LEFT) { continue; }
            var record = member.record();
            var peer = new GossipConfig.Seed(record.key().serverId(), record.host(), record.port());
            if (!isSelf(self, peer)) { candidates.merge(peer, member.assessment() != MembershipTable.Assessment.ALIVE, (a, b) -> a && b); }
        }
        int cap = Math.max(0, config.maxMembers() - 1);
        for (var seed : config.seeds()) {
            if (!isSelf(self, seed) && (candidates.containsKey(seed) || candidates.size() < cap)) {
                candidates.put(seed, true);
            }
        }
        retries.keySet().removeIf(peer -> !candidates.containsKey(peer) && !busy.contains(peer));
        var ordinary = new ArrayList<GossipConfig.Seed>();
        var recovery = new ArrayList<GossipConfig.Seed>();
        long now = clock.getAsLong();
        for (var item : candidates.entrySet()) {
            if (busy.contains(item.getKey())) { continue; }
            Retry retry = retries.get(item.getKey());
            if (retry != null && now - retry.attempted < retry.delay) { continue; }
            (item.getValue() ? recovery : ordinary).add(item.getKey());
        }
        for (int i = ordinary.size() - 1; i > 0; i--) {
            int swap = random.nextInt(i + 1);
            var value = ordinary.get(i); ordinary.set(i, ordinary.get(swap)); ordinary.set(swap, value);
        }
        int count = Math.min(config.fanout(), Math.max(0, slots));
        var selected = new ArrayList<GossipConfig.Seed>();
        boolean reserved = ++selections % 3 == 0 || ordinary.isEmpty();
        if (reserved && !recovery.isEmpty() && count > 0) { selected.add(nextRecovery(recovery)); }
        for (var peer : ordinary) { if (selected.size() == count) { break; } selected.add(peer); }
        while (selected.size() < count && !selected.containsAll(recovery)) {
            if (recovery.isEmpty()) { break; }
            var peer = nextRecovery(recovery);
            if (!selected.contains(peer)) { selected.add(peer); }
            else if (selected.containsAll(recovery)) { break; }
        }
        return List.copyOf(selected);
    }

    private GossipConfig.Seed nextRecovery(List<GossipConfig.Seed> recovery) {
        var peer = recovery.get(Math.floorMod(recoveryCursor, recovery.size()));
        recoveryCursor = (recoveryCursor + 1) % recovery.size();
        return peer;
    }

    synchronized void success(GossipConfig.Seed peer) { retries.remove(peer); }

    synchronized void failure(GossipConfig.Seed peer) {
        Retry old = retries.get(peer);
        int failures = old == null ? 1 : Math.min(31, old.failures + 1);
        long base = Math.min(roundNanos, MAX_BACKOFF);
        for (int i = 1; i < failures && base < MAX_BACKOFF; i++) { base = Math.min(MAX_BACKOFF, base * 2); }
        long low = base - base / 4;
        long delay = low + random.nextLong(Math.max(1, Math.min(MAX_BACKOFF - low, base / 2) + 1));
        if (retries.containsKey(peer) || retries.size() < config.maxMembers()) {
            retries.put(peer, new Retry(failures, clock.getAsLong(), delay));
        }
    }

    synchronized long initialDelay() { return 1 + random.nextLong(roundNanos); }
    synchronized long roundDelay() {
        long low = roundNanos - roundNanos / 5;
        long spread = Math.min(Long.MAX_VALUE - low, roundNanos / 5 * 2);
        return low + random.nextLong(spread + 1);
    }
    synchronized int retryCount() { return retries.size(); }

    static boolean isSelf(MemberRecord self, GossipConfig.Seed peer) {
        if (self.port() != peer.port()) { return false; }
        if (self.host().equalsIgnoreCase(peer.host())) { return true; }
        try { return InetAddress.ofLiteral(self.host()).equals(InetAddress.ofLiteral(peer.host())); }
        catch (IllegalArgumentException notLiteral) { return false; }
    }

    private record Retry(int failures, long attempted, long delay) { }
}
