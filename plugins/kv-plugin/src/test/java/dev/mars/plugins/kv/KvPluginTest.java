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
import dev.mars.protocol.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class KvPluginTest {
    private final KvPlugin kv = new KvPlugin();

    @BeforeEach void start() throws Exception { kv.start(new PluginContext("Server-001", 9000, 9001)); }

    @Test void itsInstructionIsKv() { assertEquals("kv", new KvPlugin().instruction()); }

    @Test void commandsAreRefusedUntilThePluginHasStarted() {
        var unstarted = new KvPlugin();
        var refused = assertThrows(IllegalStateException.class, () -> unstarted.execute(get("a")));
        assertEquals("The key-value store has not started", refused.getMessage());
    }

    @Test void commandsAreRefusedOnceThePluginHasStopped() throws Exception {
        kv.execute(put("a", "1"));
        kv.stop();
        var refused = assertThrows(IllegalStateException.class, () -> kv.execute(get("a")));
        assertEquals("The key-value store has stopped", refused.getMessage());
    }

    @Test void putCreatesAPairAndThenReplacesItsValue() throws Exception {
        assertEquals(Map.of("key", "colour", "created", true), kv.execute(put("colour", "blue")));
        assertEquals(Map.of("key", "colour", "created", false), kv.execute(put("colour", "green")));
        assertEquals(found("colour", "green"), kv.execute(get("colour")));
    }

    @Test void everyJsonTypeComesBackExactlyAsItWasStored() throws Exception {
        var nested = new LinkedHashMap<String, Object>();
        nested.put("name", "node");
        nested.put("ports", List.of(BigInteger.valueOf(9000), BigInteger.valueOf(9001)));
        nested.put("weight", new BigDecimal("0.1"));
        nested.put("missing", null);
        nested.put("tags", Map.of("live", true));
        var mixed = new ArrayList<Object>(Arrays.asList("a", BigInteger.valueOf(1), null, false, List.of(), Map.of()));
        var values = new LinkedHashMap<String, Object>();
        values.put("string", "text with \"quotes\" and ünïcode");
        values.put("integer", BigInteger.valueOf(-42));
        values.put("big integer", new BigInteger("123456789012345678901234567890"));
        values.put("decimal", new BigDecimal("3.141592653589793238462643383279"));
        values.put("true", true);
        values.put("false", false);
        values.put("null", null);
        values.put("object", nested);
        values.put("array", mixed);
        values.put("empty string", "");
        for (var entry : values.entrySet()) { kv.execute(put(entry.getKey(), entry.getValue())); }
        for (var entry : values.entrySet()) {
            var reply = assertInstanceOf(Map.class, kv.execute(get(entry.getKey())));
            assertEquals(true, reply.get("found"), entry.getKey());
            assertTrue(reply.containsKey("value"), entry.getKey());
            assertEquals(Json.write(entry.getValue()), Json.write(reply.get("value")), entry.getKey());
        }
    }

    @Test void aStoredValueIsACopyThatTheCallerCannotChangeLater() throws Exception {
        var value = new HashMap<String, Object>(Map.of("count", BigInteger.valueOf(1)));
        kv.execute(put("counter", value));
        value.put("count", BigInteger.valueOf(2));
        var stored = (Map<?, ?>) ((Map<?, ?>) kv.execute(get("counter"))).get("value");
        assertEquals(BigInteger.valueOf(1), stored.get("count"));
    }

    @Test void getOfAMissingKeyReportsItWasNotFound() throws Exception {
        assertEquals(Map.of("key", "absent", "found", false), kv.execute(get("absent")));
    }

    @Test void deleteRemovesAPairAndReportsWhetherItExisted() throws Exception {
        kv.execute(put("a", "1"));
        assertEquals(Map.of("key", "a", "deleted", true), kv.execute(delete("a")));
        assertEquals(Map.of("key", "a", "found", false), kv.execute(get("a")));
        assertEquals(Map.of("key", "a", "deleted", false), kv.execute(delete("a")));
    }

    @Test void listReturnsEveryKeyInOrder() throws Exception {
        assertEquals(Map.of("keys", List.of(), "count", 0), kv.execute(list()));
        for (String key : List.of("b", "a", "c")) { kv.execute(put(key, key)); }
        assertEquals(Map.of("keys", List.of("a", "b", "c"), "count", 3), kv.execute(list()));
    }

    @Test void replyFieldsComeInAFixedOrder() throws Exception {
        assertEquals(List.of("key", "created"), List.copyOf(kv.execute(put("a", "1")).keySet()));
        assertEquals(List.of("key", "found", "value"), List.copyOf(kv.execute(get("a")).keySet()));
        assertEquals(List.of("key", "found"), List.copyOf(kv.execute(get("absent")).keySet()));
        assertEquals(List.of("keys", "count"), List.copyOf(kv.execute(list()).keySet()));
        assertEquals(List.of("key", "deleted"), List.copyOf(kv.execute(delete("a")).keySet()));
    }

    @Test void malformedCommandsAreRejectedWithAReason() {
        var cases = new ArrayList<Map<String, Object>>();
        cases.add(Map.of());
        cases.add(Map.of("key", "a"));
        cases.add(Map.of("operation", "rename", "key", "a"));
        cases.add(Map.of("operation", 1, "key", "a"));
        cases.add(Map.of("operation", "get"));
        cases.add(Map.of("operation", "get", "key", 7));
        cases.add(Map.of("operation", "get", "key", ""));
        cases.add(Map.of("operation", "get", "key", "a", "value", "x"));
        cases.add(Map.of("operation", "put", "key", "a"));
        cases.add(Map.of("operation", "delete", "key", "a", "extra", true));
        cases.add(Map.of("operation", "list", "key", "a"));
        var nullKey = new HashMap<String, Object>(Map.of("operation", "get"));
        nullKey.put("key", null);
        cases.add(nullKey);
        for (var parameters : cases) {
            var rejected = assertThrows(InvalidParametersException.class, () -> kv.execute(parameters),
                    parameters.toString());
            assertFalse(rejected.getMessage().isBlank());
        }
    }

    @Test void keysAndValuesAreLimitedInSize() throws Exception {
        String longest = "k".repeat(KvPlugin.MAX_KEY_LENGTH);
        assertEquals(true, ((Map<?, ?>) kv.execute(put(longest, "v"))).get("created"));
        assertThrows(InvalidParametersException.class, () -> kv.execute(put(longest + "k", "v")));
        // A string value of n characters encodes as n + 2 bytes, with its quotes.
        String largest = "v".repeat(KvPlugin.MAX_VALUE_BYTES - 2);
        kv.execute(put("large", largest));
        var tooLarge = assertThrows(InvalidParametersException.class, () -> kv.execute(put("large", largest + "v")));
        assertTrue(tooLarge.getMessage().contains(String.valueOf(KvPlugin.MAX_VALUE_BYTES)), tooLarge.getMessage());
        assertEquals(found("large", largest), kv.execute(get("large")), "A rejected put leaves the old value");
        // Size is measured in UTF-8 bytes, not characters: each of these takes three bytes.
        assertThrows(InvalidParametersException.class,
                () -> kv.execute(put("wide", "€".repeat(KvPlugin.MAX_VALUE_BYTES / 3))));
    }

    @Test void aFullStoreRefusesNewKeysButStillReplacesAndDeletes() throws Exception {
        for (int i = 0; i < KvPlugin.MAX_PAIRS; i++) { kv.execute(put("key-" + i, i)); }
        var full = assertThrows(InvalidParametersException.class, () -> kv.execute(put("one-more", 1)));
        assertTrue(full.getMessage().contains(String.valueOf(KvPlugin.MAX_PAIRS)), full.getMessage());
        assertEquals(Map.of("key", "key-0", "created", false), kv.execute(put("key-0", "replaced")));
        kv.execute(delete("key-1"));
        assertEquals(Map.of("key", "one-more", "created", true), kv.execute(put("one-more", 1)));
        assertEquals(KvPlugin.MAX_PAIRS, ((Map<?, ?>) kv.execute(list())).get("count"));
    }

    @Test void concurrentPutsNeverExceedTheLimit() throws Exception {
        int attempts = KvPlugin.MAX_PAIRS + 200;
        var go = new CountDownLatch(1);
        var outcomes = new ArrayList<Future<Boolean>>();
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < attempts; i++) {
                String key = "concurrent-" + i;
                outcomes.add(threads.submit(() -> {
                    go.await();
                    try { kv.execute(put(key, key)); return true; }
                    catch (InvalidParametersException _) { return false; }
                }));
            }
            go.countDown();
            int stored = 0;
            for (var outcome : outcomes) { if (Boolean.TRUE.equals(outcome.get())) { stored++; } }
            assertEquals(KvPlugin.MAX_PAIRS, stored);
        }
        assertEquals(KvPlugin.MAX_PAIRS, ((Map<?, ?>) kv.execute(list())).get("count"));
    }

    static Map<String, Object> put(String key, Object value) {
        var parameters = new HashMap<String, Object>(Map.of("operation", "put", "key", key));
        parameters.put("value", value);
        return parameters;
    }

    static Map<String, Object> get(String key) { return Map.of("operation", "get", "key", key); }

    static Map<String, Object> delete(String key) { return Map.of("operation", "delete", "key", key); }

    static Map<String, Object> list() { return Map.of("operation", "list"); }

    private static Map<String, Object> found(String key, Object value) {
        var reply = new LinkedHashMap<String, Object>();
        reply.put("key", key);
        reply.put("found", true);
        reply.put("value", value);
        return reply;
    }
}
