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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GossipSwarmIdTest {
    private final List<Map<String, Object>> calls = new ArrayList<>();

    @Test void itAsksGossipForItsStatusAndReturnsTheSwarmId() throws Exception {
        Gossip gossip = parameters -> { calls.add(parameters); return Map.of("swarmId", "alpha", "workerState", "RUNNING"); };
        assertEquals("alpha", new GossipSwarmId(gossip).swarmId());
        assertEquals(List.of(Map.of("operation", "status")), calls);
    }

    @Test void aGossipThatCannotAnswerIsReportedToTheCaller() {
        Gossip gossip = parameters -> { throw new IOException("Unknown instruction: gossip"); };
        var failure = assertThrows(IOException.class, () -> new GossipSwarmId(gossip).swarmId());
        assertTrue(failure.getMessage().contains("gossip"), failure.getMessage());
    }

    @Test void aStatusWithoutASwarmIdIsReportedAsABadReply() {
        for (Object bad : new Object[] {null, "", 7}) {
            var reply = new java.util.HashMap<String, Object>();
            reply.put("swarmId", bad);
            Gossip gossip = parameters -> reply;
            assertThrows(IOException.class, () -> new GossipSwarmId(gossip).swarmId(), String.valueOf(bad));
        }
        Gossip empty = parameters -> Map.of();
        assertThrows(IOException.class, () -> new GossipSwarmId(empty).swarmId());
    }
}
