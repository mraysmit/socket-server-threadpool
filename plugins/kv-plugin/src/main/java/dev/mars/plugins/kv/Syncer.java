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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Compares this store with another server's and settles the difference, so both end holding the newer of every record. If
 * the roots are equal that is one exchange. Otherwise this server walks its keys a page at a time: for each page the other
 * server returns what it has that is newer or missing and names the keys it wants, and this server hands those over.
 */
final class Syncer {
    /** What one comparison did: whether nothing differed, records received and sent, and digest pages walked. */
    record Outcome(boolean equal, int received, int sent, int pages) { }

    private final VersionedStore store;
    private final SyncLink link;
    private final int maxBytes;

    Syncer(VersionedStore store, SyncLink link, int maxBytes) {
        this.store = store;
        this.link = link;
        this.maxBytes = maxBytes;
    }

    Outcome syncWith(Peer peer) throws IOException {
        if (link.syncRoot(peer, store.root()).equal()) { return new Outcome(true, 0, 0, 0); }
        int received = 0, sent = 0, pages = 0;
        String after = null;
        while (true) {
            var page = store.digestPage(after, maxBytes);
            String upTo = page.more() ? page.rows().getLast().key() : null;
            var reply = link.syncDigest(peer, new SyncResponder.DigestRequest(after, upTo, page.rows()));
            pages++;
            received += store.apply(reply.records()).applied();
            sent += hand(peer, reply.want());
            if (reply.continueAfter() != null) { after = reply.continueAfter(); }
            else if (page.more()) { after = upTo; }
            else { return new Outcome(false, received, sent, pages); }
        }
    }

    /** Sends the records for these keys in batches that each fit the budget, and says how many were sent. */
    private int hand(Peer peer, List<String> keys) throws IOException {
        var batch = new ArrayList<KvRecord>();
        int used = 0, sent = 0;
        for (var key : keys) {
            var record = store.raw(key);
            if (record.isEmpty()) { continue; }
            int bytes = SyncResponder.recordBytes(record.get());
            if (!batch.isEmpty() && used + bytes > maxBytes) {
                link.apply(peer, List.copyOf(batch));
                sent += batch.size();
                batch.clear();
                used = 0;
            }
            batch.add(record.get());
            used += bytes;
        }
        if (!batch.isEmpty()) {
            link.apply(peer, List.copyOf(batch));
            sent += batch.size();
        }
        return sent;
    }
}
