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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

import static gossip.MemberRecord.State.ALIVE;
import static gossip.MemberRecord.State.LEFT;
import static gossip.MembershipException.Reason.*;

/** Per-instance owner state and local observations. No method performs network I/O. */
public final class MembershipTable {
    public enum Assessment { CANDIDATE, ALIVE, SUSPECT, DEAD, LEFT }

    public record MemberView(MemberRecord record, Assessment assessment, long advanceAgeNanos,
                             Long directSuccessAgeNanos, boolean endpointVerified, boolean identityConflict) { }

    public record Snapshot(MemberRecord self, List<MemberView> members, Set<String> identityConflicts,
                           int activeMembers, int retainedIdentities, int tombstones) {
        public Snapshot {
            members = List.copyOf(members);
            identityConflicts = Set.copyOf(identityConflicts);
        }
    }

    public record MergeResult(int admitted, int advanced, int ignored) { }

    private final GossipConfig config;
    private final MemberKey localKey;
    private final LongSupplier clock;
    private final long suspectNanos;
    private final long deadNanos;
    private final long retentionNanos;
    private Map<MemberKey, Entry> entries = new LinkedHashMap<>();
    private Map<MemberKey, Suppression> suppressions = new LinkedHashMap<>();

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
        var decoded = new ArrayList<MemberRecord>();
        for (Object record : records) { decoded.add(MemberRecord.fromWire(record)); }
        return merge(decoded);
    }

    /** Forwarded owner records supply activity evidence, but do not verify their advertised endpoints. */
    public MergeResult merge(List<MemberRecord> records) { return apply(validateBatch(records), null); }

    /**
     * The caller must first validate correlation, swarm, and responder identity against the dialed peer.
     * Only this responder gains direct-success evidence; members carried in its batch do not.
     */
    public MergeResult mergeDirect(MemberRecord responder, List<MemberRecord> forwarded) {
        if (responder == null) { throw new MembershipException(INVALID_RECORD, "Direct responder is required"); }
        checkBatchSize(forwarded);
        if (forwarded.size() >= config.maxBatchMembers()) {
            throw new MembershipException(INVALID_RECORD, "Sender counts toward max.batch.members");
        }
        var records = new ArrayList<MemberRecord>();
        records.add(responder);
        records.addAll(forwarded);
        return apply(validateBatch(records), responder);
    }

    private List<MemberRecord> validateBatch(List<MemberRecord> records) {
        checkBatchSize(records);
        var copy = new ArrayList<MemberRecord>();
        var keys = new HashSet<MemberKey>();
        for (MemberRecord record : records) {
            if (record == null) { throw new MembershipException(INVALID_RECORD, "Member record must not be null"); }
            if (!keys.add(record.key())) { throw new MembershipException(INVALID_RECORD, "Duplicate member key: " + record.key()); }
            checkRecordSize(record);
            copy.add(record);
        }
        return List.copyOf(copy);
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

    private synchronized MergeResult apply(List<MemberRecord> records, MemberRecord direct) {
        if (direct != null && direct.key().equals(localKey)) {
            throw new MembershipException(CONFLICT, "A direct peer must not be the local process");
        }
        long now = clock.getAsLong();
        // Plan the entire transaction against immutable entries, then publish after conflict/capacity checks.
        var proposed = new LinkedHashMap<>(entries);
        var retained = new LinkedHashMap<>(suppressions);
        int admitted = 0;
        int advanced = 0;
        int ignored = 0;
        for (MemberRecord incoming : records) {
            MemberKey key = incoming.key();
            Entry old = proposed.get(key);
            boolean directRecord = direct != null && direct.key().equals(key);
            if (key.equals(localKey)) {
                MemberRecord local = old.record();
                // Peers can still forward the last ALIVE version after this owner publishes LEFT.
                boolean staleAlive = local.state() == LEFT && incoming.state() == ALIVE
                        && incoming.heartbeat() <= local.heartbeat();
                if (!staleAlive && !incoming.equals(local) && incoming.heartbeat() >= local.heartbeat()) {
                    throw conflict(key);
                }
                ignored++;
                continue;
            }
            if (old != null) {
                MemberRecord previous = old.record();
                if (previous.state() == LEFT) {
                    if (incoming.state() == LEFT && incoming.heartbeat() == previous.heartbeat()
                            && !incoming.equals(previous)) { throw conflict(key); }
                    ignored++;
                } else if (incoming.state() == LEFT || incoming.heartbeat() > previous.heartbeat()) {
                    old = new Entry(incoming, now, old.lastDirectSuccess(),
                            old.endpointVerified() && previous.sameEndpoint(incoming), now);
                    advanced++;
                } else {
                    if (incoming.heartbeat() == previous.heartbeat() && !incoming.equals(previous)) { throw conflict(key); }
                    ignored++;
                }
            } else {
                Suppression suppressed = retained.get(key);
                if (suppressed != null) {
                    boolean sameVersion = incoming.heartbeat() == suppressed.heartbeat();
                    if (sameVersion && incoming.state() == suppressed.state()
                            && !incoming.fingerprint().equals(suppressed.fingerprint())) { throw conflict(key); }
                    if (suppressed.state() == LEFT || (incoming.state() == ALIVE
                            && (incoming.heartbeat() < suppressed.heartbeat() || (sameVersion && !directRecord)))) {
                        ignored++;
                        continue;
                    }
                }
                long lastAdvance = suppressed != null && incoming.state() == ALIVE
                        && incoming.heartbeat() == suppressed.heartbeat() ? suppressed.lastAdvance() : now;
                old = new Entry(incoming, lastAdvance, null, false, now);
                retained.remove(key);
                admitted++;
            }
            if (directRecord && old.record().state() == ALIVE && old.record().sameEndpoint(incoming)) {
                old = new Entry(old.record(), old.lastAdvance(), now, true, old.stateSince());
            }
            proposed.put(key, old);
        }
        long active = proposed.values().stream().filter(entry -> entry.record().state() == ALIVE).count();
        if (active > config.maxMembers() || (long) proposed.size() + retained.size() > config.maxRecords()) {
            throw new MembershipException(CAPACITY, "Membership admission exceeds max.members or max.records");
        }
        entries = proposed;
        suppressions = retained;
        return new MergeResult(admitted, advanced, ignored);
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
        return replaceLocal(new MemberRecord(localKey, current.host(), current.port(), nextHeartbeat(current),
                ALIVE, current.metadata()));
    }

    public synchronized MemberRecord updateLocal(String host, int port, Map<String, Object> metadata) {
        MemberRecord current = entries.get(localKey).record();
        requireLocalAlive(current);
        MemberRecord candidate = new MemberRecord(localKey, host, port, current.heartbeat(), ALIVE, metadata);
        if (candidate.equals(current)) { return current; }
        return replaceLocal(new MemberRecord(localKey, host, port, nextHeartbeat(current), ALIVE, candidate.metadata()));
    }

    /** Terminal departure uses the current version; terminal state dominates ALIVE even at that heartbeat. */
    public synchronized MemberRecord leaveLocal() {
        MemberRecord current = entries.get(localKey).record();
        if (current.state() == LEFT) { return current; }
        return replaceLocal(new MemberRecord(localKey, current.host(), current.port(), current.heartbeat(), LEFT, current.metadata()));
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

    public synchronized Snapshot snapshot() {
        long now = clock.getAsLong();
        var reachableCounts = new HashMap<String, Integer>();
        for (Entry entry : entries.values()) {
            if (assessment(entry, now) == Assessment.ALIVE) {
                reachableCounts.merge(entry.record().key().serverId(), 1, Integer::sum);
            }
        }
        var conflicts = new HashSet<String>();
        reachableCounts.forEach((id, count) -> { if (count > 1) { conflicts.add(id); } });
        var members = new ArrayList<MemberView>();
        int active = 0;
        for (Entry entry : entries.values()) {
            if (entry.record().state() == ALIVE) { active++; }
            members.add(new MemberView(entry.record(), assessment(entry, now), age(now, entry.lastAdvance()),
                    entry.lastDirectSuccess() == null ? null : age(now, entry.lastDirectSuccess()),
                    entry.endpointVerified(), conflicts.contains(entry.record().key().serverId())));
        }
        return new Snapshot(entries.get(localKey).record(), members, conflicts, active,
                entries.size() + suppressions.size(), suppressions.size());
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
}
