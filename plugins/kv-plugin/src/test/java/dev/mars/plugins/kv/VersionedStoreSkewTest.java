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

/** A record stamped too far ahead of this server's clock is refused, and does not move the clock; announcements are judged alike. */
class VersionedStoreSkewTest {
    private static final long NOW = 1_000_000;
    private static final long SKEW = KvConfig.defaults().maxClockSkewMillis();
    private final VersionedStore store = new VersionedStore("Server-001", () -> NOW, KvConfig.defaults());

    private static KvRecord written(String key, long w) { return new KvRecord(key, "\"v\"", new Version(w, 0, "Server-002"), false); }

    @Test void aRecordWithinTheSkewIsTaken() {
        var taken = new ArrayList<KvRecord>();
        assertEquals(new VersionedStore.Applied(1, 0, 0, 0), store.apply(List.of(written("a", NOW + SKEW)), taken));
        assertEquals(1, taken.size());
    }

    @Test void aRecordTooFarAheadIsRefusedCountedAndLeavesTheClockAlone() {
        var taken = new ArrayList<KvRecord>();
        var result = store.apply(List.of(written("a", NOW + SKEW + 1), written("b", NOW)), taken);
        assertEquals(new VersionedStore.Applied(1, 0, 1, 1), result);
        assertEquals(List.of(written("b", NOW)), taken, "Only the record within the skew is passed on");
        assertTrue(store.get("a").isEmpty());
        // A local write is stamped from this server's own clock, not from the refused record's.
        var write = store.put("c", "\"1\"");
        assertTrue(write.record().version().w() <= NOW + 1, String.valueOf(write.record().version()));
    }

    @Test void anAnnouncementIsWantedOnlyWhenItWouldBeTaken() {
        store.apply(List.of(written("held", NOW)));
        assertTrue(store.wants(new VersionedStore.DigestRow("new", new Version(NOW, 0, "Server-002"), false)));
        assertTrue(store.wants(new VersionedStore.DigestRow("held", new Version(NOW + 1, 0, "Server-002"), false)));
        assertFalse(store.wants(new VersionedStore.DigestRow("held", new Version(NOW, 0, "Server-002"), false)), "Already held");
        assertFalse(store.wants(new VersionedStore.DigestRow("ahead", new Version(NOW + SKEW + 1, 0, "Server-002"), false)));
        assertFalse(store.wants(new VersionedStore.DigestRow("k".repeat(1_000), new Version(NOW, 0, "Server-002"), false)), "Key too long");
    }
}
