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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/** The versioned pairs of one server: live values and deletion records, each under its newest version. */
final class VersionedStore {
    /** What a local write produced, and whether the key held a live value before it. */
    record Write(KvRecord record, boolean hadLive) { }

    /** What applying a batch from a peer did: records kept, and records ignored because they were not newer. */
    record Applied(int applied, int ignored, int rejected) {
        Applied(int applied, int ignored) { this(applied, ignored, 0); }
    }

    private final String serverId;
    private final LongSupplier clock;
    private final KvConfig limits;
    private final TreeMap<String, KvRecord> records = new TreeMap<>();
    /** The newest wall-clock value this server has issued, so versions it issues never repeat or go backwards. */
    private long latest;

    VersionedStore(String serverId, LongSupplier clock, KvConfig limits) {
        this.serverId = serverId;
        this.clock = clock;
        this.limits = limits;
    }

    synchronized Write put(String key, String valueJson) {
        boolean hadLive = isLive(key);
        var record = new KvRecord(key, valueJson, stamp(), false);
        records.put(key, record);
        return new Write(record, hadLive);
    }

    synchronized Write delete(String key) {
        boolean hadLive = isLive(key);
        var record = new KvRecord(key, null, stamp(), true);
        records.put(key, record);
        return new Write(record, hadLive);
    }

    /** The live record for a key, or nothing if it is absent or deleted. */
    synchronized Optional<KvRecord> get(String key) {
        return Optional.ofNullable(records.get(key)).filter(record -> !record.deleted());
    }

    /** The newest record for a key, including a deletion record. */
    synchronized Optional<KvRecord> raw(String key) { return Optional.ofNullable(records.get(key)); }

    synchronized List<String> keys() {
        return records.values().stream().filter(record -> !record.deleted()).map(KvRecord::key).toList();
    }

    synchronized int liveCount() { return (int) records.values().stream().filter(record -> !record.deleted()).count(); }

    synchronized int deletedCount() { return (int) records.values().stream().filter(KvRecord::deleted).count(); }

    /**
     * Merges records received from a peer: for each key the newer version wins, and an equal or older one is ignored. Every
     * record's time is also taken into account, so a later local write is newer than anything seen here.
     */
    synchronized Applied apply(java.util.Collection<KvRecord> received) { return apply(received, new java.util.ArrayList<>()); }

    /** As {@link #apply(java.util.Collection)}, also adding each record that was newer than what was held to {@code taken}. */
    synchronized Applied apply(java.util.Collection<KvRecord> received, java.util.List<KvRecord> taken) {
        int applied = 0, ignored = 0, rejected = 0;
        for (var record : received) {
            if (!withinLimits(record)) {
                rejected++;
                continue;
            }
            latest = Math.max(latest, record.version().w());
            var current = records.get(record.key());
            if (current == null || record.version().compareTo(current.version()) > 0) {
                records.put(record.key(), record);
                taken.add(record);
                applied++;
            } else {
                ignored++;
            }
        }
        return new Applied(applied, ignored, rejected);
    }

    /** A record from a peer is held only if it respects this server's own limits. */
    private boolean withinLimits(KvRecord record) {
        if (record.key().length() > limits.maxKeyLength()) { return false; }
        if (record.deleted()) { return true; }
        if (record.valueJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > limits.maxValueBytes()) { return false; }
        return isLive(record.key()) || liveCount() < limits.maxRecords();
    }

    /**
     * A short summary of everything this store holds, as the first 16 hex characters of a SHA-256 over every record's key,
     * version and whether it is a deletion, in key order. Two stores with the same root hold the same records. Values are
     * left out, because equal versions mean equal values. A deletion record older than the retention is left out too, so
     * servers that drop it at slightly different moments still agree.
     */
    synchronized String root() {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            long now = clock.getAsLong();
            for (var record : records.values()) {
                if (expired(record, now)) { continue; }
                var version = record.version();
                digest.update((record.key() + '\u0000' + version.w() + '\u0000' + version.c() + '\u0000' + version.n() + '\u0000'
                        + (record.deleted() ? 'D' : 'W') + '\n').getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest(), 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** One key's entry in a digest: enough to tell whether another server holds a newer record, without sending the value. */
    record DigestRow(String key, Version version, boolean deleted) {
        /** About how many bytes this row takes on the wire: its key and server name, and a fixed allowance for the rest. */
        int bytes() {
            return key.getBytes(StandardCharsets.UTF_8).length + version.n().getBytes(StandardCharsets.UTF_8).length + ROW_OVERHEAD;
        }
    }

    /** What a digest row's punctuation, field names and numbers add to its key and server name, as a round estimate. */
    private static final int ROW_OVERHEAD = 48;

    /** A run of digest rows, and whether more keys follow it. */
    record Page(List<DigestRow> rows, boolean more) { }

    /**
     * The next run of digest rows after a key (which is not included; null starts from the beginning), as many as fit the byte
     * budget but always at least one. Deletion records already past the retention are left out, as they are from the root.
     */
    synchronized Page digestPage(String after, int maxBytes) {
        long now = clock.getAsLong();
        var rows = new java.util.ArrayList<DigestRow>();
        int used = 0;
        boolean more = false;
        for (var record : after == null ? records.values() : records.tailMap(after, false).values()) {
            if (expired(record, now)) { continue; }
            var row = new DigestRow(record.key(), record.version(), record.deleted());
            if (!rows.isEmpty() && used + row.bytes() > maxBytes) {
                more = true;
                break;
            }
            rows.add(row);
            used += row.bytes();
        }
        return new Page(List.copyOf(rows), more);
    }

    /** The records, deletion records included and expired ones left out, with keys after {@code after} up to and including {@code upTo}; null bounds are open. */
    synchronized List<KvRecord> range(String after, String upTo) {
        long now = clock.getAsLong();
        var view = after == null ? records : records.tailMap(after, false);
        if (upTo != null) { view = ((java.util.NavigableMap<String, KvRecord>) view).headMap(upTo, true); }
        return view.values().stream().filter(record -> !expired(record, now)).toList();
    }

    /** Drops the deletion records older than the retention, by their own timestamps, and says how many went. A live pair is never dropped. */
    synchronized int purgeExpired() {
        long now = clock.getAsLong();
        int before = records.size();
        records.values().removeIf(record -> expired(record, now));
        return before - records.size();
    }

    private boolean expired(KvRecord record, long now) {
        return record.deleted() && now - record.version().w() > limits.tombstoneRetentionMillis();
    }

    private boolean isLive(String key) {
        var record = records.get(key);
        return record != null && !record.deleted();
    }

    private Version stamp() {
        latest = Math.max(clock.getAsLong(), latest + 1);
        return new Version(latest, 0, serverId);
    }
}
