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
    private static final Set<String> FIELDS = Set.of("serverId", "bootId", "host", "port", "heartbeat", "state", "metadata");
    private final MemberKey key;
    private final String host;
    private final int port;
    private final long heartbeat;
    private final State state;
    private final Map<String, Object> metadata;
    // Computed lazily; racing threads compute the same value, so plain volatile publication is enough.
    private volatile int serializedBytes = -1;
    private volatile String fingerprint;

    public MemberRecord(MemberKey key, String host, int port, long heartbeat, State state, Map<String, Object> metadata) {
        if (key == null || !GossipValidation.validHost(host) || port < 1 || port > 65535
                || heartbeat < 0 || state == null || metadata == null) {
            throw invalid("Member requires identity, host, port 1-65535, nonnegative heartbeat, state, and metadata");
        }
        var frozen = freezeObject(metadata, 0, new Budget());
        validateMetadata(frozen);
        this.key = key;
        this.host = host;
        this.port = port;
        this.heartbeat = heartbeat;
        this.state = state;
        this.metadata = frozen;
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
        MemberKey key = MemberKey.fromWire(string(fields, "serverId"), string(fields, "bootId"));
        State state;
        try { state = State.valueOf(string(fields, "state")); }
        catch (IllegalArgumentException e) { throw invalid("state must be ALIVE or LEFT"); }
        if (!(fields.get("metadata") instanceof Map<?, ?> metadata)) { throw invalid("metadata must be an object"); }
        // The constructor copies the metadata and checks every key and value, so the cast is never trusted.
        return new MemberRecord(key, string(fields, "host"), (int) GossipWire.exactLong(fields.get("port"), "port", 1, 65535),
                GossipWire.exactLong(fields.get("heartbeat"), "heartbeat", 0, Long.MAX_VALUE), state, (Map<String, Object>) metadata);
    }

    public Map<String, Object> toWire() {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("serverId", key.serverId());
        fields.put("bootId", key.bootId().toString());
        fields.put("host", host);
        fields.put("port", port);
        fields.put("heartbeat", heartbeat);
        fields.put("state", state.name());
        fields.put("metadata", metadata);
        return Collections.unmodifiableMap(fields);
    }

    /** Exact UTF-8 size of {@link #toWire()} encoded as JSON, identical standalone or inside an envelope. */
    public int serializedBytes() {
        int bytes = serializedBytes;
        if (bytes < 0) { bytes = jsonBytes(toWire()).length; serializedBytes = bytes; }
        return bytes;
    }

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

    boolean sameEndpoint(MemberRecord other) { return host.equals(other.host) && port == other.port; }

    @Override public boolean equals(Object other) {
        return this == other || other instanceof MemberRecord record && port == record.port && heartbeat == record.heartbeat
                && key.equals(record.key) && host.equals(record.host) && state == record.state && metadata.equals(record.metadata);
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
        if (value == null) { budget.take(4); return null; }
        if (value instanceof String text) {
            budget.take((long) text.length() + 2);
            requireUnicode(text);
            return text;
        }
        if (value instanceof Boolean bool) { budget.take(bool ? 4 : 5); return bool; }
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
        } else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            number = BigInteger.valueOf(((Number) value).longValue());
        } else if (value instanceof BigDecimal decimal) {
            number = canonicalDecimal(decimal);
        } else if (value instanceof Float || value instanceof Double) {
            double decimal = ((Number) value).doubleValue();
            if (!Double.isFinite(decimal)) { throw invalid("metadata numbers must be finite"); }
            number = canonicalDecimal(new BigDecimal(value.toString()));
        } else { throw invalid("metadata must contain only JSON values"); }
        budget.take(number.toString().length());
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

    private static void validateMetadata(Map<String, Object> metadata) {
        try {
            String json = Json.write(metadata);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_METADATA_BYTES) {
                throw invalid("metadata exceeds " + MAX_METADATA_BYTES + " UTF-8 bytes");
            }
            // Also respect the host codec's number-length and other parsing constraints.
            Json.read(json);
        } catch (IOException e) { throw invalid("metadata cannot round trip through the host JSON codec"); }
    }

    private static byte[] jsonBytes(Object value) {
        try { return Json.write(value).getBytes(StandardCharsets.UTF_8); }
        catch (IOException e) { throw invalid("Member data could not be serialized as JSON"); }
    }

    private static MembershipException invalid(String message) { return new MembershipException(INVALID_RECORD, message); }

    /** Cheap allocation bound before exact UTF-8 serialization (escaping can use more bytes). */
    private static final class Budget {
        private long remaining = MAX_METADATA_BYTES;
        void take(long count) {
            remaining -= count;
            if (remaining < 0) { throw invalid("metadata exceeds " + MAX_METADATA_BYTES + " bytes"); }
        }
    }
}
