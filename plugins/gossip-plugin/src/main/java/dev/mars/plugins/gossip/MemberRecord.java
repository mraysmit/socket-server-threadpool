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

import dev.mars.protocol.Json;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import static dev.mars.plugins.gossip.MembershipException.Reason.INVALID_RECORD;

/**
 * Owner-published data, with deeply immutable JSON metadata and exact integral fields. A final class rather than
 * a record so that the encoded size and fingerprint, which never change, are computed once instead of per check.
 */
public final class MemberRecord {
    public enum State { ALIVE, LEFT }
    public static final int MAX_METADATA_BYTES = 4096;
    public static final int MAX_METADATA_DEPTH = 16;
    // The record's fields as peers send them.
    private static final String SERVER_ID = "serverId";
    private static final String BOOT_ID = "bootId";
    private static final String HOST = "host";
    private static final String PORT = "port";
    private static final String HEARTBEAT = "heartbeat";
    private static final String STATE = "state";
    private static final String METADATA = "metadata";
    private static final Set<String> FIELDS = Set.of(SERVER_ID, BOOT_ID, HOST, PORT, HEARTBEAT, STATE, METADATA);
    /**
     * Metadata is read back through the host codec only when it holds a number at least this long. Within the
     * metadata limits, the codec's number-length limit (1,000 characters by default) is the only one reachable.
     */
    static final int READ_BACK_NUMBER_LENGTH = 100;
    /**
     * The JSON bytes of a record besides its variable-length fields: braces, quotes, commas, colons and the seven
     * field names ({"serverId":"","bootId":"","host":"","port":,"heartbeat":,"state":"","metadata":} is 81 bytes),
     * plus the boot ID, which as a canonical UUID is always 36.
     */
    private static final int FIXED_JSON_BYTES = 81 + 36;
    private final MemberKey key;
    private final String host;
    private final int port;
    private final long heartbeat;
    private final State state;
    private final Map<String, Object> metadata;
    private final int metadataBytes; // Exact UTF-8 size of the metadata as JSON, measured once when it is checked.
    private final int serializedBytes;
    // Computed lazily; racing threads compute the same value, so plain volatile publication is enough.
    private volatile String fingerprint;
    private volatile GossipConfig.Seed endpoint;
    private volatile Map<String, Object> wire;

    public MemberRecord(MemberKey key, String host, int port, long heartbeat, State state,
            Map<String, Object> metadata) {
        if (key == null || !GossipValidation.validHost(host) || port < 1 || port > 65535
                || heartbeat < 0 || state == null || metadata == null) {
            throw invalid("Member requires identity, host, port 1-65535, nonnegative heartbeat, state, and metadata");
        }
        var budget = new Budget();
        var frozen = freezeObject(metadata, 0, budget);
        metadataBytes = validateMetadata(frozen, budget.longestNumber);
        this.key = key;
        this.host = host;
        this.port = port;
        this.heartbeat = heartbeat;
        this.state = state;
        this.metadata = frozen;
        serializedBytes = jsonSize(key, host, port, heartbeat, state, metadataBytes);
    }

    /** The same record at another heartbeat and state. Its metadata is already frozen and checked, so it is shared. */
    MemberRecord with(long heartbeat, State state) {
        if (heartbeat < 0 || state == null) { throw invalid("Member requires a nonnegative heartbeat and a state"); }
        return new MemberRecord(this, heartbeat, state);
    }

    private MemberRecord(MemberRecord base, long heartbeat, State state) {
        key = base.key;
        host = base.host;
        port = base.port;
        this.heartbeat = heartbeat;
        this.state = state;
        metadata = base.metadata;
        metadataBytes = base.metadataBytes;
        serializedBytes = jsonSize(key, host, port, heartbeat, state, metadataBytes);
    }

    public MemberKey key() { return key; }
    public String host() { return host; }
    public int port() { return port; }
    public long heartbeat() { return heartbeat; }
    public State state() { return state; }
    public Map<String, Object> metadata() { return metadata; }

