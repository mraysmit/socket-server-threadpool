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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Answers another server's comparison requests from this server's store. A reply is kept within a byte budget, so a large
 * difference is settled over several requests: the reply says where to carry on from.
 */
final class SyncResponder {
    record RootReply(boolean equal, String root) { }
    /** A page of the other server's digest, covering its keys after {@code after} up to {@code upTo} (null: to the end). */
    record DigestRequest(String after, String upTo, List<VersionedStore.DigestRow> rows) { }
    /** The records this server holds that are newer or missing there, the keys it wants, and where to carry on if it stopped early. */
    record DigestReply(List<KvRecord> records, List<String> want, String continueAfter) { }

    /** What a record's punctuation and field names add to its key, value and server name, as a round estimate. */
    private static final int RECORD_OVERHEAD = 64;

    private final VersionedStore store;
    private final int maxBytes;

    SyncResponder(VersionedStore store, int maxBytes) {
        this.store = store;
        this.maxBytes = maxBytes;
    }

    RootReply root(String senderRoot) {
        String mine = store.root();
        return new RootReply(mine.equals(senderRoot), mine);
    }

    DigestReply digest(DigestRequest request) {
        var mine = store.range(request.after(), request.upTo());
        var theirs = request.rows();
        var records = new ArrayList<KvRecord>();
        var want = new ArrayList<String>();
        int used = 0, i = 0, j = 0;
        String lastDone = null;
        while (i < mine.size() || j < theirs.size()) {
            var own = i < mine.size() ? mine.get(i) : null;
            var other = j < theirs.size() ? theirs.get(j) : null;
            int order = own == null ? 1 : other == null ? -1 : own.key().compareTo(other.key());
            KvRecord send = null;
            String ask = null;
            String key;
            if (order < 0) { send = own; key = own.key(); }
            else if (order > 0) { ask = other.key(); key = other.key(); }
            else {
                key = own.key();
                int byVersion = own.version().compareTo(other.version());
                if (byVersion > 0) { send = own; } else if (byVersion < 0) { ask = key; }
            }
            int cost = send != null ? recordBytes(send) : ask != null ? ask.getBytes(StandardCharsets.UTF_8).length + 8 : 0;
            if (cost > 0 && used + cost > maxBytes && (!records.isEmpty() || !want.isEmpty())) {
                return new DigestReply(List.copyOf(records), List.copyOf(want), lastDone);
            }
            used += cost;
            if (send != null) { records.add(send); }
            if (ask != null) { want.add(ask); }
            lastDone = key;
            if (order <= 0) { i++; }
            if (order >= 0) { j++; }
        }
        return new DigestReply(List.copyOf(records), List.copyOf(want), null);
    }

    /** About how many bytes a record takes on the wire. */
    static int recordBytes(KvRecord record) {
        int value = record.valueJson() == null ? 0 : record.valueJson().getBytes(StandardCharsets.UTF_8).length;
        return record.key().getBytes(StandardCharsets.UTF_8).length + value + record.version().n().getBytes(StandardCharsets.UTF_8).length + RECORD_OVERHEAD;
    }
}
