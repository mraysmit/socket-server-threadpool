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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class VersionedStoreTest {
    private long now = 1_000;
    private final VersionedStore store = new VersionedStore("Server-001", () -> now, KvConfig.defaults());

    @Test void versionsOrderByWallClockThenCounterThenServer() {
        assertTrue(new Version(1, 0, "a").compareTo(new Version(2, 0, "a")) < 0);
        assertTrue(new Version(1, 0, "a").compareTo(new Version(1, 1, "a")) < 0);
        assertTrue(new Version(1, 1, "a").compareTo(new Version(1, 1, "b")) < 0);
        assertTrue(new Version(2, 0, "a").compareTo(new Version(1, 9, "z")) > 0);
        assertEquals(0, new Version(1, 1, "a").compareTo(new Version(1, 1, "a")));
    }

    @Test void aWrittenValueIsReadBackWithAVersionNamingTheWriter() {
        var written = store.put("colour", "\"blue\"");
        assertFalse(written.hadLive());
        var record = store.get("colour").orElseThrow();
        assertEquals("\"blue\"", record.valueJson());
        assertEquals("Server-001", record.version().n());
        assertEquals(written.record(), record);
        assertTrue(store.put("colour", "\"green\"").hadLive());
        assertEquals("\"green\"", store.get("colour").orElseThrow().valueJson());
    }

    @Test void keysAreListedInOrder() {
        for (String key : List.of("b", "a", "c")) { store.put(key, "1"); }
        assertEquals(List.of("a", "b", "c"), store.keys());
        assertEquals(3, store.liveCount());
    }

    @Test void aDeletedKeyDisappearsFromReadsButLeavesADeletionRecord() {
        store.put("a", "1");
        var deleted = store.delete("a");
        assertTrue(deleted.hadLive());
        assertEquals(Optional.empty(), store.get("a"));
        assertEquals(List.of(), store.keys());
        assertEquals(0, store.liveCount());
        assertEquals(1, store.deletedCount());
        var record = store.raw("a").orElseThrow();
        assertTrue(record.deleted());
        assertNull(record.valueJson());
        assertEquals(deleted.record(), record);
    }

    @Test void deletingAKeyNeverHeldStillLeavesADeletionRecordForPeersToLearnFrom() {
        var deleted = store.delete("never-held");
        assertFalse(deleted.hadLive());
        assertEquals(1, store.deletedCount());
        assertTrue(store.raw("never-held").orElseThrow().deleted());
    }

    @Test void twoWritesInTheSameMillisecondGetIncreasingVersions() {
        var first = store.put("a", "1").record().version();
        var second = store.put("b", "2").record().version();
        var third = store.delete("a").record().version();
        assertTrue(second.compareTo(first) > 0);
        assertTrue(third.compareTo(second) > 0);
    }

    @Test void aClockThatGoesBackwardsStillGivesIncreasingVersions() {
        var before = store.put("a", "1").record().version();
        now -= 500;
        var after = store.put("a", "2").record().version();
        assertTrue(after.compareTo(before) > 0, before + " then " + after);
    }

    @Test void versionsFollowTheClockWhenItMovesForward() {
        store.put("a", "1");
        now += 250;
        assertEquals(now, store.put("a", "2").record().version().w());
    }

    private static KvRecord written(String key, String json, long w, String server) {
        return new KvRecord(key, json, new Version(w, 0, server), false);
    }

    private static KvRecord deleted(String key, long w, String server) {
        return new KvRecord(key, null, new Version(w, 0, server), true);
    }

    @Test void aRecordFromAPeerForANewKeyIsStoredAsReceived() {
        var result = store.apply(List.of(written("a", "1", 5_000, "Server-002")));
        assertEquals(new VersionedStore.Applied(1, 0), result);
        assertEquals(written("a", "1", 5_000, "Server-002"), store.get("a").orElseThrow());
    }

    @Test void aNewerRecordReplacesAnOlderOneAndAnOlderOrEqualOneIsIgnored() {
        store.apply(List.of(written("a", "old", 2_000, "Server-002")));
        assertEquals(new VersionedStore.Applied(1, 0), store.apply(List.of(written("a", "new", 3_000, "Server-002"))));
        assertEquals("new", store.get("a").orElseThrow().valueJson());
        assertEquals(new VersionedStore.Applied(0, 1), store.apply(List.of(written("a", "stale", 2_500, "Server-003"))));
        assertEquals(new VersionedStore.Applied(0, 1), store.apply(List.of(written("a", "new", 3_000, "Server-002"))));
        assertEquals("new", store.get("a").orElseThrow().valueJson());
    }

    @Test void aWriteFromAnotherServerAtTheSameTimeLosesOrWinsByServerName() {
        store.apply(List.of(written("a", "from-2", 4_000, "Server-002")));
        store.apply(List.of(written("a", "from-3", 4_000, "Server-003")));
        assertEquals("from-3", store.get("a").orElseThrow().valueJson());
    }

    @Test void aDeletionBeatsAnOlderWriteAndAWriteBeatsAnOlderDeletion() {
        store.apply(List.of(written("a", "1", 2_000, "Server-002")));
        store.apply(List.of(deleted("a", 3_000, "Server-002")));
        assertEquals(Optional.empty(), store.get("a"));
        assertEquals(1, store.deletedCount());
        store.apply(List.of(written("a", "2", 4_000, "Server-003")));
        assertEquals("2", store.get("a").orElseThrow().valueJson());
        store.apply(List.of(deleted("a", 3_500, "Server-004")));
        assertEquals("2", store.get("a").orElseThrow().valueJson(), "An older deletion does not remove a newer write");
    }

    @Test void receivingALaterTimestampMovesTheLocalClockPastIt() {
        store.apply(List.of(written("a", "1", 9_000, "Server-002")));
        var local = store.put("a", "2").record().version();
        assertTrue(local.w() > 9_000, "A write made after seeing w=9000 must be newer than it, got " + local);
    }

    @Test void receivingAnOlderTimestampDoesNotMoveTheClockBackwards() {
        store.put("a", "1");
        now += 100;
        var before = store.put("b", "1").record().version();
        store.apply(List.of(written("c", "1", 10, "Server-002")));
        assertTrue(store.put("d", "1").record().version().compareTo(before) > 0);
    }

    @Test void theSameRecordsInAnyOrderGiveTheSameStore() {
        var records = new ArrayList<KvRecord>();
        var random = new Random(42);
        for (int i = 0; i < 60; i++) {
            String key = "k" + random.nextInt(6);
            String server = "Server-00" + (1 + random.nextInt(3));
            // A server never issues the same version twice, so the counter makes each generated version unique.
            var version = new Version(1_000 + random.nextInt(8), i, server);
            records.add(random.nextInt(4) == 0 ? new KvRecord(key, null, version, true) : new KvRecord(key, "v" + i, version, false));
        }
        Map<String, KvRecord> expected = null;
        for (int round = 0; round < 40; round++) {
            Collections.shuffle(records, random);
            var other = new VersionedStore("Server-009", () -> 1, KvConfig.defaults());
            for (var record : records) { other.apply(List.of(record)); }
            var seen = new TreeMap<String, KvRecord>();
            for (var key : List.of("k0", "k1", "k2", "k3", "k4", "k5")) { other.raw(key).ifPresent(r -> seen.put(key, r)); }
            if (expected == null) { expected = seen; } else { assertEquals(expected, seen, "round " + round); }
        }
    }

    private VersionedStore limited(int maxKey, int maxValue, int maxRecords) {
        return new VersionedStore("Server-001", () -> now, new KvConfig(maxKey, maxValue, maxRecords, true));
    }

    @Test void aReplicatedRecordBeyondThePairLimitIsRefusedAndCounted() {
        var small = limited(256, 16384, 2);
        var result = small.apply(List.of(written("a", "1", 2_000, "Server-002"), written("b", "2", 2_001, "Server-002"),
                written("c", "3", 2_002, "Server-002")));
        assertEquals(new VersionedStore.Applied(2, 0, 1), result);
        assertEquals(List.of("a", "b"), small.keys());
        // Replacing a pair that is already held, and recording a deletion, are not new pairs.
        assertEquals(new VersionedStore.Applied(2, 0, 0), small.apply(List.of(written("a", "1b", 3_000, "Server-002"),
                deleted("b", 3_001, "Server-002"))));
        assertEquals(List.of("a"), small.keys());
    }

    @Test void aReplicatedRecordWithAnOversizedKeyOrValueIsRefusedAndCounted() {
        var small = limited(5, 10, 100);
        var result = small.apply(List.of(written("toolong", "1", 2_000, "Server-002"),
                written("ok", "12345678901", 2_001, "Server-002"), written("fine", "1", 2_002, "Server-002")));
        assertEquals(new VersionedStore.Applied(1, 0, 2), result);
        assertEquals(List.of("fine"), small.keys());
    }

    private static final KvConfig SHORT_RETENTION = new KvConfig(256, 16_384, 1_000, true, 2_000, 1, 32_768, 1_000);

    private VersionedStore storeWithShortRetention() {
        return new VersionedStore("Server-001", () -> now, SHORT_RETENTION);
    }

    @Test void aRootIsSixteenHexCharactersAndAnEmptyStoreHasOne() {
        assertTrue(store.root().matches("[0-9a-f]{16}"), store.root());
        assertEquals(store.root(), new VersionedStore("Server-009", () -> 5, KvConfig.defaults()).root());
    }

    @Test void storesHoldingTheSameRecordsHaveTheSameRootWhateverTheOrder() {
        var records = List.of(written("a", "1", 2_000, "Server-002"), written("b", "2", 2_001, "Server-002"),
                deleted("c", 2_002, "Server-003"), written("d", "4", 2_003, "Server-003"));
        var forwards = new VersionedStore("Server-001", () -> 1, KvConfig.defaults());
        var backwards = new VersionedStore("Server-009", () -> 1, KvConfig.defaults());
        for (var record : records) { forwards.apply(List.of(record)); }
        for (var record : records.reversed()) { backwards.apply(List.of(record)); }
        assertEquals(forwards.root(), backwards.root());
    }

    @Test void anyChangeToTheRecordsChangesTheRoot() {
        var other = new VersionedStore("Server-009", () -> 1, KvConfig.defaults());
        store.apply(List.of(written("a", "1", 2_000, "Server-002")));
        other.apply(List.of(written("a", "1", 2_000, "Server-002")));
        String before = store.root();
        assertEquals(before, other.root());
        store.apply(List.of(written("b", "2", 2_001, "Server-002")));
        assertNotEquals(before, store.root(), "a new key");
        other.apply(List.of(written("a", "1", 2_500, "Server-003")));
        assertNotEquals(before, other.root(), "the same key at a newer version");
        var deletedStore = new VersionedStore("Server-009", () -> 1, KvConfig.defaults());
        deletedStore.apply(List.of(deleted("a", 2_000, "Server-002")));
        assertNotEquals(before, deletedStore.root(), "a deletion where there was a value");
    }

    @Test void theValueItselfDoesNotChangeTheRootBecauseEqualVersionsMeanEqualValues() {
        var one = new VersionedStore("Server-001", () -> 1, KvConfig.defaults());
        var two = new VersionedStore("Server-002", () -> 1, KvConfig.defaults());
        one.apply(List.of(written("a", "1", 2_000, "Server-009")));
        two.apply(List.of(written("a", "1", 2_000, "Server-009")));
        assertEquals(one.root(), two.root());
    }

    @Test void aDeletionRecordOlderThanTheRetentionIsLeftOutOfTheRootAndThenDropped() {
        var kept = storeWithShortRetention();
        var without = storeWithShortRetention();
        now = 10_000;
        kept.put("live", "1");
        without.put("live", "1");
        kept.apply(List.of(deleted("gone", 10_000, "Server-002")));
        assertNotEquals(without.root(), kept.root(), "while the deletion record is within the retention, it counts");
        now = 10_000 + 1_001;
        assertEquals(without.root(), kept.root(), "once it has expired it no longer counts, even before it is dropped");
        assertEquals(1, kept.deletedCount());
        assertEquals(1, kept.purgeExpired());
        assertEquals(0, kept.deletedCount());
        assertEquals(Optional.empty(), kept.raw("gone"));
    }

    @Test void aLivePairIsNeverDroppedAndADeletionWithinTheRetentionIsKept() {
        var s = storeWithShortRetention();
        now = 10_000;
        s.put("live", "1");
        s.delete("recent");
        now = 10_000 + 900;
        assertEquals(0, s.purgeExpired());
        assertEquals(List.of("live"), s.keys());
        assertEquals(1, s.deletedCount());
        now = 10_000 + 50_000;
        assertEquals(1, s.purgeExpired());
        assertEquals(List.of("live"), s.keys(), "A live pair is never dropped, however old");
    }

    @Test void aWriteAfterADeletionRevivesTheKey() {
        store.put("a", "1");
        store.delete("a");
        var revived = store.put("a", "2");
        assertFalse(revived.hadLive());
        assertEquals("2", store.get("a").orElseThrow().valueJson());
        assertEquals(0, store.deletedCount());
    }
}
