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

import dev.mars.plugin.InvalidParametersException;
import dev.mars.plugin.PluginContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GossipPluginTest {
    @Test void isolatesBootIdentitiesAndRetainsStatusAfterIdempotentStop() throws Exception {
        var config = GossipConfig.fromProperties(GossipConfigTest.minimal(), "Self");
        var first = new GossipPlugin(config, false);
        var second = new GossipPlugin(config, false);
        var context = new PluginContext("Self", 9000, 9001);
        first.start(context);
        second.start(context);
        var firstStatus = status(first);
        var firstSelf = (Map<?, ?>) firstStatus.get("self");
        assertEquals("gossip", first.instruction());
        assertEquals("Self", firstSelf.get("serverId"));
        assertEquals(9000, firstSelf.get("port"));
        UUID.fromString((String) firstSelf.get("bootId"));
        assertNotEquals(firstSelf.get("bootId"), ((Map<?, ?>) status(second).get("self")).get("bootId"));
        assertEquals(List.of("exchange", "members", "status", "leave"), firstStatus.get("supportedOperations"));
        assertEquals("departure-runtime", firstStatus.get("implementationStage"));
        first.stop();
        first.stop();
        assertEquals(firstSelf, status(first).get("self"));
        assertEquals("STOPPED", status(first).get("workerState"));
        assertEquals("MANUAL", status(second).get("workerState"));
        assertThrows(IllegalStateException.class, () -> first.start(context));
        second.stop();
    }

    @Test void rejectsUnavailableOperationsAndMalformedParametersWithoutBreakingStatus() throws Exception {
        var plugin = new GossipPlugin(GossipConfig.fromProperties(GossipConfigTest.minimal(), "Self"), false);
        assertThrows(IllegalStateException.class, () -> status(plugin));
        plugin.start(new PluginContext("Self", 9000, 9001));
        try {
            for (String operation : List.of("exchange", "unknown")) {
                assertThrows(InvalidParametersException.class, () -> plugin.execute(Map.of("operation", operation)));
            }
            assertThrows(InvalidParametersException.class, () -> plugin.execute(Map.of()));
            assertThrows(InvalidParametersException.class, () -> plugin.execute(Map.of("operation", 1)));
            assertThrows(InvalidParametersException.class,
                    () -> plugin.execute(Map.of("operation", "status", "extra", true)));
            assertEquals("MANUAL", status(plugin).get("workerState"));
        } finally { plugin.stop(); }
    }

    @Test void stopBeforeStartPreventsAcquiringState() throws Exception {
        var plugin = new GossipPlugin();
        plugin.stop();
        assertThrows(IllegalStateException.class, () -> plugin.start(new PluginContext("Self", 9000, 9001)));
    }

    private static Map<?, ?> status(GossipPlugin plugin) throws Exception {
        return (Map<?, ?>) plugin.execute(Map.of("operation", "status"));
    }
}
