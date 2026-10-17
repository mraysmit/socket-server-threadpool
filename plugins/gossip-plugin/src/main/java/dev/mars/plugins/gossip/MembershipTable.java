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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

import static dev.mars.plugins.gossip.MemberRecord.State.ALIVE;
import static dev.mars.plugins.gossip.MemberRecord.State.LEFT;
import static dev.mars.plugins.gossip.MembershipException.Reason.*;

/** Per-instance owner state and local observations. No method performs network I/O. */
public final class MembershipTable {
    public enum Assessment { CANDIDATE, ALIVE, SUSPECT, DEAD, LEFT }
    public enum Admission { ADMITTED, CAPACITY, IGNORED }

    public record MemberView(MemberRecord record, Assessment assessment, long advanceAgeNanos,
                             Long directSuccessAgeNanos, boolean endpointVerified, boolean identityConflict) { }

    public record Snapshot(MemberRecord self, List<MemberView> members, Set<String> identityConflicts,
                           int activeMembers, int retainedIdentities, int tombstones, long admissionsSkipped) {
        public Snapshot {
            members = List.copyOf(members);
            identityConflicts = Set.copyOf(identityConflicts);
        }
    }

    public record MergeResult(int admitted, int advanced, int ignored) { }
    public record ExchangeMerge(MergeResult merge, Admission senderAdmission) { }

    private final GossipConfig config;
    private final MemberKey localKey;
    private final LongSupplier clock;
    private final long suspectNanos;
    private final long deadNanos;
    private final long retentionNanos;
    private final Map<MemberKey, Entry> entries = new LinkedHashMap<>();
    private final Map<MemberKey, Suppression> suppressions = new LinkedHashMap<>();
    private long admissionsSkipped;

    public MembershipTable(GossipConfig config, MemberRecord self) { this(config, self, System::nanoTime); }

    public MembershipTable(GossipConfig config, MemberRecord self, LongSupplier clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(self, "self");
        if (self.state() != ALIVE) { throw new MembershipException(INVALID_RECORD, "Initial local record must be ALIVE"); }
        checkRecordSize(self);
        localKey = self.key();
        suspectNanos = config.suspectAfterMillis() * 1_000_000;
        deadNanos = config.deadAfterMillis() * 1_000_000;
        retentionNanos = config.terminalRetentionMillis() * 1_000_000;
        long now = clock.getAsLong();
        entries.put(localKey, new Entry(self, now, null, true, now));
    }

    /** Decode every record before applying any of them. Outer envelope validation belongs to the transport. */
    public MergeResult mergeEncoded(List<?> records) {
        checkBatchSize(records);
        var decoded = new ArrayList<MemberRecord>(records.size());
        for (Object record : records) { decoded.add(MemberRecord.fromWire(record)); }
        return apply(validateBatch(decoded), FORWARDED).merge();
    }

    /** Forwarded owner records supply activity evidence, but do not verify their advertised endpoints. */
    public MergeResult merge(List<MemberRecord> records) {
        // A private copy, so a caller changing its list cannot change the batch while it is checked and applied.
        return apply(validateBatch(records == null ? null : new ArrayList<>(records)), FORWARDED).merge();
    }

    /** Admission of the sender is captured atomically with the committed merge, not a later snapshot. */
    public ExchangeMerge mergeExchange(MemberRecord sender, List<MemberRecord> forwarded) {
        return apply(validateExchange(sender, forwarded), new FromSender(sender.key()));
    }

    /**
     * The caller must first validate correlation, swarm, and responder identity against the dialed peer.
     * Only this responder gains direct-success evidence; members carried in its batch do not.
     */
    public MergeResult mergeDirect(MemberRecord responder, List<MemberRecord> forwarded) {
        return apply(validateExchange(responder, forwarded), new FromResponder(responder)).merge();
    }

    private List<MemberRecord> validateExchange(MemberRecord responder, List<MemberRecord> forwarded) {
        if (responder == null) { throw new MembershipException(INVALID_RECORD, "Direct responder is required"); }
        if (forwarded == null) { throw new MembershipException(INVALID_RECORD, "Forwarded records are required"); }
        // The responder counts toward max.batch.members, which validateBatch checks for the whole batch.
        var records = new ArrayList<MemberRecord>(forwarded.size() + 1);
        records.add(responder);
        records.addAll(forwarded);
        return validateBatch(records);
    }

