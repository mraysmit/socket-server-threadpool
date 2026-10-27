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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** How pushes are made: batched per peer, large values announced first, and peers with a clock too far ahead left out. */
@Timeout(30)
class ReplicatorPushTest {
    private static final Peer TWO = new Peer("Server-002", "127.0.0.1", 9100);
    private static final Peer THREE = new Peer("Server-003", "127.0.0.1", 9200);
    private static final Peer FOUR = new Peer("Server-004", "127.0.0.1", 9300);

    private final KvCounters counters = new KvCounters();
    private final List<Peer> alive = new ArrayList<>(List.of(TWO, THREE));
    private final List<Map.Entry<Peer, List<KvRecord>>> sent = Collections.synchronizedList(new ArrayList<>());
    private final List<Map.Entry<Peer, List<KvRecord>>> announced = Collections.synchronizedList(new ArrayList<>());
    /** Keys the fake peers say they want when a record is announced. */
    private final Set<String> wanted = Collections.synchronizedSet(new java.util.HashSet<>());

    private final PeerSource peers = () -> List.copyOf(alive);
    private final PeerLink link = new PeerLink() {
        @Override public void send(Peer peer, List<KvRecord> records) { sent.add(Map.entry(peer, records)); }
        @Override public List<String> announce(Peer peer, List<KvRecord> records) {
            announced.add(Map.entry(peer, records));
            return records.stream().map(KvRecord::key).filter(wanted::contains).toList();
        }
    };

    private static KvRecord record(String key, String valueJson, long w) {
        return new KvRecord(key, valueJson, new Version(w, 0, "Server-001"), false);
    }

    private static Replicator.Settings settings(long batchMillis, int maxBatchBytes, int announceBytes) {
        return new Replicator.Settings(Integer.MAX_VALUE, batchMillis, maxBatchBytes, announceBytes, Duration.ofMinutes(1));
    }

    private void awaitSends(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (sent.size() < count && System.nanoTime() < deadline) { TimeUnit.MILLISECONDS.sleep(5); }
        assertEquals(count, sent.size());
    }

    @Test void writesCloseTogetherGoToEachPeerInOneMessageWithEachKeyOnceAtItsNewest() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var replicator = new Replicator(peers, link, executor, counters, settings(200, 32_768, Integer.MAX_VALUE), new Random(1));
            replicator.push(record("a", "1", 1_000));
            replicator.push(record("b", "2", 1_001));
            replicator.push(record("a", "3", 1_002));
            replicator.push(record("c", "4", 1_003));
            awaitSends(2);
            TimeUnit.MILLISECONDS.sleep(300);
            assertEquals(2, sent.size(), "One message per peer");
            for (var entry : sent) {
                // Pushes run on their own tasks, so the order they reach the batch in is not fixed.
                assertEquals(3, entry.getValue().size());
                assertEquals(Set.of(record("a", "3", 1_002), record("b", "2", 1_001), record("c", "4", 1_003)), Set.copyOf(entry.getValue()));
            }
            assertEquals(2L, counters.pushed.get());
        }
    }

    @Test void aBatchThatWouldPassTheByteLimitIsSentAndANewOneStarted() throws Exception {
        alive.remove(THREE);
        String value = "\"" + "x".repeat(200) + "\"";
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var replicator = new Replicator(peers, link, executor, counters, settings(200, 700, Integer.MAX_VALUE), new Random(1));
            for (int i = 0; i < 6; i++) { replicator.push(record("k" + i, value, 1_000 + i)); }
            awaitSends(3);
            TimeUnit.MILLISECONDS.sleep(300);
            assertEquals(3, sent.size(), "About 330 bytes a record, so two to a 700-byte batch");
            assertEquals(6, sent.stream().mapToInt(entry -> entry.getValue().size()).sum());
        }
    }

    @Test void aLargeValueIsAnnouncedAndSentOnlyToAPeerThatWantsIt() {
        alive.remove(THREE);
        var replicator = new Replicator(peers, link, Runnable::run, counters, settings(0, 32_768, 10), new Random(1));
        var small = record("small", "1", 1_000);
        var large = record("large", "\"" + "y".repeat(50) + "\"", 1_001);
        replicator.forward(List.of(small, large), null);
        assertEquals(List.of(Map.entry(TWO, List.of(large))), announced, "Only the large value is announced");
        assertEquals(List.of(Map.entry(TWO, List.of(small))), sent, "The peer did not want it, so only the small one went whole");
        wanted.add("large");
        sent.clear();
        replicator.forward(List.of(large), null);
        assertEquals(List.of(Map.entry(TWO, List.of(large))), sent, "Asked for, so sent whole");
        assertEquals(2L, counters.announced.get());
        assertEquals(1L, counters.fetched.get());
    }

    @Test void aDeletionIsNeverAnnouncedBecauseItCarriesNoValue() {
        var replicator = new Replicator(peers, link, Runnable::run, counters, settings(0, 32_768, 1), new Random(1));
        replicator.push(new KvRecord("a", null, new Version(1_000, 0, "Server-001"), true));
        assertEquals(0, announced.size());
        assertEquals(2, sent.size());
    }

    @Test void aPenalisedPeerLeavesTheMeshAndCannotGraftBackIn() {
        alive.add(FOUR);
        var replicator = new Replicator(peers, link, Runnable::run, counters,
                new Replicator.Settings(2, 0, 32_768, Integer.MAX_VALUE, Duration.ofMinutes(1)), new Random(1));
        replicator.push(record("a", "1", 1_000));
        var meshed = replicator.mesh();
        assertEquals(2, meshed.size());
        var offender = meshed.getFirst();
        replicator.penalise(offender);
        replicator.grafted(offender);
        sent.clear();
        replicator.push(record("b", "2", 1_001));
        var targets = sent.stream().map(entry -> entry.getKey().serverId()).toList();
        assertFalse(targets.contains(offender), targets.toString());
        assertEquals(2, targets.size(), "The mesh was refilled from the peer not yet in it");
    }

    @Test void aPenaltyEnds() throws Exception {
        var replicator = new Replicator(peers, link, Runnable::run, counters,
                new Replicator.Settings(2, 0, 32_768, Integer.MAX_VALUE, Duration.ofMillis(50)), new Random(1));
        replicator.penalise(TWO.serverId());
        replicator.push(record("a", "1", 1_000));
        assertEquals(List.of(THREE), sent.stream().map(Map.Entry::getKey).toList());
        TimeUnit.MILLISECONDS.sleep(100);
        sent.clear();
        replicator.push(record("b", "2", 1_001));
        assertEquals(List.of(TWO, THREE), sent.stream().map(Map.Entry::getKey).toList());
    }
}
