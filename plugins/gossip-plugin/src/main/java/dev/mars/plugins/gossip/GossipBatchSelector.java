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

/** Per-direction fair selection. Sizes are read under the lock, and only for the records a batch considers. */
final class GossipBatchSelector {
    private final ToIntFunction<MemberRecord> encodedSize;
    private final Map<MemberKey, Entry> entries = new HashMap<>();
    private ArrayList<Entry> scan = new ArrayList<>();
    private ArrayList<Entry> previousScan = new ArrayList<>(); // Last call's scan; the table rarely reorders.
    private final LinkedHashSet<Entry> departures = new LinkedHashSet<>();
    private final LinkedHashSet<Entry> changes = new LinkedHashSet<>();
    private int cursor;
    private MemberKey cursorKey; // The member the scan resumes at, whose index can shift between snapshots.
    private boolean priorityTurn = true;
    private long generation;

    /** MemberRecord computes its size when it is built, so reading it is cheap and the selector caches nothing. */
    GossipBatchSelector() { this(MemberRecord::serializedBytes); }

    GossipBatchSelector(ToIntFunction<MemberRecord> encodedSize) { this.encodedSize = java.util.Objects.requireNonNull(encodedSize); }

    /**
     * Records are the candidates in table order, without the local record, which travels as the sender. Budget is
     * the complete envelope's remaining bytes after encoding it with an empty member array.
     */
    synchronized List<MemberRecord> select(List<MemberRecord> records, int maxBatch, int memberByteBudget) {
        if (maxBatch < 1) { throw new IllegalArgumentException("Batch limit must include the sender"); }
        if (memberByteBudget < 0) { throw GossipWire.invalid("Local sender exceeds the exchange budget"); }
        refresh(records);
        int limit = Math.min(maxBatch - 1, scan.size());
        if (limit == 0) { return List.of(); }
        int remaining = memberByteBudget;
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
            int size = encodedSize.applyAsInt(candidate.record);
            if (size < 1) { throw new IllegalArgumentException("Encoded record size must be positive"); }
            long bytes = (long) size + (selected.isEmpty() ? 0 : 1); // JSON array commas.
            if (bytes > remaining) {
                if (selected.isEmpty()) { throw GossipWire.invalid("Single member cannot fit beside the local sender"); }
                break;
            }
            selected.add(candidate.record);
            remaining -= (int) bytes;
            candidate.included = true;
            departures.remove(candidate);
            changes.remove(candidate);
            priorityTurn = !priorityTurn;
            if (candidate.index == position) { position = (position + 1) % scan.size(); }
        }
        cursor = position;
        cursorKey = scan.get(position).record.key();
        return List.copyOf(selected);
    }

    private void refresh(List<MemberRecord> records) {
        long current = ++generation;
        var previous = scan;
        scan = previousScan;
        previousScan = previous;
        scan.clear();
        for (var record : records) {
            // Usually the entry at the same place last time, still holding this very record: then neither the
            // record nor the map needs reading. Only a changed record or a reordered table does.
            Entry before = scan.size() < previous.size() ? previous.get(scan.size()) : null;
            Entry entry = before;
            boolean changed = false;
            if (before == null || before.record != record) {
                var key = record.key();
                entry = before != null && before.record.key().equals(key) ? before : entries.get(key);
                changed = entry == null || entry.record.heartbeat() != record.heartbeat() || entry.record.state() != record.state();
                if (entry == null) { entry = new Entry(); entries.put(key, entry); }
                entry.record = record;
            }
            entry.index = scan.size();
            entry.included = false;
            entry.generation = current;
            scan.add(entry);
            if (changed) {
                departures.remove(entry);
                changes.remove(entry);
                (record.state() == MemberRecord.State.LEFT ? departures : changes).add(entry);
            }
        }
        // Table keys are distinct, so each record refreshed its own entry: only a shrunken table leaves stale ones.
        if (entries.size() == scan.size()) { return; }
        var iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.generation != current) { departures.remove(entry); changes.remove(entry); iterator.remove(); }
        }
    }

    private static final class Entry {
        MemberRecord record;
        int index;
        boolean included;
        long generation;
    }
}