    /** Checks a batch the caller has already copied, so it is applied as it is. */
    private List<MemberRecord> validateBatch(List<MemberRecord> records) {
        checkBatchSize(records);
        var keys = new HashSet<MemberKey>();
        for (MemberRecord record : records) {
            if (record == null) { throw new MembershipException(INVALID_RECORD, "Member record must not be null"); }
            if (!keys.add(record.key())) { throw new MembershipException(INVALID_RECORD, "Duplicate member key: " + record.key()); }
            checkRecordSize(record);
        }
        return records;
    }

    private void checkBatchSize(List<?> records) {
        if (records == null || records.size() > config.maxBatchMembers()) {
            throw new MembershipException(INVALID_RECORD, "Record batch exceeds max.batch.members");
        }
    }

    private void checkRecordSize(MemberRecord record) {
        if (record.serializedBytes() > config.maxExchangeBytes()) {
            throw new MembershipException(INVALID_RECORD, "Single member exceeds max.exchange.bytes");
        }
    }

    /**
     * Merges a validated batch: decides each record against the table, commits the updates, then admits new members
     * while there is room. Deciding only reads the table and may throw on a conflict, so a rejected batch leaves the
     * table untouched without copying it. Batch keys are unique, so no key is read twice.
     */
    private synchronized ExchangeMerge apply(List<MemberRecord> records, Evidence evidence) {
        if (evidence instanceof FromResponder(var responder) && responder.key().equals(localKey)) {
            throw new MembershipException(CONFLICT, "A direct peer must not be the local process");
        }
        long now = clock.getAsLong();
        var updates = new LinkedHashMap<MemberKey, Entry>();
        var admissions = new LinkedHashMap<MemberKey, Entry>();
        int advanced = 0;
        int ignored = 0;
        for (MemberRecord incoming : records) {
            var key = incoming.key();
            switch (decide(incoming, now, evidence)) {
                case Skip _ -> ignored++;
                case Keep(var entry) -> {
                    ignored++;
                    updates.put(key, withDirectEvidence(entry, incoming, evidence, now));
                }
                case Advance(var entry) -> {
                    advanced++;
                    updates.put(key, withDirectEvidence(entry, incoming, evidence, now));
                }
                case Admit(var entry) -> admissions.put(key, withDirectEvidence(entry, incoming, evidence, now));
            }
        }
        entries.putAll(updates);
        var admitted = admit(admissions, evidence);
        admissionsSkipped += Math.min(admitted.skipped(), Long.MAX_VALUE - admissionsSkipped);
        var result = new MergeResult(admitted.count(), advanced, ignored + admitted.skipped());
        return new ExchangeMerge(result, senderAdmission(evidence, admitted.senderSkipped()));
    }

    /** What merging one record would do, judged against the table as it stands. Throws on a conflicting version. */
    private Decision decide(MemberRecord incoming, long now, Evidence evidence) {
        Entry known = entries.get(incoming.key());
        if (incoming.key().equals(localKey)) { return fromLocal(incoming, known.record()); }
        if (known != null) { return fromKnown(incoming, known, now); }
        return fromUnknown(incoming, now, evidence.isDirect(incoming.key()));
    }

    /** Peers forward copies of this process's own record, which only it may change; one that would replace it conflicts. */
    private static Decision fromLocal(MemberRecord incoming, MemberRecord local) {
        // Peers can still forward the last ALIVE version after this owner publishes LEFT.
        boolean staleAlive = local.state() == LEFT && incoming.state() == ALIVE && incoming.heartbeat() <= local.heartbeat();
        if (!staleAlive && !incoming.equals(local) && incoming.heartbeat() >= local.heartbeat()) { throw conflict(incoming.key()); }
        return SKIP;
    }

