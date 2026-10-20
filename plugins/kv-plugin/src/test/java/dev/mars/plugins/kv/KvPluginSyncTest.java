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

import static dev.mars.plugins.kv.KvPluginApplyTest.apply;
import static dev.mars.plugins.kv.KvPluginApplyTest.record;
import static dev.mars.plugins.kv.KvPluginApplyTest.version;
import static org.junit.jupiter.api.Assertions.*;

/** The operations servers use to compare their stores, and the check that they are in the same swarm. */
class KvPluginSyncTest {
    @TempDir Path folder;

    private KvPlugin started(SwarmIdSource swarm, String... settings) throws Exception {
        var file = Files.writeString(folder.resolve("kv.properties"), String.join(System.lineSeparator(), settings) + System.lineSeparator());
        var kv = new KvPlugin(file, null, null, null, swarm);
        kv.start(new PluginContext("Server-001", 9000, 9001));
        return kv;
    }

    private KvPlugin inSwarm(String id, String... extra) throws Exception {
        var settings = new ArrayList<String>(List.of("replication=on"));
        settings.addAll(List.of(extra));
        return started(() -> id, settings.toArray(String[]::new));
    }

    static Map<String, Object> root(String swarm, String root) {
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("operation", "sync-root");
        if (swarm != null) { parameters.put("swarmId", swarm); }
        parameters.put("root", root);
        return parameters;
    }

    static Map<String, Object> row(String key, long w, String server, boolean deleted) {
        var row = new LinkedHashMap<String, Object>();
        row.put("key", key);
        row.put("version", version(w, 0, server));
        row.put("deleted", deleted);
        return row;
    }

    static Map<String, Object> digest(String swarm, String after, String upTo, Object... rows) {
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("operation", "sync-digest");
        if (swarm != null) { parameters.put("swarmId", swarm); }
        parameters.put("after", after);
        parameters.put("upTo", upTo);
        parameters.put("rows", new ArrayList<>(List.of(rows)));
        return parameters;
    }

    @Test void syncRootReportsWhetherTheRootsAreEqualAndGivesItsOwn() throws Exception {
        var kv = inSwarm("alpha");
        kv.execute(apply(record("a", "1", 5_000, "Server-002")));
        var different = kv.execute(root("alpha", "0000000000000000"));
        assertEquals(Boolean.FALSE, different.get("equal"));
        String mine = (String) different.get("root");
        assertTrue(mine.matches("[0-9a-f]{16}"), mine);
        assertEquals(Map.of("equal", true, "root", mine), kv.execute(root("alpha", mine)));
        assertEquals(List.of("equal", "root"), List.copyOf(different.keySet()));
    }

    @Test void syncDigestAnswersWithWhatItHoldsThatIsNewerAndWhatItWants() throws Exception {
        var kv = inSwarm("alpha");
        kv.execute(apply(record("mine-newer", Map.of("n", BigInteger.TWO), 9_000, "Server-002"), record("equal", "1", 5_000, "Server-002"),
                record("mine-only", "x", 5_000, "Server-002")));
        var reply = kv.execute(digest("alpha", null, null,
                row("equal", 5_000, "Server-002", false), row("mine-newer", 5_000, "Server-003", false), row("theirs-newer", 7_000, "Server-003", false)));
        assertEquals(List.of("records", "want", "continueAfter"), List.copyOf(reply.keySet()));
        @SuppressWarnings("unchecked") var records = (List<Map<String, Object>>) reply.get("records");
        assertEquals(List.of("mine-newer", "mine-only"), records.stream().map(r -> r.get("key")).toList());
        assertEquals(Map.of("n", BigInteger.TWO), records.getFirst().get("value"));
        @SuppressWarnings("unchecked") var newest = (Map<String, Object>) records.getFirst().get("version");
        assertEquals(9_000L, ((Number) newest.get("w")).longValue());
        assertEquals(0L, ((Number) newest.get("c")).longValue());
        assertEquals("Server-002", newest.get("n"));
        assertEquals(Boolean.FALSE, records.getFirst().get("deleted"));
        assertEquals(List.of("theirs-newer"), reply.get("want"));
        assertNull(reply.get("continueAfter"));
    }

    @Test void aSmallSyncBudgetCutsTheReplyOffWithAKeyToContinueAfter() throws Exception {
        var kv = inSwarm("alpha", "sync.max.bytes=300");
        for (int i = 0; i < 12; i++) { kv.execute(apply(record(String.format("k%02d", i), "x".repeat(80), 5_000 + i, "Server-002"))); }
        var reply = kv.execute(digest("alpha", null, null));
        @SuppressWarnings("unchecked") var records = (List<Map<String, Object>>) reply.get("records");
        assertTrue(records.size() < 12);
        assertEquals(records.getLast().get("key"), reply.get("continueAfter"));
    }

    @Test void syncOperationsAreRefusedWhenReplicationIsOff() throws Exception {
        var kv = started(() -> "alpha", "replication=off");
        assertThrows(InvalidParametersException.class, () -> kv.execute(root("alpha", "0000000000000000")));
        assertThrows(InvalidParametersException.class, () -> kv.execute(digest("alpha", null, null)));
    }

