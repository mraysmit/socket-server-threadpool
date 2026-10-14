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

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.function.Predicate;

/** Each runtime direction owns its own cursor; only included records advance it. */
final class GossipBatchSelector {
    private int cursor;
    private boolean priorityTurn = true;
    private final Map<MemberKey, Version> seen = new HashMap<>();
    private final LinkedHashSet<MemberKey> priority = new LinkedHashSet<>();

    synchronized List<MemberRecord> select(MembershipTable.Snapshot snapshot, int maxBatch,
                                            Predicate<List<MemberRecord>> fits) {
        var candidates = snapshot.members().stream().map(MembershipTable.MemberView::record)
                .filter(record -> !record.key().equals(snapshot.self().key())).toList();
        var selected = new ArrayList<MemberRecord>();
        var indices = new HashMap<MemberKey, Integer>();
        for (int i = 0; i < candidates.size(); i++) { indices.put(candidates.get(i).key(), i); }
        seen.keySet().retainAll(indices.keySet());
        priority.retainAll(indices.keySet());
        for (var record : candidates) {
            var version = new Version(record.heartbeat(), record.state());
            if (!version.equals(seen.put(record.key(), version))) { priority.remove(record.key()); priority.add(record.key()); }
        }
        if (!fits.test(selected)) { throw GossipWire.invalid("Local sender exceeds the exchange budget"); }
        if (candidates.isEmpty() || maxBatch == 1) { return List.of(); }
        int position = Math.floorMod(cursor, candidates.size());
        var included = new HashSet<MemberKey>();
        while (selected.size() < maxBatch - 1 && selected.size() < candidates.size()) {
            while (included.contains(candidates.get(position).key())) { position = (position + 1) % candidates.size(); }
            MemberKey preferred = null;
            if (priorityTurn) {
                preferred = priority.stream().filter(key -> candidates.get(indices.get(key)).state() == MemberRecord.State.LEFT)
                        .findFirst().orElseGet(() -> priority.stream().findFirst().orElse(null));
            }
            MemberRecord candidate = preferred == null ? candidates.get(position) : candidates.get(indices.get(preferred));
            selected.add(candidate);
            if (!fits.test(selected)) {
                selected.removeLast();
                if (selected.isEmpty()) { throw GossipWire.invalid("Single member cannot fit beside the local sender"); }
                break;
            }
            included.add(candidate.key());
            priority.remove(candidate.key());
            priorityTurn = !priorityTurn;
            if (candidate.key().equals(candidates.get(position).key())) { position = (position + 1) % candidates.size(); }
        }
        cursor = position;
        return List.copyOf(selected);
    }

    private record Version(long heartbeat, MemberRecord.State state) { }
}
