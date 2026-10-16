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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/** Per-direction fair selection. Record sizes are measured before taking the queue/cursor lock. */
final class GossipBatchSelector {
    private final ToIntFunction<MemberRecord> encodedSize;
    private final Map<MemberKey, Entry> entries = new HashMap<>();
    private final ArrayList<Entry> scan = new ArrayList<>();
    private final LinkedHashSet<Entry> departures = new LinkedHashSet<>();
    private final LinkedHashSet<Entry> changes = new LinkedHashSet<>();
    private int cursor;
    private MemberKey cursorKey; // The member the scan resumes at, whose index can shift between snapshots.
    private boolean priorityTurn = true;
    private long generation;

    /** MemberRecord computes its size once and caches it, so the selector keeps no size cache of its own. */
    GossipBatchSelector() { this(MemberRecord::serializedBytes); }

    GossipBatchSelector(ToIntFunction<MemberRecord> encodedSize) { this.encodedSize = java.util.Objects.requireNonNull(encodedSize); }

    /** Budget is the complete envelope's remaining bytes after encoding it with an empty member array. */
    List<MemberRecord> select(MembershipTable.Snapshot snapshot, int maxBatch, int memberByteBudget) {
        if (maxBatch < 1) { throw new IllegalArgumentException("Batch limit must include the sender"); }
        if (memberByteBudget < 0) { throw GossipWire.invalid("Local sender exceeds the exchange budget"); }
        var prepared = new ArrayList<Sized>(snapshot.members().size());
        for (var member : snapshot.members()) {
            var record = member.record();
            if (record.key().equals(snapshot.self().key())) { continue; }
            // A sender-only batch never needs a member's size.
            int bytes = maxBatch > 1 ? encodedSize.applyAsInt(record) : 0;
            if (maxBatch > 1 && bytes < 1) { throw new IllegalArgumentException("Encoded record size must be positive"); }
            prepared.add(new Sized(record, bytes));
        }
        return selectPrepared(prepared, maxBatch, memberByteBudget);
    }

    private synchronized List<MemberRecord> selectPrepared(List<Sized> prepared, int maxBatch, int remaining) {
        refresh(prepared);
        int limit = Math.min(maxBatch - 1, scan.size());
        if (limit == 0) { return List.of(); }
        var selected = new ArrayList<MemberRecord>(limit);
        // Resume at the member that was next, wherever it now is; if it was removed, its successor took its index.
        Entry next = cursorKey == null ? null : entries.get(cursorKey);
        int position = next != null ? next.index : Math.floorMod(cursor, scan.size());
        while (selected.size() < limit) {
            while (scan.get(position).included) { position = (position + 1) % scan.size(); }
            Entry candidate = scan.get(position);
            if (priorityTurn && (!departures.isEmpty() || !changes.isEmpty())) {
                candidate = !departures.isEmpty() ? departures.getFirst() : changes.getFirst();
            }
            long bytes = (long) candidate.sized.bytes + (selected.isEmpty() ? 0 : 1); // JSON array commas.
            if (bytes > remaining) {
                if (selected.isEmpty()) { throw GossipWire.invalid("Single member cannot fit beside the local sender"); }
                break;
            }
            selected.add(candidate.sized.record);
            remaining -= (int) bytes;
            candidate.included = true;
            departures.remove(candidate);
            changes.remove(candidate);
            priorityTurn = !priorityTurn;
            if (candidate.index == position) { position = (position + 1) % scan.size(); }
        }
        cursor = position;
        cursorKey = scan.get(position).sized.record().key();
        return List.copyOf(selected);
    }

    private void refresh(List<Sized> prepared) {
        long current = ++generation;
        scan.clear();
        for (var sized : prepared) {
            var key = sized.record.key();
            Entry entry = entries.get(key);
            boolean changed = entry == null || entry.sized.record.heartbeat() != sized.record.heartbeat()
                    || entry.sized.record.state() != sized.record.state();
            if (entry == null) { entry = new Entry(); entries.put(key, entry); }
            entry.sized = sized;
            entry.index = scan.size();
            entry.included = false;
            entry.generation = current;
            scan.add(entry);
            if (changed) {
                departures.remove(entry);
                changes.remove(entry);
                (sized.record.state() == MemberRecord.State.LEFT ? departures : changes).add(entry);
            }
        }
        var iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.generation != current) { departures.remove(entry); changes.remove(entry); iterator.remove(); }
        }
    }

    private record Sized(MemberRecord record, int bytes) { }

    private static final class Entry {
        Sized sized;
        int index;
        boolean included;
        long generation;
    }
}
