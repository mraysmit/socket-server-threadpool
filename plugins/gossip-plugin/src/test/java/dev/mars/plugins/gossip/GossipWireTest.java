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

import static org.junit.jupiter.api.Assertions.*;

class GossipWireTest {
    private final GossipWire wire = new GossipWire(GossipCommandsTest.config(32768, 4, 8));
    private final MemberRecord peer = GossipCommandsTest.member("Peer", 2);

    @ParameterizedTest @EnumSource(MembershipTable.Admission.class)
    void versionTwoDecodesEachAdmissionOutcomeAndVersionOneHasUnknownAdmission(MembershipTable.Admission admission) {
        var modern = wire.decode(wire.result(peer, List.of(), 2, admission), false);
        assertEquals(admission, modern.senderAdmission());
        assertEquals(2, modern.gossipVersion());
        var legacy = wire.decode(wire.result(peer, List.of()), false);
        assertEquals(1, legacy.gossipVersion());
        assertNull(legacy.senderAdmission(), "Legacy success must not imply admission");
    }

    @Test void missingMalformedAndUnexpectedAdmissionFieldsAreRejectedBeforeAnyMerge() {
        var valid = wire.result(peer, List.of(), 2, MembershipTable.Admission.ADMITTED);
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
        var legacyWithHint = new LinkedHashMap<>(valid);
        legacyWithHint.put("gossipVersion", 1);
        cases.add(legacyWithHint);
        var future = new LinkedHashMap<>(valid);
        future.put("gossipVersion", 3);
        cases.add(future);
        var unknown = new LinkedHashMap<>(valid);
        unknown.put("capacity", true);
        cases.add(unknown);
        for (var value : cases) { assertThrows(MembershipException.class, () -> wire.decode(value, false)); }
        for (int version : List.of(1, 2)) {
            var request = new LinkedHashMap<>(wire.parameters(peer, List.of(), version));
            assertEquals(version, wire.decode(request, true).gossipVersion());
            request.put("senderAdmission", "ADMITTED");
            assertThrows(MembershipException.class, () -> wire.decode(request, true));
        }
    }
}
