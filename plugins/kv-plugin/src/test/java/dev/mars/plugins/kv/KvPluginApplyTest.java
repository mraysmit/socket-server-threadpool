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
import dev.mars.plugin.PluginContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static dev.mars.plugins.kv.KvPluginTest.get;
import static dev.mars.plugins.kv.KvPluginTest.list;
import static org.junit.jupiter.api.Assertions.*;

/** The {@code apply} operation, which servers use to hand each other records. */
class KvPluginApplyTest {
    @TempDir Path folder;
    private KvPlugin kv;

    @BeforeEach void startWithReplicationOn() throws Exception {
        var file = Files.writeString(folder.resolve("kv.properties"), "replication=on" + System.lineSeparator());
        kv = new KvPlugin(file);
        kv.start(new PluginContext("Server-001", 9000, 9001));
    }

    static Map<String, Object> version(long w, long c, String n) {
        var version = new LinkedHashMap<String, Object>();
        version.put("w", BigInteger.valueOf(w));
        version.put("c", BigInteger.valueOf(c));
        version.put("n", n);
        return version;
    }

    static Map<String, Object> record(String key, Object value, long w, String server) {
        var record = new LinkedHashMap<String, Object>();
        record.put("key", key);
        record.put("value", value);
        record.put("version", version(w, 0, server));
        record.put("deleted", false);
        return record;
    }

    static Map<String, Object> deletion(String key, long w, String server) {
        var record = new LinkedHashMap<String, Object>();
        record.put("key", key);
        record.put("version", version(w, 0, server));
        record.put("deleted", true);
        return record;
    }

    static Map<String, Object> apply(Object... records) {
        var parameters = new HashMap<String, Object>();
        parameters.put("operation", "apply");
        parameters.put("records", new ArrayList<>(List.of(records)));
        return parameters;
    }

    @Test void applyStoresNewRecordsAndReportsTheCounts() throws Exception {
        var reply = kv.execute(apply(record("a", "1", 5_000, "Server-002"), record("b", Map.of("n", BigInteger.TWO), 5_001, "Server-002")));
        assertEquals(Map.of("applied", 2, "ignored", 0, "rejected", 0), reply);
        assertEquals(List.of("applied", "ignored", "rejected"), List.copyOf(reply.keySet()));
        assertEquals("1", ((Map<?, ?>) kv.execute(get("a"))).get("value"));
        assertEquals(Map.of("n", BigInteger.TWO), ((Map<?, ?>) kv.execute(get("b"))).get("value"));
        assertEquals(Map.of("keys", List.of("a", "b"), "count", 2), kv.execute(list()));
    }

    @Test void aStaleOrRepeatedRecordIsIgnored() throws Exception {
        kv.execute(apply(record("a", "new", 5_000, "Server-002")));
        var reply = kv.execute(apply(record("a", "old", 4_000, "Server-003"), record("a", "new", 5_000, "Server-002")));
        assertEquals(Map.of("applied", 0, "ignored", 2, "rejected", 0), reply);
        assertEquals("new", ((Map<?, ?>) kv.execute(get("a"))).get("value"));
    }

    @Test void aDeletionRecordRemovesTheKey() throws Exception {
        kv.execute(apply(record("a", "1", 5_000, "Server-002")));
        kv.execute(apply(deletion("a", 6_000, "Server-002")));
        assertEquals(Map.of("key", "a", "found", false), kv.execute(get("a")));
        assertEquals(Map.of("keys", List.of(), "count", 0), kv.execute(list()));
    }

    @Test void aRecordBeyondThisServersLimitsIsRefusedAndCountedNotAnError() throws Exception {
        var file = Files.writeString(folder.resolve("small.properties"),
                "replication=on" + System.lineSeparator() + "namespace.kv.max.key.length=3" + System.lineSeparator());
        var small = new KvPlugin(file);
        small.start(new PluginContext("Server-001", 9000, 9001));
        var reply = small.execute(apply(record("ok", "1", 5_000, "Server-002"), record("toolong", "1", 5_001, "Server-002")));
        assertEquals(Map.of("applied", 1, "ignored", 0, "rejected", 1), reply);
    }

    @Test void applyIsRefusedWhenReplicationIsOff() throws Exception {
        var plain = new KvPlugin();
        plain.start(new PluginContext("Server-001", 9000, 9001));
        var refused = assertThrows(InvalidParametersException.class, () -> plain.execute(apply(record("a", "1", 5_000, "Server-002"))));
        assertTrue(refused.getMessage().toLowerCase().contains("replication is off"), refused.getMessage());
        assertEquals(Map.of("key", "a", "found", false), plain.execute(get("a")));
    }

    static Stream<Map<String, Object>> malformedApplies() {
        var good = record("a", "1", 5_000, "Server-002");
        var cases = new ArrayList<Map<String, Object>>();
        cases.add(Map.of("operation", "apply"));
        cases.add(Map.of("operation", "apply", "records", "not a list"));
        cases.add(Map.of("operation", "apply", "records", List.of("not an object")));
        cases.add(Map.of("operation", "apply", "records", List.of(good), "extra", 1));
        cases.add(apply(without(good, "key")));
        cases.add(apply(with(good, "key", BigInteger.ONE)));
        cases.add(apply(with(good, "key", "")));
        cases.add(apply(without(good, "value")));
        cases.add(apply(without(good, "version")));
        cases.add(apply(with(good, "version", "soon")));
        cases.add(apply(with(good, "version", version(-1, 0, "Server-002"))));
        cases.add(apply(with(good, "version", version(5_000, -1, "Server-002"))));
        cases.add(apply(with(good, "version", version(5_000, 0, ""))));
        cases.add(apply(with(good, "version", Map.of("w", "5000", "c", BigInteger.ZERO, "n", "Server-002"))));
        cases.add(apply(with(good, "version", Map.of("w", BigInteger.TEN, "c", BigInteger.ZERO))));
        cases.add(apply(with(good, "version", Map.of("w", BigInteger.TEN, "c", BigInteger.ZERO, "n", "s", "extra", 1))));
        cases.add(apply(without(good, "deleted")));
        cases.add(apply(with(good, "deleted", "yes")));
        cases.add(apply(with(deletion("a", 5_000, "Server-002"), "value", "should not be here")));
        cases.add(apply(with(good, "unknown", 1)));
        return cases.stream();
    }

    private static Map<String, Object> without(Map<String, Object> record, String field) {
        var copy = new LinkedHashMap<>(record);
        copy.remove(field);
        return copy;
    }

    private static Map<String, Object> with(Map<String, Object> record, String field, Object value) {
        var copy = new LinkedHashMap<>(record);
        copy.put(field, value);
        return copy;
    }

    @ParameterizedTest
    @MethodSource("malformedApplies")
    void aMalformedApplyIsRefusedWithAReasonAndChangesNothing(Map<String, Object> parameters) throws Exception {
        var refused = assertThrows(InvalidParametersException.class, () -> kv.execute(parameters));
        assertNotNull(refused.getMessage());
        assertEquals(Map.of("keys", List.of(), "count", 0), kv.execute(list()), "A refused apply stores nothing");
    }

    @Test void aBatchWithOneBadRecordStoresNone() throws Exception {
        assertThrows(InvalidParametersException.class,
                () -> kv.execute(apply(record("a", "1", 5_000, "Server-002"), without(record("b", "2", 5_001, "Server-002"), "key"))));
        assertEquals(Map.of("keys", List.of(), "count", 0), kv.execute(list()));
    }
}
