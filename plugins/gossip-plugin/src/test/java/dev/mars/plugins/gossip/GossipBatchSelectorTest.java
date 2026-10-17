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

import dev.mars.protocol.CommandReply;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class GossipBatchSelectorTest {
    private static final MemberRecord SELF = GossipCommandsTest.member("Self", 1);

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
            seen.add(selector.select(table.peers(), 2, Integer.MAX_VALUE).getFirst().key().serverId());
        }
        assertEquals(5, seen.size());
    }

    @Test void terminalChangesGetPriorityAlongsideTheFairScan() {
        var config = GossipCommandsTest.config(32768, 32, 128);
        var table = new MembershipTable(config, GossipCommandsTest.member("Self", 1));
        for (int i = 0; i < 5; i++) { table.merge(List.of(GossipCommandsTest.member("Peer-" + i, i + 2))); }
        var selector = new GossipBatchSelector();
        selector.select(table.peers(), 2, Integer.MAX_VALUE);
        var peer = GossipCommandsTest.member("Peer-4", 6);
        table.merge(List.of(new MemberRecord(peer.key(), peer.host(), peer.port(), peer.heartbeat(), MemberRecord.State.LEFT, Map.of())));
        var selected = new HashSet<MemberKey>();
        for (int i = 0; i < 2; i++) { selected.add(selector.select(table.peers(), 2, Integer.MAX_VALUE).getFirst().key()); }
        assertTrue(selected.contains(peer.key()));
    }

    @Test void byteTruncationAdvancesOnlyIncludedRecordsAndDoesNotLoseDeparturePriority() {
        var a = GossipCommandsTest.member("A", 2);
        var b = GossipCommandsTest.member("B", 3);
        var c = GossipCommandsTest.member("C", 4);
        var selector = new GossipBatchSelector(record -> 10);
        var original = List.of(a, b, c);
        assertEquals(List.of(a), selector.select(original, 4, 20), "Two members require 21 bytes including the comma");
        assertEquals(List.of(b), selector.select(original, 4, 20));
        var left = new MemberRecord(c.key(), c.host(), c.port(), c.heartbeat(), MemberRecord.State.LEFT, Map.of());
        assertThrows(MembershipException.class, () -> selector.select(List.of(a, b, left), 4, 9));
        assertEquals(List.of(left), selector.select(List.of(a, b, left), 2, 10));
    }

    @Test void exactMemberBudgetIncludesEveryArrayCommaAndAvoidsIntegerOverflow() {
        var a = GossipCommandsTest.member("A", 2);
        var b = GossipCommandsTest.member("B", 3);
        assertEquals(List.of(a, b), new GossipBatchSelector(record -> 10).select(List.of(a, b), 3, 21));
        assertEquals(List.of(a), new GossipBatchSelector(record -> Integer.MAX_VALUE).select(List.of(a, b), 3, Integer.MAX_VALUE));
    }

    @Test void senderOnlyAndEmptyTablesNeedNoRecordEncodingAndRejectOversizedEnvelopes() {
        var calls = new AtomicInteger();
        var selector = new GossipBatchSelector(record -> { calls.incrementAndGet(); return 10; });
        assertTrue(selector.select(List.of(), 5, 0).isEmpty());
        assertTrue(selector.select(List.of(GossipCommandsTest.member("A", 2)), 1, 0).isEmpty());
        assertEquals(0, calls.get());
        assertThrows(MembershipException.class, () -> selector.select(List.of(), 1, -1));
        assertThrows(IllegalArgumentException.class, () -> selector.select(List.of(), 0, 10));
    }

    @Test void sizesAreReadOnlyForTheRecordsABatchConsiders() {
        var calls = new AtomicInteger();
        var selector = new GossipBatchSelector(record -> { calls.incrementAndGet(); return 10; });
        var members = new ArrayList<MemberRecord>();
        for (int i = 0; i < 32; i++) { members.add(GossipCommandsTest.member("Peer-" + i, i + 2)); }
        assertEquals(2, selector.select(members, 3, 1000).size());
        assertEquals(2, calls.get(), "Only the two records selected are measured, not all 32");
        calls.set(0);
        assertEquals(1, selector.select(members, 3, 15).size());
        assertEquals(2, calls.get(), "The record that did not fit is measured too, and nothing beyond it");
    }

    @Test void removingAnEarlierMemberDoesNotMakeTheScanSkipTheNextOne() {
        var selector = new GossipBatchSelector(record -> 10);
        var members = new ArrayList<MemberRecord>();
        for (String id : List.of("A", "B", "C", "D", "E", "F")) { members.add(GossipCommandsTest.member(id, members.size() + 2)); }
        var all = List.copyOf(members);
        for (int i = 0; i < 3; i++) { selector.select(all, 3, 1000); } // Every record sent once; no changes pending.
        assertEquals(List.of(members.get(0), members.get(1)), selector.select(all, 3, 1000));
        // C is next. Removing B shifts C down one place; the scan must still resume at C, not skip to D.
        var withoutB = new ArrayList<>(members);
        withoutB.remove(1);
        assertEquals(List.of(members.get(2), members.get(3)), selector.select(withoutB, 3, 1000));
    }

    @Test void memberSentByPriorityDoesNotOpenTheNextBatch() {
        var selector = new GossipBatchSelector(record -> 10);
        var members = new ArrayList<MemberRecord>();
        for (String id : List.of("A", "B", "C", "D")) { members.add(GossipCommandsTest.member(id, members.size() + 2)); }
        assertEquals(List.of(members.get(0), members.get(1)), selector.select(List.copyOf(members), 3, 1000));
        assertEquals(List.of(members.get(2), members.get(3)), selector.select(List.copyOf(members), 3, 1000));
        var b = members.get(1);
        members.set(1, new MemberRecord(b.key(), b.host(), b.port(), b.heartbeat() + 1, b.state(), Map.of()));
        // B goes first by priority and A is the rotation's next; B, just sent, must not open the next batch as well.
        assertEquals(List.of(members.get(1), members.get(0)), selector.select(List.copyOf(members), 3, 1000));
        assertEquals(List.of(members.get(2), members.get(3)), selector.select(List.copyOf(members), 3, 1000));
    }

    @Test void removedRecordsArePrunedFromQueuesAndReappearAsNewCandidates() {
        var selector = new GossipBatchSelector(record -> 10);
        var a = GossipCommandsTest.member("A", 2);
        var b = GossipCommandsTest.member("B", 3);
        selector.select(List.of(a, b), 2, 10);
        assertEquals(List.of(a), selector.select(List.of(a), 2, 10));
        var selected = selector.select(List.of(b, a), 3, 21);
        assertEquals(java.util.Set.of(a, b), java.util.Set.copyOf(selected));
    }

    @Test void changingRecordContentsInvalidatesItsSizeEvenIfAnInvalidSnapshotReusesTheVersion() {
        var a = member("A", 2, 1, MemberRecord.State.ALIVE, Map.of());
        var larger = member("A", 2, 1, MemberRecord.State.ALIVE, Map.of("text", "雪".repeat(30)));
        var selector = new GossipBatchSelector();
        selector.select(List.of(a), 2, a.serializedBytes());
        assertThrows(MembershipException.class, () -> selector.select(List.of(larger), 2, a.serializedBytes()));
        assertEquals(List.of(larger), selector.select(List.of(larger), 2, larger.serializedBytes()));
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
            int budget = reply ? wire.replyMemberBudget(SELF, version, MembershipTable.Admission.CAPACITY) : wire.requestMemberBudget(SELF);
            assertEquals(a.serializedBytes() + b.serializedBytes() + 1 + difference, budget);
            var selected = new GossipBatchSelector().select(List.of(a, b), 3, budget);
            assertEquals(difference < 0 ? List.of(b) : members, selected);
            int encoded = reply ? GossipWire.bytes(new CommandReply(1, UUID.randomUUID(), "Self", Long.MAX_VALUE, "OK",
                    wire.result(SELF, selected, version, MembershipTable.Admission.CAPACITY), null)).length
                    : wire.requestBytes(wire.parameters(SELF, selected, version)).length;
            assertTrue(encoded <= actual + difference);
        }
    }

    @Test void concurrentSelectionNeverDuplicatesMembersWithinABatch() throws Exception {
        var selector = new GossipBatchSelector();
        var members = new MemberRecord[32];
        for (int i = 0; i < members.length; i++) { members[i] = GossipCommandsTest.member("Peer-" + i, i + 2); }
        var state = List.of(members);
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
    }

    @Test void trackerReportsWhatEachSnapshotChanged() {
        var tracker = new GossipBatchSelector.MemberTracker();
        var a = member("A", 2, 1, MemberRecord.State.ALIVE, Map.of());
        var b = member("B", 3, 1, MemberRecord.State.ALIVE, Map.of());
        var c = member("C", 4, 1, MemberRecord.State.ALIVE, Map.of());
        var first = tracker.update(List.of(a, b));
        assertEquals(List.of(a, b), first.changes().stream().map(GossipBatchSelector.Change::record).toList());
        assertTrue(first.changes().stream().allMatch(GossipBatchSelector.Change::news), "Every member is new");
        var memberA = first.changes().get(0).member();
        var memberB = first.changes().get(1).member();
        assertSame(GossipBatchSelector.Diff.NONE, tracker.update(List.of(a, b)), "The same records");
        assertSame(GossipBatchSelector.Diff.NONE, tracker.update(List.of(b, a)), "A reorder changes nothing");
        var newerA = member("A", 2, 2, MemberRecord.State.ALIVE, Map.of());
        var sameVersionB = member("B", 3, 1, MemberRecord.State.ALIVE, Map.of("text", "x"));
        var changed = tracker.update(List.of(newerA, sameVersionB, c));
        assertEquals(new GossipBatchSelector.Change(memberA, newerA, true), changed.changes().get(0), "A new heartbeat is news");
        assertEquals(new GossipBatchSelector.Change(memberB, sameVersionB, false), changed.changes().get(1),
                "A different record with the same version replaces the old one but is not news");
        assertSame(c, changed.changes().get(2).record());
        assertTrue(changed.changes().get(2).news(), "A new member is news");
        assertEquals(List.of(), changed.removed());
        var leftA = member("A", 2, 2, MemberRecord.State.LEFT, Map.of());
        assertEquals(List.of(new GossipBatchSelector.Change(memberA, leftA, true)),
                tracker.update(List.of(leftA, sameVersionB, c)).changes(), "A new state is news");
        var shrunk = tracker.update(List.of(c));
        assertEquals(List.of(), shrunk.changes());
        assertEquals(java.util.Set.of(memberA, memberB), java.util.Set.copyOf(shrunk.removed()));
    }

    @Test void choosingABatchChangesNothingUntilItIsRecordedAsSent() {
        var queues = new GossipBatchSelector.BatchQueues();
        var records = new ArrayList<MemberRecord>();
        var changes = new ArrayList<GossipBatchSelector.Change>();
        for (String id : List.of("A", "B", "C", "D")) {
            var record = GossipCommandsTest.member(id, records.size() + 2);
            records.add(record);
            // Not news, so the members only join the rotation and nothing waits for a priority pick.
            changes.add(new GossipBatchSelector.Change(new GossipBatchSelector.Member(record.key()), record, false));
        }
        queues.apply(new GossipBatchSelector.Diff(changes, List.of()));
        var first = queues.choose(2, 1000, record -> 10);
        assertEquals(List.of(records.get(0), records.get(1)), first.batch());
        assertEquals(first, queues.choose(2, 1000, record -> 10), "Choosing again before recording gives the same batch");
        queues.sent(first);
        // A and B moved to the back of the rotation, so C and D come next, then A and B again.
        var second = queues.choose(2, 1000, record -> 10);
        assertEquals(List.of(records.get(2), records.get(3)), second.batch());
        queues.sent(second);
        assertEquals(List.of(records.get(0), records.get(1)), queues.choose(2, 1000, record -> 10).batch());
    }
}