    /**
     * A departure is final. Otherwise a LEFT record or a newer heartbeat advances the member, keeping its last direct
     * success, and keeping its endpoint verified only if the endpoint is unchanged. A record at the same heartbeat must
     * match the one held.
     */
    private static Decision fromKnown(MemberRecord incoming, Entry known, long now) {
        MemberRecord previous = known.record();
        if (previous.state() == LEFT) {
            if (incoming.state() == LEFT && incoming.heartbeat() == previous.heartbeat() && !incoming.equals(previous)) {
                throw conflict(incoming.key());
            }
            return new Keep(known);
        }
        if (incoming.state() == LEFT || incoming.heartbeat() > previous.heartbeat()) {
            boolean stillVerified = known.endpointVerified() && previous.sameEndpoint(incoming);
            return new Advance(new Entry(incoming, now, known.lastDirectSuccess(), stillVerified, now));
        }
        if (incoming.heartbeat() == previous.heartbeat() && !incoming.equals(previous)) { throw conflict(incoming.key()); }
        return new Keep(known);
    }

    /**
     * A member the table does not hold. If it was removed, its tombstone keeps out a departed member and stale copies:
     * an older version, or the same version unless it comes directly from the member. Readmitting the version the
     * tombstone remembers keeps its activity time, so it does not count as fresh evidence.
     */
    private Decision fromUnknown(MemberRecord incoming, long now, boolean direct) {
        Suppression tombstone = suppressions.get(incoming.key());
        if (tombstone != null) {
            boolean sameVersion = incoming.heartbeat() == tombstone.heartbeat();
            if (sameVersion && incoming.state() == tombstone.state() && !incoming.fingerprint().equals(tombstone.fingerprint())) {
                throw conflict(incoming.key());
            }
            boolean departedForGood = tombstone.state() == LEFT;
            boolean staleCopy = incoming.state() == ALIVE
                    && (incoming.heartbeat() < tombstone.heartbeat() || (sameVersion && !direct));
            if (departedForGood || staleCopy) { return SKIP; }
        }
        boolean remembered = tombstone != null && incoming.state() == ALIVE && incoming.heartbeat() == tombstone.heartbeat();
        return new Admit(new Entry(incoming, remembered ? tombstone.lastAdvance() : now, null, false, now));
    }

    /** A peer this process dialled, still ALIVE at the endpoint it answered on, gains direct-success evidence. */
    private static Entry withDirectEvidence(Entry entry, MemberRecord incoming, Evidence evidence, long now) {
        if (!evidence.isDirect(incoming.key()) || entry.record().state() != ALIVE || !entry.record().sameEndpoint(incoming)) {
            return entry;
        }
        return new Entry(entry.record(), entry.lastAdvance(), now, true, entry.stateSince());
    }

    /**
     * Admits new keys while there is room, retained identities before new ones. A live member needs an active slot and
     * a new identity a retained-identity slot; a record that finds no room is skipped.
     */
    private Admitted admit(Map<MemberKey, Entry> admissions, Evidence evidence) {
        if (admissions.isEmpty()) { return Admitted.NONE; }
        // Only admitting new keys needs the live count, so a batch of known members skips this pass over the table.
        long active = entries.values().stream().filter(entry -> entry.record().state() == ALIVE).count();
        long identities = (long) entries.size() + suppressions.size();
        var ordered = new ArrayList<>(admissions.entrySet());
        ordered.sort(Comparator.comparing(admission -> !suppressions.containsKey(admission.getKey())));
        int count = 0;
        int skipped = 0;
        boolean senderSkipped = false;
        for (var admission : ordered) {
            boolean alive = admission.getValue().record().state() == ALIVE;
            boolean newIdentity = !suppressions.containsKey(admission.getKey());
            if ((alive && active >= config.maxMembers()) || (newIdentity && identities >= config.maxRecords())) {
                skipped++;
                senderSkipped |= evidence.isSender(admission.getKey());
                continue;
            }
            entries.put(admission.getKey(), admission.getValue());
            suppressions.remove(admission.getKey());
            count++;
            if (alive) { active++; }
            if (newIdentity) { identities++; }
        }
        return new Admitted(count, skipped, senderSkipped);
    }

    /** How an inbound exchange's sender fared: admitted if the table now holds it, otherwise skipped for capacity or ignored. */
    private Admission senderAdmission(Evidence evidence, boolean senderSkipped) {
        if (!(evidence instanceof FromSender(var sender))) { return Admission.IGNORED; }
        if (entries.containsKey(sender)) { return Admission.ADMITTED; }
        return senderSkipped ? Admission.CAPACITY : Admission.IGNORED;
    }

