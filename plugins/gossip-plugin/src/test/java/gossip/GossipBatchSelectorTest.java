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

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GossipBatchSelectorTest {
    @Test void continuouslyChangedRecordsCannotStarveTheRotatingScan() {
        var config = GossipCommandsTest.config(32768, 32, 128);
        var table = new MembershipTable(config, GossipCommandsTest.member("Self", 1));
        for (int i = 0; i < 5; i++) { table.merge(List.of(GossipCommandsTest.member("Peer-" + i, i + 2))); }
        var selector = new GossipBatchSelector();
        var seen = new HashSet<String>();
        for (int i = 0; i < 20; i++) {
            var owner = GossipCommandsTest.member("Peer-0", 2);
            table.merge(List.of(new MemberRecord(owner.key(), owner.host(), owner.port(), i + 2, owner.state(), Map.of())));
            seen.add(selector.select(table.snapshot(), 2, batch -> true).getFirst().key().serverId());
        }
        assertEquals(5, seen.size());
    }

    @Test void terminalChangesGetPriorityAlongsideTheFairScan() {
        var config = GossipCommandsTest.config(32768, 32, 128);
        var table = new MembershipTable(config, GossipCommandsTest.member("Self", 1));
        for (int i = 0; i < 5; i++) { table.merge(List.of(GossipCommandsTest.member("Peer-" + i, i + 2))); }
        var selector = new GossipBatchSelector();
        selector.select(table.snapshot(), 2, batch -> true);
        var peer = GossipCommandsTest.member("Peer-4", 6);
        table.merge(List.of(new MemberRecord(peer.key(), peer.host(), peer.port(), peer.heartbeat(), MemberRecord.State.LEFT, Map.of())));
        var selected = new HashSet<MemberKey>();
        for (int i = 0; i < 2; i++) { selected.add(selector.select(table.snapshot(), 2, batch -> true).getFirst().key()); }
        assertTrue(selected.contains(peer.key()));
    }
}