    @SuppressWarnings("unchecked")
    public static MemberRecord fromWire(Object value) {
        if (!(value instanceof Map<?, ?> fields) || !fields.keySet().equals(FIELDS)) {
            throw invalid("Member record requires exactly: " + FIELDS);
        }
        MemberKey key = MemberKey.fromWire(string(fields, SERVER_ID), string(fields, BOOT_ID));
        State state;
        try { state = State.valueOf(string(fields, STATE)); }
        catch (IllegalArgumentException e) { throw invalid("state must be ALIVE or LEFT"); }
        if (!(fields.get(METADATA) instanceof Map<?, ?> metadata)) { throw invalid("metadata must be an object"); }
        // The constructor copies the metadata and checks every key and value, so the cast is never trusted.
        return new MemberRecord(key, string(fields, HOST),
                (int) GossipValidation.exactLong(fields.get(PORT), PORT, 1, 65535),
                GossipValidation.exactLong(fields.get(HEARTBEAT), HEARTBEAT, 0, Long.MAX_VALUE), state,
                (Map<String, Object>) metadata);
    }

    /** The record's fields for JSON, built once: every exchange that carries the record sends the same map. */
    public Map<String, Object> toWire() {
        var fields = wire;
        if (fields == null) {
            var built = new LinkedHashMap<String, Object>();
            built.put(SERVER_ID, key.serverId());
            built.put(BOOT_ID, key.bootId().toString());
            built.put(HOST, host);
            built.put(PORT, port);
            built.put(HEARTBEAT, heartbeat);
            built.put(STATE, state.name());
            built.put(METADATA, metadata);
            fields = Collections.unmodifiableMap(built);
            wire = fields;
        }
        return fields;
    }

    /** Exact UTF-8 size of {@link #toWire()} encoded as JSON, identical standalone or inside an envelope. */
    public int serializedBytes() { return serializedBytes; }

