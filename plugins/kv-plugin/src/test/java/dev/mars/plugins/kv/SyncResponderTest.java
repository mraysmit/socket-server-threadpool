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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The server that is asked: given a page of the other server's digest, what it has that is newer and what it wants. */
class SyncResponderTest {
    private final VersionedStore mine = new VersionedStore("Server-002", () -> 1, KvConfig.defaults());
    private final SyncResponder responder = new SyncResponder(mine, 32_768);

    private static KvRecord written(String key, String value, long w, String server) {
        return new KvRecord(key, value, new Version(w, 0, server), false);
    }

    private static KvRecord deleted(String key, long w, String server) {
        return new KvRecord(key, null, new Version(w, 0, server), true);
    }

    /** What the other server would send for these records: their digest rows, over the whole key range. */
    private static SyncResponder.DigestRequest pageOf(String after, String upTo, KvRecord... records) {
        var rows = new ArrayList<VersionedStore.DigestRow>();
        for (var record : records) { rows.add(new VersionedStore.DigestRow(record.key(), record.version(), record.deleted())); }
        return new SyncResponder.DigestRequest(after, upTo, rows);
    }

    @Test void equalRootsAreReportedAsEqual() {
        mine.apply(List.of(written("a", "1", 100, "Server-001")));
        assertTrue(responder.root(mine.root()).equal());
        assertEquals(mine.root(), responder.root("0000000000000000").root());
        assertFalse(responder.root("0000000000000000").equal());
    }

    @Test void aKeyIHoldAtANewerVersionIsReturnedInFull() {
        mine.apply(List.of(written("a", "mine", 200, "Server-002")));
        var reply = responder.digest(pageOf(null, null, written("a", "theirs", 100, "Server-001")));
        assertEquals(List.of(written("a", "mine", 200, "Server-002")), reply.records());
        assertEquals(List.of(), reply.want());
        assertNull(reply.continueAfter());
    }

    @Test void aKeyTheyHoldAtANewerVersionIsWanted() {
        mine.apply(List.of(written("a", "mine", 100, "Server-002")));
        var reply = responder.digest(pageOf(null, null, written("a", "theirs", 200, "Server-001")));
        assertEquals(List.of(), reply.records());
        assertEquals(List.of("a"), reply.want());
    }

    @Test void equalVersionsNeedNothingInEitherDirection() {
        mine.apply(List.of(written("a", "1", 100, "Server-001")));
        var reply = responder.digest(pageOf(null, null, written("a", "1", 100, "Server-001")));
        assertEquals(List.of(), reply.records());
        assertEquals(List.of(), reply.want());
    }

    @Test void aKeyIDoNotHoldButTheyDoIsWanted() {
        var reply = responder.digest(pageOf(null, null, written("only-theirs", "1", 100, "Server-001")));
        assertEquals(List.of("only-theirs"), reply.want());
        assertEquals(List.of(), reply.records());
    }

    @Test void aKeyIHoldInTheirRangeThatIsNotOnTheirPageIsReturned() {
        mine.apply(List.of(written("b", "only-mine", 100, "Server-002")));
        var reply = responder.digest(pageOf(null, "c", written("a", "1", 100, "Server-001"), written("c", "1", 100, "Server-001")));
        assertEquals(List.of(written("b", "only-mine", 100, "Server-002")), reply.records());
        assertEquals(List.of("a", "c"), reply.want());
    }

    @Test void keysOutsideTheRangeOfThePageAreLeftAlone() {
        mine.apply(List.of(written("a", "before", 100, "Server-002"), written("m", "inside", 100, "Server-002"),
                written("z", "after", 100, "Server-002")));
        var reply = responder.digest(pageOf("c", "p", written("m", "inside", 100, "Server-002")));
        assertEquals(List.of(), reply.records());
        assertEquals(List.of(), reply.want());
    }

    @Test void aPageWithNoUpperBoundCoversEverythingToTheEnd() {
        mine.apply(List.of(written("a", "1", 100, "Server-002"), written("m", "2", 100, "Server-002"), written("z", "3", 100, "Server-002")));
        var reply = responder.digest(pageOf("a", null));
        assertEquals(List.of("m", "z"), reply.records().stream().map(KvRecord::key).toList());
    }

    @Test void deletionRecordsTravelBothWays() {
        mine.apply(List.of(deleted("mine-gone", 300, "Server-002"), written("theirs-gone", "1", 100, "Server-002")));
        var reply = responder.digest(pageOf(null, null, written("mine-gone", "1", 200, "Server-001"), deleted("theirs-gone", 400, "Server-001")));
        assertEquals(List.of(deleted("mine-gone", 300, "Server-002")), reply.records());
        assertEquals(List.of("theirs-gone"), reply.want());
    }

    @Test void aReplyThatWouldBeTooLargeIsCutOffWithTheKeyToContinueAfter() {
        for (int i = 0; i < 10; i++) { mine.apply(List.of(written(String.format("k%02d", i), "x".repeat(100), 100, "Server-002"))); }
        var small = new SyncResponder(mine, 400);
        var reply = small.digest(pageOf(null, null));
        assertFalse(reply.records().isEmpty());
        assertTrue(reply.records().size() < 10, "cut off: " + reply.records().size());
        assertNotNull(reply.continueAfter());
        assertEquals(reply.records().getLast().key(), reply.continueAfter());
        assertEquals(List.of(), reply.want(), "Keys not reached are not reported either way");
    }

    @Test void continuingFromTheCutOffKeyVisitsEveryRecordExactlyOnce() {
        for (int i = 0; i < 10; i++) { mine.apply(List.of(written(String.format("k%02d", i), "x".repeat(100), 100, "Server-002"))); }
        var small = new SyncResponder(mine, 400);
        var seen = new ArrayList<String>();
        String after = null;
        int rounds = 0;
        do {
            var reply = small.digest(pageOf(after, null));
            reply.records().forEach(record -> seen.add(record.key()));
            after = reply.continueAfter();
            rounds++;
        } while (after != null);
        assertEquals(List.of("k00", "k01", "k02", "k03", "k04", "k05", "k06", "k07", "k08", "k09"), seen);
        assertTrue(rounds > 1);
    }

    @Test void aSingleRecordLargerThanTheBudgetIsStillReturnedSoTheSyncMakesProgress() {
        mine.apply(List.of(written("big", "x".repeat(2_000), 100, "Server-002")));
        var reply = new SyncResponder(mine, 100).digest(pageOf(null, null));
        assertEquals(1, reply.records().size());
        assertNull(reply.continueAfter());
    }
}
