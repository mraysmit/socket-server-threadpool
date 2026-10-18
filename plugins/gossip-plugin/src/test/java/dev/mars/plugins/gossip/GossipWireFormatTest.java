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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The exact JSON of each gossip message, so a change to how messages are built cannot change what is sent. */
class GossipWireFormatTest {
    private final GossipConfig config = GossipCommandsTest.config(32768, 4, 8);
    private final GossipWire wire = new GossipWire(config);
    private final MemberRecord peer = GossipCommandsTest.member("Peer", 2);
    private final MemberRecord other = GossipCommandsTest.member("Other", 3);

    @Test 
    void anExchangeRequestHasExactlyTheseFields() throws Exception {
        assertSameJson(Map.of("operation", "exchange", "gossipVersion", 2, "swarmId", config.swarmId(),
                        "sender", peer.toWire(), "members", List.of(other.toWire())),
                wire.toWire(new GossipWire.ExchangeRequest(peer, List.of(other))));
    }

    @ParameterizedTest @EnumSource(MembershipTable.Admission.class)
    void anExchangeReplyHasExactlyTheseFields(MembershipTable.Admission admission) throws Exception {
        assertSameJson(Map.of("gossipVersion", 2, "swarmId", config.swarmId(), "sender", peer.toWire(),
                        "members", List.of(other.toWire()), "senderAdmission", admission.name()),
                wire.toWire(new GossipWire.ExchangeReply(peer, List.of(other), admission)));
    }

    @Test 
    void aMembersPageHasExactlyTheseFields() throws Exception {
        var view = new MembershipTable.MemberView(other, MembershipTable.Assessment.SUSPECT,
                TimeUnit.MILLISECONDS.toNanos(5), OptionalLong.of(TimeUnit.MILLISECONDS.toNanos(7)), true, false);
        var row = new LinkedHashMap<>(other.toWire());
        row.putAll(Map.of("assessment", "SUSPECT", "advanceAgeMillis", 5, "directSuccessAgeMillis", 7,
                "endpointVerified", true, "identityConflict", false));
        Map<String, Object> capacity = Map.of("activeMembers", 2, "retainedIdentities", 3, "tombstones", 1, "admissionsSkipped", 4);
        assertSameJson(Map.of("swarmId", config.swarmId(), "self", peer.toWire(), "members", List.of(row),
                        "nextCursor", "abc", "capacity", capacity),
                wire.toWire(new GossipWire.MembersPage(peer, List.of(view), Optional.of("abc"), new GossipWire.Capacity(2, 3, 1, 4))));
    }

    @Test 
    void aLastPageAndAMemberNeverReachedDirectlyAreWrittenAsNull() throws Exception {
        var view = new MembershipTable.MemberView(other, MembershipTable.Assessment.ALIVE, 0, OptionalLong.empty(),
                false, true);
        var row = new HashMap<String, Object>(other.toWire());
        row.putAll(Map.of("assessment", "ALIVE", "advanceAgeMillis", 0, "endpointVerified", false,
                "identityConflict", true));
        row.put("directSuccessAgeMillis", null);
        Map<String, Object> capacity = Map.of("activeMembers", 1, "retainedIdentities", 1, "tombstones", 0, "admissionsSkipped", 0);
        var expected = new HashMap<String, Object>(Map.of("swarmId", config.swarmId(), "self", peer.toWire(),
                "members", List.of(row), "capacity", capacity));
        expected.put("nextCursor", null);
        assertSameJson(expected, wire.toWire(new GossipWire.MembersPage(peer, List.of(view), Optional.empty(), new GossipWire.Capacity(1, 1, 0, 0))));
    }

    /** Compares parsed JSON, so field order, which a map does not fix, does not matter. */
    private static void assertSameJson(Object expected, Object actual) throws Exception {
        assertEquals(Json.read(Json.write(expected)), Json.read(Json.write(actual)));
    }
}
