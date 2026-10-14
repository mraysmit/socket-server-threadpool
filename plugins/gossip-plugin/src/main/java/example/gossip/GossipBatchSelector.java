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
package example.gossip;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Each runtime direction owns its own cursor; only included records advance it. */
final class GossipBatchSelector {
    private int cursor;

    synchronized List<MemberRecord> select(MembershipTable.Snapshot snapshot, int maxBatch,
                                            Predicate<List<MemberRecord>> fits) {
        var candidates = snapshot.members().stream().map(MembershipTable.MemberView::record)
                .filter(record -> !record.key().equals(snapshot.self().key())).toList();
        var selected = new ArrayList<MemberRecord>();
        if (!fits.test(selected)) { throw GossipWire.invalid("Local sender exceeds the exchange budget"); }
        if (candidates.isEmpty() || maxBatch == 1) { return List.of(); }
        int position = Math.floorMod(cursor, candidates.size());
        while (selected.size() < maxBatch - 1 && selected.size() < candidates.size()) {
            MemberRecord candidate = candidates.get(position);
            selected.add(candidate);
            if (!fits.test(selected)) {
                selected.removeLast();
                if (selected.isEmpty()) { throw GossipWire.invalid("Single member cannot fit beside the local sender"); }
                break;
            }
            position = (position + 1) % candidates.size();
        }
        cursor = position;
        return List.copyOf(selected);
    }
}
