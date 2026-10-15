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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import dev.mars.protocol.CommandReply;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class GossipBatchSelectorTest {
    private static final MemberRecord SELF = GossipCommandsTest.member("Self", 1);

    private static MembershipTable.Snapshot snapshot(MemberRecord... members) {
        var views = new ArrayList<MembershipTable.MemberView>();
        views.add(new MembershipTable.MemberView(SELF, MembershipTable.Assessment.ALIVE, 0, null, true, false));
        for (var record : members) {
            views.add(new MembershipTable.MemberView(record, MembershipTable.Assessment.CANDIDATE, 0, null, false, false));
        }
        return new MembershipTable.Snapshot(SELF, views, java.util.Set.of(), views.size(), views.size(), 0);
    }

    private static MemberRecord member(String id, int boot, long heartbeat, MemberRecord.State state, Map<String, Object> metadata) {
        return new MemberRecord(new MemberKey(id, new UUID(0, boot)), "127.0.0.1", 9010 + boot, heartbeat, state, metadata);
    }

    @Test void continuouslyChangedRecordsCannotStarveTheRotatingScan() {
        var config = GossipCommandsTest.config(32768, 32, 128);
        var table = new MembershipTable(config, GossipCommandsTest.member("Self", 1));
        for (int i = 0; i < 5; i++) { table.merge(List.of(GossipCommandsTest.member("Peer-" + i, i + 2))); }
        var selector = new GossipBatchSelector();
        var seen = new HashSet<String>();
        for (int i = 0; i < 20; i++) {
            var owner = GossipCommandsTest.member("Peer-0", 2);
            table.merge(List.of(new MemberRecord(owner.key(), owner.host(), owner.port(), i + 2, owner.state(), Map.of())));
            seen.add(selector.select(table.snapshot(), 2, Integer.MAX_VALUE).getFirst().key().serverId());
        }
        assertEquals(5, seen.size());
    }

    @Test void terminalChangesGetPriorityAlongsideTheFairScan() {
        var config = GossipCommandsTest.config(32768, 32, 128);
        var table = new MembershipTable(config, GossipCommandsTest.member("Self", 1));
        for (int i = 0; i < 5; i++) { table.merge(List.of(GossipCommandsTest.member("Peer-" + i, i + 2))); }
        var selector = new GossipBatchSelector();
        selector.select(table.snapshot(), 2, Integer.MAX_VALUE);
        var peer = GossipCommandsTest.member("Peer-4", 6);
        table.merge(List.of(new MemberRecord(peer.key(), peer.host(), peer.port(), peer.heartbeat(), MemberRecord.State.LEFT, Map.of())));
        var selected = new HashSet<MemberKey>();
        for (int i = 0; i < 2; i++) { selected.add(selector.select(table.snapshot(), 2, Integer.MAX_VALUE).getFirst().key()); }
        assertTrue(selected.contains(peer.key()));
    }

    @Test void byteTruncationAdvancesOnlyIncludedRecordsAndDoesNotLoseDeparturePriority() {
        var a = GossipCommandsTest.member("A", 2);
        var b = GossipCommandsTest.member("B", 3);
        var c = GossipCommandsTest.member("C", 4);
        var selector = new GossipBatchSelector(record -> 10);
        var original = snapshot(a, b, c);
        assertEquals(List.of(a), selector.select(original, 4, 20), "Two members require 21 bytes including the comma");
        assertEquals(List.of(b), selector.select(original, 4, 20));
        var left = new MemberRecord(c.key(), c.host(), c.port(), c.heartbeat(), MemberRecord.State.LEFT, Map.of());
        assertThrows(MembershipException.class, () -> selector.select(snapshot(a, b, left), 4, 9));
        assertEquals(List.of(left), selector.select(snapshot(a, b, left), 2, 10));
    }

    @Test void exactMemberBudgetIncludesEveryArrayCommaAndAvoidsIntegerOverflow() {
        var a = GossipCommandsTest.member("A", 2);
        var b = GossipCommandsTest.member("B", 3);
        assertEquals(List.of(a, b), new GossipBatchSelector(record -> 10).select(snapshot(a, b), 3, 21));
        assertEquals(List.of(a), new GossipBatchSelector(record -> Integer.MAX_VALUE).select(snapshot(a, b), 3, Integer.MAX_VALUE));
    }

    @Test void senderOnlyAndEmptyTablesNeedNoRecordEncodingAndRejectOversizedEnvelopes() {
        var calls = new AtomicInteger();
        var selector = new GossipBatchSelector(record -> { calls.incrementAndGet(); return 10; });
        assertTrue(selector.select(snapshot(), 5, 0).isEmpty());
        assertTrue(selector.select(snapshot(GossipCommandsTest.member("A", 2)), 1, 0).isEmpty());
        assertEquals(0, calls.get());
        assertThrows(MembershipException.class, () -> selector.select(snapshot(), 1, -1));
        assertThrows(IllegalArgumentException.class, () -> selector.select(snapshot(), 0, 10));
    }

    @Test void removedRecordsArePrunedFromQueuesAndSizeCacheAndReappearAsNewCandidates() {
        var calls = new AtomicInteger();
        var selector = new GossipBatchSelector(record -> { calls.incrementAndGet(); return 10; });
        var a = GossipCommandsTest.member("A", 2);
        var b = GossipCommandsTest.member("B", 3);
        selector.select(snapshot(a, b), 2, 10);
        assertEquals(List.of(a), selector.select(snapshot(a), 2, 10));
        assertEquals(2, calls.get());
        var selected = selector.select(snapshot(b, a), 3, 21);
        assertEquals(java.util.Set.of(a, b), java.util.Set.copyOf(selected));
        assertEquals(3, calls.get(), "A removed identity must not retain its cached encoding indefinitely");
    }

    @Test void changingRecordContentsInvalidatesItsSizeEvenIfAnInvalidSnapshotReusesTheVersion() {
        var a = member("A", 2, 1, MemberRecord.State.ALIVE, Map.of());
        var larger = member("A", 2, 1, MemberRecord.State.ALIVE, Map.of("text", "雪".repeat(30)));
        var selector = new GossipBatchSelector();
        selector.select(snapshot(a), 2, a.serializedBytes());
        assertThrows(MembershipException.class, () -> selector.select(snapshot(larger), 2, a.serializedBytes()));
        assertEquals(List.of(larger), selector.select(snapshot(larger), 2, larger.serializedBytes()));
    }

    @ParameterizedTest @CsvSource({"false, 1", "false, 2", "true, 1", "true, 2"})
    void incrementalBudgetsMatchActualUtf8EnvelopesAtTheExactBoundary(boolean reply, int version) {
        var properties = GossipConfigTest.minimal();
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("text", "雪 🌍 \"quotes\" \\ \n\t");
        metadata.put("null", null);
        metadata.put("numbers", List.of(new BigInteger("123456789012345678901234567890"), new BigDecimal("0.000000000123")));
        var a = member("A", 2, Long.MAX_VALUE, MemberRecord.State.ALIVE, metadata);
        var b = member("B", 3, 1, MemberRecord.State.LEFT, Map.of("padding", "x".repeat(800)));
        var members = List.of(b, a); // LEFT records take the first priority slot.
        var baseWire = new GossipWire(GossipConfig.fromProperties(properties, "Self"));
        int actual = reply ? GossipWire.bytes(new CommandReply(1, new UUID(0, 0), "Self", Long.MAX_VALUE, "OK",
                baseWire.result(SELF, members, version, MembershipTable.Admission.CAPACITY), null)).length
                : baseWire.requestBytes(baseWire.parameters(SELF, members, version)).length;
        assertTrue(actual >= 1024);
        for (int difference : List.of(-1, 0, 1)) {
            properties.setProperty("max.exchange.bytes", Integer.toString(actual + difference));
            var wire = new GossipWire(GossipConfig.fromProperties(properties, "Self"));
            int budget = reply ? wire.replyMemberBudget(SELF, version) : wire.requestMemberBudget(SELF);
            assertEquals(a.serializedBytes() + b.serializedBytes() + 1 + difference, budget);
            var selected = new GossipBatchSelector().select(snapshot(a, b), 3, budget);
            assertEquals(difference < 0 ? List.of(b) : members, selected);
            int encoded = reply ? GossipWire.bytes(new CommandReply(1, UUID.randomUUID(), "Self", Long.MAX_VALUE, "OK",
                    wire.result(SELF, selected, version, MembershipTable.Admission.CAPACITY), null)).length
                    : wire.requestBytes(wire.parameters(SELF, selected, version)).length;
            assertTrue(encoded <= actual + difference);
        }
    }

    @Test void concurrentSelectionSharesColdEncodingsAndNeverDuplicatesMembersWithinABatch() throws Exception {
        var calls = new AtomicInteger();
        var selector = new GossipBatchSelector(record -> { calls.incrementAndGet(); return record.serializedBytes(); });
        var members = new MemberRecord[32];
        for (int i = 0; i < members.length; i++) { members[i] = GossipCommandsTest.member("Peer-" + i, i + 2); }
        var state = snapshot(members);
        var gate = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var batches = new ArrayList<java.util.concurrent.Future<List<MemberRecord>>>();
            for (int i = 0; i < 100; i++) {
                batches.add(workers.submit(() -> { gate.await(); return selector.select(state, 8, 32768); }));
            }
            gate.countDown();
            var covered = new HashSet<MemberKey>();
            for (var batch : batches) {
                var selected = batch.get(5, TimeUnit.SECONDS);
                assertEquals(7, selected.size());
                assertEquals(7, selected.stream().map(MemberRecord::key).distinct().count());
                selected.forEach(record -> covered.add(record.key()));
            }
            assertEquals(32, covered.size());
        }
        assertEquals(32, calls.get(), "Concurrent cold calls must share each immutable record's size calculation");
    }

    @Test void recordEncodingCannotHoldTheSelectorStateLockOrBlockAnIndependentEmptySnapshot() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var reference = new AtomicReference<GossipBatchSelector>();
        var selector = new GossipBatchSelector(record -> {
            assertFalse(Thread.holdsLock(reference.get()));
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            return record.serializedBytes();
        });
        reference.set(selector);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var blocked = workers.submit(() -> selector.select(snapshot(GossipCommandsTest.member("A", 2)), 2, 32768));
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertTrue(workers.submit(() -> selector.select(snapshot(), 2, 0)).get(2, TimeUnit.SECONDS).isEmpty());
                release.countDown();
                assertEquals(1, blocked.get(2, TimeUnit.SECONDS).size());
            } finally { release.countDown(); }
        }
    }

    @Test void warmedSelectionEncodesOnlyChangedRecordsInsteadOfEveryGrowingBatch() {
        var calls = new AtomicInteger();
        var selector = new GossipBatchSelector(record -> { calls.incrementAndGet(); return record.serializedBytes(); });
        var members = new MemberRecord[127];
        for (int i = 0; i < members.length; i++) { members[i] = GossipCommandsTest.member("Peer-" + i, i + 2); }
        var state = snapshot(members);
        assertEquals(31, selector.select(state, 32, 32768).size());
        assertEquals(127, calls.get());
        long began = System.nanoTime();
        for (int i = 0; i < 1000; i++) { assertEquals(31, selector.select(state, 32, 32768).size()); }
        long elapsed = System.nanoTime() - began;
        assertEquals(127, calls.get(), "Unchanged records must not be re-encoded for size checks");
        for (int i = 0; i < 4; i++) {
            var old = members[i];
            members[i] = new MemberRecord(old.key(), old.host(), old.port(), 2, old.state(), old.metadata());
        }
        selector.select(snapshot(members), 32, 32768);
        assertEquals(131, calls.get());
        System.out.printf("Batch selector work PASSED | members=128 | batch=32 | coldRecordEncodes=127 | warmSelections=1000 | warmRecordEncodes=0 | changedRecordEncodes=4 | warmElapsedMs=%.3f%n", elapsed / 1_000_000.0);
    }
}
