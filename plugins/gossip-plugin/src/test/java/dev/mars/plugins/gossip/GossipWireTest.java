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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class GossipWireTest {
    private final GossipWire wire = new GossipWire(GossipCommandsTest.config(32768, 4, 8));
    private final MemberRecord peer = GossipCommandsTest.member("Peer", 2);

    @Test void aMembersPageRowReportsItsAgesInWholeMilliseconds() {
        var row = GossipWire.memberView(new MembershipTable.MemberView(peer, MembershipTable.Assessment.ALIVE,
                TimeUnit.MILLISECONDS.toNanos(5) + 999_999, OptionalLong.of(TimeUnit.MILLISECONDS.toNanos(7)), true, false));
        assertEquals(5L, row.get("advanceAgeMillis"), "Nanoseconds are truncated to whole milliseconds");
        assertEquals(7L, row.get("directSuccessAgeMillis"));
    }

    @ParameterizedTest @EnumSource(MembershipTable.Admission.class)
    void eachAdmissionOutcomeDecodesAsItWasSent(MembershipTable.Admission admission) {
        var reply = wire.decodeReply(wire.toWire(new GossipWire.ExchangeReply(peer, List.of(), admission)));
        assertEquals(admission, reply.senderAdmission());
    }

    @Test void onlyAnExchangeRequestIsDecodedAsOne() {
        var request = new LinkedHashMap<>(wire.toWire(new GossipWire.ExchangeRequest(peer, List.of())));
        request.put("operation", "members");
        assertEquals("operation must be exchange", assertThrows(MembershipException.class, () -> wire.decodeRequest(request)).getMessage());
    }

    @Test void aBatchOverTheLimitIsRefusedBeforeAnyMemberIsDecoded() {
        // With max.batch.members at 4, a sender and three members fit.
        var three = List.of(GossipCommandsTest.member("A", 3), GossipCommandsTest.member("B", 4), GossipCommandsTest.member("C", 5));
        assertEquals(3, wire.decodeRequest(wire.toWire(new GossipWire.ExchangeRequest(peer, three))).members().size());
        // Four members, none of them a record: the limit must be checked before any is decoded.
        var four = new LinkedHashMap<>(wire.toWire(new GossipWire.ExchangeRequest(peer, three)));
        four.put("members", List.of("not", "a", "member", "record"));
        assertEquals("Sender and members exceed max.batch.members",
                assertThrows(MembershipException.class, () -> wire.decodeRequest(four)).getMessage());
    }

    @Test void anExchangeThatIsNotAnObjectIsAProtocolFailure() {
        for (Object value : List.of(List.of(), "exchange", 1)) {
            assertEquals(GossipFailure.PROTOCOL, GossipFailure.of(assertThrows(MembershipException.class, () -> wire.decodeReply(value))));
        }
    }

    @Test void missingMalformedAndUnexpectedAdmissionFieldsAreRejectedBeforeAnyMerge() {
        var valid = wire.toWire(new GossipWire.ExchangeReply(peer, List.of(), MembershipTable.Admission.ADMITTED));
        var cases = new ArrayList<Map<String, Object>>();
        for (Object value : List.of(true, 1, "UNKNOWN", "admitted", Map.of("state", "ADMITTED"))) {
            var changed = new LinkedHashMap<>(valid);
            changed.put("senderAdmission", value);
            cases.add(changed);
        }
        var missing = new LinkedHashMap<>(valid);
        missing.remove("senderAdmission");
        cases.add(missing);
        var nullValue = new LinkedHashMap<>(valid);
        nullValue.put("senderAdmission", null);
        cases.add(nullValue);
        var older = new LinkedHashMap<>(valid);
        older.put("gossipVersion", 1);
        cases.add(older);
        var future = new LinkedHashMap<>(valid);
        future.put("gossipVersion", 3);
        cases.add(future);
        var unknown = new LinkedHashMap<>(valid);
        unknown.put("capacity", true);
        cases.add(unknown);
        for (var value : cases) { assertThrows(MembershipException.class, () -> wire.decodeReply(value)); }
        var request = new LinkedHashMap<>(wire.toWire(new GossipWire.ExchangeRequest(peer, List.of())));
        assertEquals(peer, wire.decodeRequest(request).sender());
        request.put("senderAdmission", "ADMITTED");
        assertThrows(MembershipException.class, () -> wire.decodeRequest(request));
    }
}