    /** Periodic expiry is explicit so rejected merges cannot mutate the table by pruning it. */
    public synchronized void maintain() {
        long now = clock.getAsLong();
        suppressions.entrySet().removeIf(entry -> age(now, entry.getValue().retainedAt()) >= retentionNanos);
        var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            var item = iterator.next();
            if (item.getKey().equals(localKey)) { continue; }
            Entry entry = item.getValue();
            boolean expired = entry.record().state() == LEFT
                    ? age(now, entry.stateSince()) >= retentionNanos
                    : evidenceAge(entry, now) >= retentionNanos;
            if (expired) {
                MemberRecord record = entry.record();
                suppressions.put(item.getKey(), new Suppression(record.state(), record.heartbeat(),
                        record.fingerprint(), entry.lastAdvance(), now));
                iterator.remove();
            }
        }
    }

    public synchronized MemberRecord advanceLocal() {
        MemberRecord current = entries.get(localKey).record();
        requireLocalAlive(current);
        return replaceLocal(current.with(nextHeartbeat(current), ALIVE));
    }

    public synchronized MemberRecord updateLocal(String host, int port, Map<String, Object> metadata) {
        MemberRecord current = entries.get(localKey).record();
        requireLocalAlive(current);
        MemberRecord candidate = new MemberRecord(localKey, host, port, current.heartbeat(), ALIVE, metadata);
        if (candidate.equals(current)) { return current; }
        return replaceLocal(candidate.with(nextHeartbeat(current), ALIVE));
    }

    /** Terminal departure uses the current version; terminal state dominates ALIVE even at that heartbeat. */
    public synchronized MemberRecord leaveLocal() {
        MemberRecord current = entries.get(localKey).record();
        if (current.state() == LEFT) { return current; }
        return replaceLocal(current.with(current.heartbeat(), LEFT));
    }

    private MemberRecord replaceLocal(MemberRecord record) {
        checkRecordSize(record);
        long now = clock.getAsLong();
        entries.put(localKey, new Entry(record, now, null, true, now));
        return record;
    }

    private static void requireLocalAlive(MemberRecord record) {
        if (record.state() == LEFT) { throw new IllegalStateException("A departed boot ID cannot publish ALIVE"); }
    }

    private static long nextHeartbeat(MemberRecord current) {
        if (current.heartbeat() == Long.MAX_VALUE) { throw new IllegalStateException("Owner heartbeat is exhausted"); }
        return current.heartbeat() + 1;
    }

    /** The local process identity, which never changes; no lock or copy needed. */
    public MemberKey localKey() { return localKey; }

    /** The current local owner record, without copying the table. */
    public synchronized MemberRecord self() { return entries.get(localKey).record(); }

    /** Server IDs with more than one reachable boot ID; cheaper than a snapshot when only this is needed. */
    public synchronized Set<String> identityConflicts() {
        return Set.copyOf(conflicts(assessed(clock.getAsLong())));
    }

    /** Every record but the local one, in table order: what a batch can forward, without a snapshot's assessments. */
    public synchronized List<MemberRecord> peers() {
        var peers = new ArrayList<MemberRecord>(entries.size());
        // The local entry is only ever stored under localKey itself, so an identity check is exact.
        for (var item : entries.entrySet()) {
            if (item.getKey() != localKey) { peers.add(item.getValue().record()); }
        }
        return Collections.unmodifiableList(peers);
    }

    public synchronized Snapshot snapshot() {
        long now = clock.getAsLong();
        var assessed = assessed(now);
        var conflicts = conflicts(assessed);
        var members = new ArrayList<MemberView>(assessed.size());
        int active = 0;
        for (var item : assessed) {
            Entry entry = item.entry();
            if (entry.record().state() == ALIVE) { active++; }
            members.add(new MemberView(entry.record(), item.assessment(), age(now, entry.lastAdvance()),
                    entry.lastDirectSuccess() == null ? null : age(now, entry.lastDirectSuccess()),
                    entry.endpointVerified(), conflicts.contains(entry.record().key().serverId())));
        }
        return new Snapshot(entries.get(localKey).record(), members, conflicts, active,
                entries.size() + suppressions.size(), suppressions.size(), admissionsSkipped);
    }

    /** Each entry with its assessment at {@code now}, in table order, computed once per caller. */
    private List<Assessed> assessed(long now) {
        var assessed = new ArrayList<Assessed>(entries.size());
        for (Entry entry : entries.values()) { assessed.add(new Assessed(entry, assessment(entry, now))); }
        return assessed;
    }

    private static Set<String> conflicts(List<Assessed> assessed) {
        var reachableCounts = new HashMap<String, Integer>();
        for (var item : assessed) {
            if (item.assessment() == Assessment.ALIVE) {
                reachableCounts.merge(item.entry().record().key().serverId(), 1, Integer::sum);
            }
        }
        var conflicts = new HashSet<String>();
        reachableCounts.forEach((id, count) -> { if (count > 1) { conflicts.add(id); } });
        return conflicts;
    }

    private Assessment assessment(Entry entry, long now) {
        if (entry.record().state() == LEFT) { return Assessment.LEFT; }
        if (entry.record().key().equals(localKey)) { return Assessment.ALIVE; }
        long elapsed = evidenceAge(entry, now);
        if (elapsed >= deadNanos) { return Assessment.DEAD; }
        if (elapsed >= suspectNanos) { return Assessment.SUSPECT; }
        return entry.endpointVerified() ? Assessment.ALIVE : Assessment.CANDIDATE;
    }

    private static long evidenceAge(Entry entry, long now) {
        return entry.lastDirectSuccess() == null ? age(now, entry.lastAdvance())
                : Math.min(age(now, entry.lastAdvance()), age(now, entry.lastDirectSuccess()));
    }

    /** nanoTime subtraction remains valid across signed wraparound; negative elapsed values are clamped. */
    private static long age(long now, long then) { return Math.max(0, now - then); }

    private static MembershipException conflict(MemberKey key) {
        return new MembershipException(CONFLICT, "Conflicting owner record for " + key);
    }

    private record Entry(MemberRecord record, long lastAdvance, Long lastDirectSuccess,
                         boolean endpointVerified, long stateSince) { }
    private record Suppression(MemberRecord.State state, long heartbeat, String fingerprint,
                               long lastAdvance, long retainedAt) { }
    private record Assessed(Entry entry, Assessment assessment) { }

    /** Where a batch comes from, which decides what evidence its records carry. */
    private sealed interface Evidence {
        /** Whether the record with this key is the reply of a peer this process dialled. */
        default boolean isDirect(MemberKey key) { return false; }

        /** Whether the record with this key is an inbound exchange's sender. */
        default boolean isSender(MemberKey key) { return false; }
    }

    /** Records passed on by others: evidence of activity only. */
    private record Forwarded() implements Evidence { }

    /** An inbound exchange, whose sender learns whether it was admitted. */
    private record FromSender(MemberKey sender) implements Evidence {
        @Override public boolean isSender(MemberKey key) { return sender.equals(key); }
    }

    /** A reply from a peer this process dialled and checked; only that responder gains direct-success evidence. */
    private record FromResponder(MemberRecord responder) implements Evidence {
        @Override public boolean isDirect(MemberKey key) { return responder.key().equals(key); }
    }

    private static final Evidence FORWARDED = new Forwarded();

    /** What merging one record would do. */
    private sealed interface Decision { }

    /** Nothing: a copy of this process's own record, or a removed member kept out by its tombstone. */
    private record Skip() implements Decision { }

    /** No new version. The entry held stays, though a direct reply can still refresh its evidence. */
    private record Keep(Entry entry) implements Decision { }

    /** A newer version of a member the table holds. */
    private record Advance(Entry entry) implements Decision { }

    /** A member the table does not hold, admitted if there is room. */
    private record Admit(Entry entry) implements Decision { }

    private static final Decision SKIP = new Skip();

    /** What admission did: how many it admitted and skipped, and whether it skipped the sender. */
    private record Admitted(int count, int skipped, boolean senderSkipped) {
        static final Admitted NONE = new Admitted(0, 0, false);
    }
}