    @Test void aSyncRequestMustNameThisServersSwarm() throws Exception {
        var kv = inSwarm("alpha");
        var missing = assertThrows(InvalidParametersException.class, () -> kv.execute(root(null, "0000000000000000")));
        assertTrue(missing.getMessage().toLowerCase().contains("swarm"), missing.getMessage());
        var wrong = assertThrows(InvalidParametersException.class, () -> kv.execute(root("beta", "0000000000000000")));
        assertTrue(wrong.getMessage().toLowerCase().contains("swarm"), wrong.getMessage());
        assertThrows(InvalidParametersException.class, () -> kv.execute(digest("beta", null, null)));
        assertThrows(InvalidParametersException.class, () -> kv.execute(digest(null, null, null)));
    }

    @Test void applyChecksTheSwarmWhenItIsGivenAndStillAcceptsNoneAsBefore() throws Exception {
        var kv = inSwarm("alpha");
        var withSwarm = new HashMap<>(apply(record("a", "1", 5_000, "Server-002")));
        withSwarm.put("swarmId", "alpha");
        assertEquals(1, kv.execute(withSwarm).get("applied"));
        var wrong = new HashMap<>(apply(record("b", "1", 5_000, "Server-002")));
        wrong.put("swarmId", "beta");
        var refused = assertThrows(InvalidParametersException.class, () -> kv.execute(wrong));
        assertTrue(refused.getMessage().toLowerCase().contains("swarm"), refused.getMessage());
        assertEquals(1, kv.execute(apply(record("c", "1", 5_000, "Server-002"))).get("applied"), "No swarm ID: accepted, for manual use");
        assertEquals(Map.of("key", "b", "found", false), kv.execute(Map.of("operation", "get", "key", "b")));
    }

    @Test void whenThisServerCannotSayWhichSwarmItIsInARequestNamingOneIsRefused() throws Exception {
        SwarmIdSource gossipDown = () -> { throw new IOException("gossip is not answering"); };
        var kv = started(gossipDown, "replication=on");
        var refused = assertThrows(InvalidParametersException.class, () -> kv.execute(root("alpha", "0000000000000000")));
        assertTrue(refused.getMessage().toLowerCase().contains("swarm"), refused.getMessage());
    }

    static Stream<Map<String, Object>> malformedDigests() {
        var good = digest("alpha", null, null, row("a", 5_000, "Server-002", false));
        var cases = new ArrayList<Map<String, Object>>();
        cases.add(without(good, "rows"));
        cases.add(without(good, "after"));
        cases.add(without(good, "upTo"));
        cases.add(with(good, "rows", "not a list"));
        cases.add(with(good, "rows", List.of("not an object")));
        cases.add(with(good, "after", BigInteger.ONE));
        cases.add(with(good, "upTo", List.of()));
        cases.add(with(good, "extra", 1));
        cases.add(with(good, "rows", List.of(without(row("a", 5_000, "Server-002", false), "key"))));
        cases.add(with(good, "rows", List.of(without(row("a", 5_000, "Server-002", false), "version"))));
        cases.add(with(good, "rows", List.of(with(row("a", 5_000, "Server-002", false), "version", "soon"))));
        cases.add(with(good, "rows", List.of(with(row("a", 5_000, "Server-002", false), "deleted", "yes"))));
        cases.add(with(good, "rows", List.of(with(row("a", 5_000, "Server-002", false), "value", "not part of a digest"))));
        cases.add(with(good, "rows", List.of(with(row("a", 5_000, "Server-002", false), "key", ""))));
        cases.add(with(good, "rows", List.of(row("b", 5_000, "Server-002", false), row("a", 5_000, "Server-002", false))));
        cases.add(with(good, "rows", List.of(row("a", 5_000, "Server-002", false), row("a", 6_000, "Server-002", false))));
        return cases.stream();
    }

    private static Map<String, Object> without(Map<String, Object> map, String field) {
        var copy = new LinkedHashMap<>(map);
        copy.remove(field);
        return copy;
    }

    private static Map<String, Object> with(Map<String, Object> map, String field, Object value) {
        var copy = new LinkedHashMap<>(map);
        copy.put(field, value);
        return copy;
    }

    @ParameterizedTest
    @MethodSource("malformedDigests")
    void aMalformedDigestRequestIsRefusedWithAReason(Map<String, Object> parameters) throws Exception {
        var kv = inSwarm("alpha");
        var refused = assertThrows(InvalidParametersException.class, () -> kv.execute(parameters));
        assertNotNull(refused.getMessage());
        assertFalse(refused.getMessage().startsWith("Supported operations"), "It should be refused for what is wrong with it: " + refused.getMessage());
    }

    @Test void aMalformedRootRequestIsRefused() throws Exception {
        var kv = inSwarm("alpha");
        assertThrows(InvalidParametersException.class, () -> kv.execute(without(root("alpha", "x"), "root")));
        assertThrows(InvalidParametersException.class, () -> kv.execute(with(root("alpha", "x"), "root", BigInteger.ONE)));
        assertThrows(InvalidParametersException.class, () -> kv.execute(with(root("alpha", "x"), "extra", 1)));
    }
}
