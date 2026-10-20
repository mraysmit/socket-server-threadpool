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

import dev.mars.plugin.InvalidParametersException;
import dev.mars.protocol.Json;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** How records travel between servers: reading them from a request or reply, with every field checked. */
final class KvWire {
    private static final String KEY = "key";
    private static final String VALUE = "value";

    private KvWire() { }

    /** A record as the apply and sync operations carry it. The stored JSON text becomes a JSON value again. */
    static Map<String, Object> toWire(KvRecord record) throws IOException {
        var wire = new java.util.LinkedHashMap<String, Object>();
        wire.put(KEY, record.key());
        if (!record.deleted()) { wire.put(VALUE, Json.value(Json.read(record.valueJson()))); }
        var version = new java.util.LinkedHashMap<String, Object>();
        version.put("w", record.version().w());
        version.put("c", record.version().c());
        version.put("n", record.version().n());
        wire.put("version", version);
        wire.put("deleted", record.deleted());
        return wire;
    }

    /** A digest row from a sync request: a key, its version, and whether it is a deletion, and nothing else. */
    static VersionedStore.DigestRow parseRow(Object item, int position) throws InvalidParametersException {
        String where = "Row " + position;
        if (!(item instanceof Map<?, ?> fields) || !fields.keySet().equals(Set.of(KEY, "version", "deleted"))) {
            throw new InvalidParametersException(where + " must be an object with key, version and deleted");
        }
        if (!(fields.get(KEY) instanceof String key) || key.isEmpty()) { throw new InvalidParametersException(where + ": 'key' must be a non-empty string"); }
        if (!(fields.get("deleted") instanceof Boolean deleted)) { throw new InvalidParametersException(where + ": 'deleted' must be true or false"); }
        return new VersionedStore.DigestRow(key, parseVersion(fields.get("version"), where), deleted);
    }

    static List<KvRecord> parseRecords(Object records) throws InvalidParametersException {
        if (!(records instanceof List<?> items)) { throw new InvalidParametersException("Parameter 'records' must be an array"); }
        var parsed = new ArrayList<KvRecord>();
        int position = 0;
        for (var item : items) { parsed.add(parseRecord(item, ++position)); }
        return parsed;
    }

    static KvRecord parseRecord(Object item, int position) throws InvalidParametersException {
        String where = "Record " + position;
        if (!(item instanceof Map<?, ?> fields)) { throw new InvalidParametersException(where + " must be an object"); }
        for (var name : fields.keySet()) {
            if (!Set.of(KEY, VALUE, "version", "deleted").contains(name)) {
                throw new InvalidParametersException(where + " has an unknown field: " + name);
            }
        }
        if (!(fields.get(KEY) instanceof String key) || key.isEmpty()) {
            throw new InvalidParametersException(where + ": 'key' must be a non-empty string");
        }
        if (!(fields.get("deleted") instanceof Boolean deleted)) {
            throw new InvalidParametersException(where + ": 'deleted' must be true or false");
        }
        String valueJson = null;
        if (deleted) {
            if (fields.containsKey(VALUE)) { throw new InvalidParametersException(where + ": a deletion has no 'value'"); }
        } else {
            if (!fields.containsKey(VALUE)) { throw new InvalidParametersException(where + ": 'value' is missing"); }
            try { valueJson = Json.write(fields.get(VALUE)); }
            catch (IOException e) { throw new InvalidParametersException(where + ": 'value' must be a JSON value"); }
        }
        return new KvRecord(key, valueJson, parseVersion(fields.get("version"), where), deleted);
    }

    static Version parseVersion(Object version, String where) throws InvalidParametersException {
        if (!(version instanceof Map<?, ?> fields) || !fields.keySet().equals(Set.of("w", "c", "n"))) {
            throw new InvalidParametersException(where + ": 'version' must be an object with w, c and n");
        }
        if (!(fields.get("n") instanceof String server) || !server.matches("[A-Za-z0-9._-]+")) {
            throw new InvalidParametersException(where + ": version 'n' must be a server ID");
        }
        return new Version(nonNegative(fields.get("w"), where, "w"), nonNegative(fields.get("c"), where, "c"), server);
    }

    private static long nonNegative(Object number, String where, String field) throws InvalidParametersException {
        if (number instanceof Integer || number instanceof Long || number instanceof BigInteger) {
            try {
                long value = new BigInteger(number.toString()).longValueExact();
                if (value >= 0) { return value; }
            } catch (ArithmeticException tooLarge) { /* reported below */ }
        }
        throw new InvalidParametersException(where + ": version '" + field + "' must be a non-negative whole number");
    }
}
