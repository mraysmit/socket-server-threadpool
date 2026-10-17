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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Chooses which member records travel in one gossip message. Each direction has its own selector, the transport's for
 * the requests it sends and the command handler's for the replies it returns, so the two make progress independently.
 *
 * A message carries the sender's own record plus at most {@code maxBatch - 1} members, and the members must fit in the
 * bytes the envelope leaves. The table usually holds more than that, so each message carries a share, picked one
 * member at a time by two rules that take turns.
 *
 * Priority: members whose record changed since they were last sent, so that news spreads quickly. Departures
 * ({@code LEFT}) go before other changes, and each queue is served oldest first.
 *
 * Rotation: every member, least recently sent first, so that each is resent regularly and a peer that missed an
 * update gets it again. Sending a member by either rule moves it to the back.
 *
 * Taking turns means continuous changes cannot starve the rotation, and the rotation cannot hold changes back. The
 * turn carries over from one message to the next, so even one-member messages alternate. A member that does not fit
 * ends the batch and keeps its place.
 *
 * Each call takes three explicit steps under the selector's lock. The tracker reports how the table's snapshot differs
 * from the last one; the queues take in that difference; and the queues choose the batch, which is then recorded as
 * sent. Sizes are read only for the members a batch considers.
 */
final class GossipBatchSelector {
    private final ToIntFunction<MemberRecord> encodedSize;
    private final MemberTracker tracker = new MemberTracker();
    private final BatchQueues queues = new BatchQueues();

    /** MemberRecord computes its size when it is built, so reading it is cheap and the selector caches nothing. */
    GossipBatchSelector() { this(MemberRecord::serializedBytes); }

    /** Tests supply sizes of their own, to count the reads or to make a batch fill up at a chosen point. */
    GossipBatchSelector(ToIntFunction<MemberRecord> encodedSize) { this.encodedSize = java.util.Objects.requireNonNull(encodedSize); }

    /**
     * Picks the members for one message, in the order they are to be encoded. Records are the table's records without
     * the local one, which travels as the sender; they are only read. The batch limit counts the sender as one of its
     * places. Budget is the complete envelope's remaining bytes after encoding it with an empty member array, so the
     * members' sizes, plus one comma between each pair, must not exceed it.
     */
    synchronized List<MemberRecord> select(List<MemberRecord> records, int maxBatch, int memberByteBudget) {
        if (maxBatch < 1) { throw new IllegalArgumentException("Batch limit must include the sender"); }
        if (memberByteBudget < 0) { throw GossipWire.invalid("Local sender exceeds the exchange budget"); }
        Diff diff = tracker.update(records);
        queues.apply(diff);
        Selection selection = queues.choose(maxBatch - 1, memberByteBudget, encodedSize);
        queues.sent(selection);
        return selection.batch();
    }

    /** One member's identity. It has no equals or hashCode of its own, so collections of members hash by identity. */
    static final class Member {
        private final MemberKey key;

        Member(MemberKey key) { this.key = key; }

        MemberKey key() { return key; }

        @Override public String toString() { return key.toString(); }
    }

    /** A member's record in a new snapshot. News, a new member or a new heartbeat or state, earns a priority pick. */
    record Change(Member member, MemberRecord record, boolean news) { }

    /** How a snapshot differs from the previous one. */
    record Diff(List<Change> changes, List<Member> removed) {
        static final Diff NONE = new Diff(List.of(), List.of());
    }

    /** The members chosen for one message, with their records in the same order, and whose turn comes next. */
    record Selection(List<Member> members, List<MemberRecord> batch, boolean nextTurnIsPriority) { }

    /**
     * Remembers the last snapshot it was given, and reports how each new one differs. It visits every record, but reads
     * a record's key, or the map, only when the record changed or the table was reordered.
     */
    static final class MemberTracker {
        private final Map<MemberKey, Tracked> tracked = new HashMap<>();
        // This snapshot's members in table order, and the last snapshot's; the two lists swap on every update.
        private ArrayList<Tracked> order = new ArrayList<>();
        private ArrayList<Tracked> previousOrder = new ArrayList<>();
        private long generation; // Counts updates, so that members a snapshot no longer holds can be recognised.

        Diff update(List<MemberRecord> snapshot) {
            long current = ++generation;
            var previous = order;
            order = previousOrder;
            previousOrder = previous;
            order.clear();
            var changes = new ArrayList<Change>();
            for (var record : snapshot) {
                // Usually the member at the same place last time, still holding this very record: unchanged, and found
                // without reading the record or the map.
                Tracked entry = order.size() < previous.size() ? previous.get(order.size()) : null;
                if (entry == null || entry.record != record) {
                    var key = record.key();
                    if (entry == null || !entry.record.key().equals(key)) { entry = tracked.get(key); }
                    if (entry == null) { entry = new Tracked(new Member(key)); tracked.put(key, entry); }
                    if (entry.record != record) {
                        boolean news = entry.record == null || entry.record.heartbeat() != record.heartbeat()
                                || entry.record.state() != record.state();
                        changes.add(new Change(entry.member, record, news));
                        entry.record = record;
                    }
                }
                entry.generation = current;
                order.add(entry);
            }
            // Table keys are distinct, so each record found its own entry: only a shrunken table leaves stale ones.
            var removed = new ArrayList<Member>();
            if (tracked.size() != order.size()) {
                var iterator = tracked.values().iterator();
                while (iterator.hasNext()) {
                    var entry = iterator.next();
                    if (entry.generation != current) { removed.add(entry.member); iterator.remove(); }
                }
            }
            return changes.isEmpty() && removed.isEmpty() ? Diff.NONE : new Diff(List.copyOf(changes), List.copyOf(removed));
        }