    /** Compact retained-version comparison; metadata key order and numeric wrapper types are normalized. */
    String fingerprint() {
        String digest = fingerprint;
        if (digest == null) {
            try { digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jsonBytes(toWire()))); }
            catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
            fingerprint = digest;
        }
        return digest;
    }

    /**
     * This member as a peer endpoint, built once: selection asks for it every round, and its fields are already valid.
     */
    GossipConfig.Seed endpoint() {
        var seed = endpoint;
        if (seed == null) {
            seed = new GossipConfig.Seed(key.serverId(), host, port);
            endpoint = seed;
        }
        return seed;
    }

    boolean sameEndpoint(MemberRecord other) { return host.equals(other.host) && port == other.port; }

    @Override public boolean equals(Object other) {
        return this == other || other instanceof MemberRecord record && port == record.port
                && heartbeat == record.heartbeat
                && key.equals(record.key) && host.equals(record.host) && state == record.state
                && metadata.equals(record.metadata);
    }

    @Override public int hashCode() { return Objects.hash(key, host, port, heartbeat, state, metadata); }

    @Override public String toString() {
        return "MemberRecord[key=" + key + ", host=" + host + ", port=" + port + ", heartbeat=" + heartbeat
                + ", state=" + state + ", metadata=" + metadata + "]";
    }

    private static String string(Map<?, ?> fields, String name) {
        if (!(fields.get(name) instanceof String text)) { throw invalid(name + " must be a string"); }
        return text;
    }

    private static Map<String, Object> freezeObject(Map<?, ?> source, int depth, Budget budget) {
        requireDepth(depth);
        budget.take(2);
        var copy = new TreeMap<String, Object>();
        for (var entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String name)) { throw invalid("metadata keys must be strings"); }
            budget.take((long) name.length() + 3);
            requireUnicode(name);
            copy.put(name, freeze(entry.getValue(), depth + 1, budget));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object freeze(Object value, int depth, Budget budget) {
        requireDepth(depth);
        if (value == null) {
            budget.take(4);
            return null;
        }
        if (value instanceof String text) {
            budget.take((long) text.length() + 2);
            requireUnicode(text);
            return text;
        }
        if (value instanceof Boolean bool) {
            budget.take(bool ? 4 : 5);
            return bool;
        }
        if (value instanceof Map<?, ?> map) { return freezeObject(map, depth, budget); }
        if (value instanceof List<?> list) {
            budget.take(2);
            var copy = new ArrayList<Object>();
            for (Object item : list) {
                copy.add(freeze(item, depth + 1, budget));
            }
            return Collections.unmodifiableList(copy);
        }
        Number number;
        if (value instanceof BigInteger integer) {
            if (integer.bitLength() > MAX_METADATA_BYTES * 4) { throw invalid("metadata integer is too large"); }
            number = integer;
        } else if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long) {
            number = BigInteger.valueOf(((Number) value).longValue());
        } else if (value instanceof BigDecimal decimal) {
            number = canonicalDecimal(decimal);
        } else if (value instanceof Float || value instanceof Double) {
            double decimal = ((Number) value).doubleValue();
            if (!Double.isFinite(decimal)) { throw invalid("metadata numbers must be finite"); }
            number = canonicalDecimal(new BigDecimal(value.toString()));
        } else { throw invalid("metadata must contain only JSON values"); }
        String text = number.toString();
        budget.take(text.length());
        budget.longestNumber = Math.max(budget.longestNumber, text.length());
        return number;
    }

    private static Number canonicalDecimal(BigDecimal decimal) {
        if (decimal.precision() > MAX_METADATA_BYTES || Math.abs((long) decimal.scale()) > MAX_METADATA_BYTES) {
            throw invalid("metadata decimal precision/scale exceeds " + MAX_METADATA_BYTES);
        }
        BigDecimal stripped = decimal.stripTrailingZeros();
        if (stripped.scale() <= 0) {
            if ((long) stripped.precision() - stripped.scale() > MAX_METADATA_BYTES) {
                throw invalid("metadata integer is too large");
            }
            return stripped.toBigIntegerExact();
        }
        return stripped;
    }

    private static void requireDepth(int depth) {
        if (depth > MAX_METADATA_DEPTH) { throw invalid("metadata exceeds nesting depth " + MAX_METADATA_DEPTH); }
    }

    private static void requireUnicode(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i))) {
                    throw invalid("metadata contains an unpaired Unicode surrogate");
                }
            } else if (Character.isLowSurrogate(c)) {
                throw invalid("metadata contains an unpaired Unicode surrogate");
            }
        }
    }

    /** Checks the exact UTF-8 size and that the host codec can read the metadata back; returns the size. */
    private static int validateMetadata(Map<String, Object> metadata, int longestNumber) {
        if (metadata.isEmpty()) { return 2; } // {} needs no encoding to measure, and always reads back.
        try {
            String json = Json.write(metadata);
            int bytes = json.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_METADATA_BYTES) {
                throw invalid("metadata exceeds " + MAX_METADATA_BYTES + " UTF-8 bytes");
            }
            // Reading back is the costliest step, and only a long number can fail it within these limits.
            if (longestNumber >= READ_BACK_NUMBER_LENGTH) { Json.read(json); }
            return bytes;
        } catch (IOException e) { throw invalid("metadata cannot round trip through the host JSON codec"); }
    }

    private static byte[] jsonBytes(Object value) {
        try { return Json.write(value).getBytes(StandardCharsets.UTF_8); }
        catch (IOException e) { throw invalid("Member data could not be serialized as JSON"); }
    }

    /**
     * The record's JSON size from its field lengths alone. Every field but the metadata is ASCII that JSON never
     * escapes: a validated server ID and host, a canonical UUID, integers and an enum name. The metadata was measured
     * when it was checked.
     */
    private static int jsonSize(MemberKey key, String host, int port, long heartbeat, State state, int metadataBytes) {
        return FIXED_JSON_BYTES + key.serverId().length() + host.length() + digits(port) + digits(heartbeat)
                + state.name().length() + metadataBytes;
    }

    private static int digits(long nonnegative) {
        int digits = 1;
        for (long rest = nonnegative; rest >= 10; rest /= 10) { digits++; }
        return digits;
    }

    private static MembershipException invalid(String message) {
        return new MembershipException(INVALID_RECORD, message);
    }

    /** Cheap allocation bound before exact UTF-8 serialization (escaping can use more bytes). */
    private static final class Budget {
        private long remaining = MAX_METADATA_BYTES;
        int longestNumber;
        void take(long count) {
            remaining -= count;
            if (remaining < 0) { throw invalid("metadata exceeds " + MAX_METADATA_BYTES + " bytes"); }
        }
    }
}
