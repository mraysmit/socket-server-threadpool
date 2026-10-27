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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static dev.mars.plugins.kv.KvPluginApplyTest.apply;
import static dev.mars.plugins.kv.KvPluginApplyTest.record;
import static dev.mars.plugins.kv.KvPluginTest.delete;
import static dev.mars.plugins.kv.KvPluginTest.put;
import static org.junit.jupiter.api.Assertions.*;

class KvPluginStatusTest {
    @TempDir Path folder;

    private static final Map<String, Object> STATUS = Map.of("operation", "status");

    private KvPlugin started(String... settings) throws Exception {
        var file = Files.writeString(folder.resolve("kv.properties"), String.join(System.lineSeparator(), settings) + System.lineSeparator());
        var kv = new KvPlugin(file);
        kv.start(new PluginContext("Server-001", 9000, 9001));
        return kv;
    }

    private static Map<String, Object> counters(long pushed, long pushFailures, long applied, long ignored, long rejected) {
        return Map.of("pushed", pushed, "pushFailures", pushFailures, "applied", applied, "ignored", ignored, "rejected", rejected,
                "tooFarAhead", 0L, "announced", 0L, "fetched", 0L);
    }

    @Test void aFreshServerReportsReplicationOffAndNothingStored() throws Exception {
        var status = started("replication=off").execute(STATUS);
        assertEquals("off", status.get("replication"));
        assertEquals(0, status.get("pairs"));
        assertEquals(0, status.get("deleted"));
        assertEquals(counters(0, 0, 0, 0, 0), status.get("counters"));
    }

    @Test void statusReportsReplicationOnWhenItIsOn() throws Exception {
        assertEquals("on", started("replication=on").execute(STATUS).get("replication"));
    }

    @Test void statusCountsLivePairsAndDeletionRecords() throws Exception {
        var kv = started("replication=off");
        kv.execute(put("a", "1"));
        kv.execute(put("b", "2"));
        kv.execute(delete("a"));
        var status = kv.execute(STATUS);
        assertEquals(1, status.get("pairs"));
        assertEquals(1, status.get("deleted"));
    }

    @Test void statusAddsUpWhatApplyDidAcrossCalls() throws Exception {
        var kv = started("replication=on", "namespace.kv.max.key.length=3");
        kv.execute(apply(record("a", "1", 5_000, "Server-002"), record("b", "1", 5_001, "Server-002")));
        kv.execute(apply(record("a", "old", 4_000, "Server-003"), record("toolong", "1", 5_002, "Server-002")));
        assertEquals(counters(0, 0, 2, 1, 1), kv.execute(STATUS).get("counters"));
    }

    @Test void statusShowsTheRootOfWhatTheServerHoldsAndItChangesWithEachWrite() throws Exception {
        var kv = started("replication=off");
        String empty = (String) kv.execute(STATUS).get("root");
        assertTrue(empty.matches("[0-9a-f]{16}"), empty);
        kv.execute(put("a", "1"));
        String one = (String) kv.execute(STATUS).get("root");
        assertNotEquals(empty, one);
        assertEquals(one, kv.execute(STATUS).get("root"), "Reading does not change it");
        kv.execute(delete("a"));
        assertNotEquals(one, kv.execute(STATUS).get("root"));
    }

    @Test void statusShowsTheComparisonsSoFarAndNoneBeforeAnyHaveRun() throws Exception {
        var kv = started("replication=off");
        assertEquals(Map.of("rounds", 0L, "differences", 0L, "failures", 0L, "peers", List.of()), kv.execute(STATUS).get("sync"));
        assertEquals(List.of("rounds", "differences", "failures", "peers"), List.copyOf(((Map<?, ?>) kv.execute(STATUS).get("sync")).keySet()));
    }

    @Test void anUnknownOperationIsAnsweredWithEveryOperationThePluginSupports() throws Exception {
        var refused = assertThrows(InvalidParametersException.class, () -> started("replication=off").execute(Map.of("operation", "explode")));
        for (String operation : List.of("put", "get", "delete", "list", "apply", "status")) {
            assertTrue(refused.getMessage().contains(operation), operation + " missing from: " + refused.getMessage());
        }
    }

    @Test void statusFieldsComeInAFixedOrderAndItTakesNoParameters() throws Exception {
        var kv = started("replication=off");
        assertEquals(List.of("replication", "pairs", "deleted", "counters", "root", "sync"), List.copyOf(kv.execute(STATUS).keySet()));
        assertThrows(InvalidParametersException.class, () -> kv.execute(Map.of("operation", "status", "extra", 1)));
    }
}