        /** The tracker's private bookkeeping for one member. */
        private static final class Tracked {
            final Member member;
            MemberRecord record; // The last record seen; null only until the first.
            long generation; // The last update whose snapshot held this member.

            Tracked(Member member) { this.member = member; }
        }
    }

    /**
     * The selection state: every member in the rotation, the members waiting for a priority pick, and whose turn it
     * is. Choosing only reads it; apply and sent change it.
     */
    static final class BatchQueues {
        // Every member with its current record, least recently sent first.
        private final LinkedHashMap<Member, MemberRecord> rotation = new LinkedHashMap<>();
        // Members waiting for a priority pick, oldest first. Each is in at most one queue, and leaves it once sent.
        private final LinkedHashSet<Member> departures = new LinkedHashSet<>();
        private final LinkedHashSet<Member> changed = new LinkedHashSet<>();
        private boolean priorityTurn = true;

        /** Takes in a snapshot's difference. A new member joins the back of the rotation, and news joins a queue. */
        void apply(Diff diff) {
            for (var change : diff.changes()) {
                var member = change.member();
                rotation.put(member, change.record()); // A member already in the rotation keeps its place.
                if (change.news()) {
                    // To the back of one queue, moving from changes to departures if it has now left.
                    departures.remove(member);
                    changed.remove(member);
                    (change.record().state() == MemberRecord.State.LEFT ? departures : changed).add(member);
                }
            }
            for (var member : diff.removed()) {
                rotation.remove(member);
                departures.remove(member);
                changed.remove(member);
            }
        }

        /**
         * Chooses the next batch, changing nothing. On a priority turn the oldest waiting departure goes, or else the
         * oldest waiting change; on the other turn, or with nothing waiting, the rotation's next member goes. Members
         * already in the batch are passed over, and the first member that does not fit ends the batch.
         */
        Selection choose(int places, int budget, ToIntFunction<MemberRecord> size) {
            int limit = Math.min(places, rotation.size());
            if (limit == 0) { return new Selection(List.of(), List.of(), priorityTurn); }
            var members = new ArrayList<Member>(limit);
            var batch = new ArrayList<MemberRecord>(limit);
            Iterator<Member> waitingDepartures = departures.iterator();
            Iterator<Member> waitingChanges = changed.iterator();
            Iterator<Map.Entry<Member, MemberRecord>> inRotation = rotation.entrySet().iterator();
            boolean priority = priorityTurn;
            int remaining = budget;
            while (batch.size() < limit) {
                Member candidate = priority ? untaken(waitingDepartures, members) : null;
                if (priority && candidate == null) { candidate = untaken(waitingChanges, members); }
                MemberRecord record;
                if (candidate != null) {
                    record = rotation.get(candidate);
                } else {
                    // The rotation always has a member left, because the batch holds fewer members than the rotation.
                    var next = inRotation.next();
                    while (members.contains(next.getKey())) { next = inRotation.next(); }
                    candidate = next.getKey();
                    record = next.getValue();
                }
                int recordSize = size.applyAsInt(record);
                if (recordSize < 1) { throw new IllegalArgumentException("Encoded record size must be positive"); }
                long bytes = (long) recordSize + (batch.isEmpty() ? 0 : 1); // JSON array commas.
                if (bytes > remaining) {
                    // The member keeps its place, since nothing is recorded and the turn does not pass. If not even the
                    // first member fits, it would block every later batch the same way, so that is an error.
                    if (batch.isEmpty()) { throw GossipWire.invalid("Single member cannot fit beside the local sender"); }
                    break;
                }
                members.add(candidate);
                batch.add(record);
                remaining -= (int) bytes;
                priority = !priority;
            }
            return new Selection(List.copyOf(members), List.copyOf(batch), priority);
        }

        /** Records a batch just chosen as sent: its members stop waiting and move to the back of the rotation. */
        void sent(Selection selection) {
            for (int i = 0; i < selection.members().size(); i++) {
                var member = selection.members().get(i);
                if (!departures.isEmpty()) { departures.remove(member); }
                if (!changed.isEmpty()) { changed.remove(member); }
                // putLast moves the member to the back without allocating. The member must still be in the rotation,
                // since nothing may change the queues between choosing a batch and recording it.
                if (rotation.putLast(member, selection.batch().get(i)) == null) {
                    rotation.remove(member);
                    throw new IllegalStateException("A selection must be recorded right after it is chosen");
                }
            }
            priorityTurn = selection.nextTurnIsPriority();
        }

        /**
         * The next member from a queue that the batch does not hold yet, or null when there is none. A batch holds at
         * most {@code max.batch.members - 1} members, so searching it is cheaper than keeping a set.
         */
        private static Member untaken(Iterator<Member> queue, List<Member> taken) {
            while (queue.hasNext()) {
                var member = queue.next();
                if (!taken.contains(member)) { return member; }
            }
            return null;
        }
    }
}
