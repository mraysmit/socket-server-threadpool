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

/** A store summarises itself a page of keys at a time, so two servers can compare a large store in several small messages. */
class VersionedStoreDigestTest {
    private long now = 1_000_000;
    private final VersionedStore store = new VersionedStore("Server-001", () -> now, KvConfig.defaults());

    private static KvRecord written(String key, long w) { return new KvRecord(key, "1", new Version(w, 0, "Server-002"), false); }

    private static KvRecord deleted(String key, long w) { return new KvRecord(key, null, new Version(w, 0, "Server-002"), true); }

    private void fill(String... keys) {
        long w = now;
        for (var key : keys) { store.apply(List.of(written(key, w++))); }
    }

    @Test void anEmptyStoreHasAnEmptyLastPage() {
        var page = store.digestPage(null, 10_000);
        assertEquals(List.of(), page.rows());
        assertFalse(page.more());
    }

    @Test void aPageHoldsTheRowsInKeyOrderWithTheirVersionsAndDeletionFlags() {
        fill("b", "a");
        store.apply(List.of(deleted("c", now + 10)));
        var page = store.digestPage(null, 10_000);
        assertEquals(List.of("a", "b", "c"), page.rows().stream().map(VersionedStore.DigestRow::key).toList());
        assertEquals(new Version(now + 1, 0, "Server-002"), page.rows().get(0).version());
        assertFalse(page.rows().get(0).deleted());
        assertTrue(page.rows().get(2).deleted());
        assertFalse(page.more());
    }

    @Test void aPageStartsAfterTheGivenKeyWhichIsNotIncluded() {
        fill("a", "b", "c", "d");
        assertEquals(List.of("c", "d"), store.digestPage("b", 10_000).rows().stream().map(VersionedStore.DigestRow::key).toList());
        assertEquals(List.of(), store.digestPage("d", 10_000).rows());
        assertEquals(List.of("a", "b", "c", "d"), store.digestPage("", 10_000).rows().stream().map(VersionedStore.DigestRow::key).toList());
    }

    @Test void theByteBudgetLimitsAPageAndItAlwaysHoldsAtLeastOneRow() {
        fill("a", "b", "c", "d", "e");
        int one = store.digestPage(null, 1).rows().getFirst().bytes();
        assertEquals(1, store.digestPage(null, 1).rows().size(), "A budget smaller than one row still gives one row");
        assertTrue(store.digestPage(null, 1).more());
        var two = store.digestPage(null, one * 2);
        assertEquals(2, two.rows().size());
        assertTrue(two.more());
        assertEquals(5, store.digestPage(null, one * 5).rows().size());
        assertFalse(store.digestPage(null, one * 5).more());
    }

    @Test void walkingPagesFromTheStartVisitsEveryKeyOnce() {
        fill("a", "b", "c", "d", "e", "f", "g");
        int one = store.digestPage(null, 1).rows().getFirst().bytes();
        var seen = new ArrayList<String>();
        String after = null;
        int pages = 0;
        while (true) {
            var page = store.digestPage(after, one * 3);
            page.rows().forEach(row -> seen.add(row.key()));
            pages++;
            if (!page.more()) { break; }
            after = page.rows().getLast().key();
        }
        assertEquals(List.of("a", "b", "c", "d", "e", "f", "g"), seen);
        assertEquals(3, pages);
    }

    @Test void aDeletionRecordPastTheRetentionIsNotInTheDigestAsItIsNotInTheRoot() {
        var shortLived = new VersionedStore("Server-001", () -> now, new KvConfig(256, 16_384, 1_000, true, 2_000, 1, 32_768, 1_000));
        shortLived.apply(List.of(deleted("gone", now - 5_000), written("kept", now)));
        assertEquals(List.of("kept"), shortLived.digestPage(null, 10_000).rows().stream().map(VersionedStore.DigestRow::key).toList());
    }
}
