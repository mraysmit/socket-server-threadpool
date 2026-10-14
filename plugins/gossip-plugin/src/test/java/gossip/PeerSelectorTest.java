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

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class PeerSelectorTest {
    private final AtomicLong clock = new AtomicLong();
    private final MemberRecord self = record("Self", 1, 9000);

    static MemberRecord record(String id, long boot, int port) {
        return new MemberRecord(new MemberKey(id, new UUID(0, boot)), "127.0.0.1", port, 1, MemberRecord.State.ALIVE, Map.of());
    }

    private GossipConfig config(int fanout, String seeds) {
        var properties = GossipConfigTest.minimal();
        properties.setProperty("round.millis", "100");
        properties.setProperty("fanout", Integer.toString(fanout));
        properties.setProperty("max.members", "8");
        properties.setProperty("seeds", seeds);
        return GossipConfig.fromProperties(properties, "Self");
    }

    @Test void excludesSelfAndBusyEndpointsAndKeepsFanoutDistinct() {
        var config = config(2, "Self@127.0.0.1:9000,A@127.0.0.1:9010,B@127.0.0.1:9020");
        var table = new MembershipTable(config, self, clock::get);
        var selector = new PeerSelector(config, clock::get, new Random(1));
        var selected = selector.select(table.snapshot(), Set.of(), 2);
        assertEquals(2, Set.copyOf(selected).size());
        assertTrue(selected.stream().noneMatch(peer -> peer.port() == 9000));
        assertEquals(1, selector.select(table.snapshot(), Set.of(selected.getFirst()), 2).size());
        assertTrue(selector.select(table.snapshot(), Set.of(), 0).isEmpty());
    }

    @Test void jitteredFailureBackoffIsBoundedAndSuccessResetsIt() {
        var config = config(1, "Peer@127.0.0.1:9010");
        var table = new MembershipTable(config, self, clock::get);
        var selector = new PeerSelector(config, clock::get, new Random(2));
        var peer = config.seeds().getFirst();
        selector.failure(peer);
        clock.set(TimeUnit.MILLISECONDS.toNanos(74));
        assertTrue(selector.select(table.snapshot(), Set.of(), 1).isEmpty());
        clock.set(TimeUnit.MILLISECONDS.toNanos(126));
        assertEquals(List.of(peer), selector.select(table.snapshot(), Set.of(), 1));
        for (int i = 0; i < 50; i++) { selector.failure(peer); }
        clock.addAndGet(TimeUnit.SECONDS.toNanos(30));
        assertEquals(List.of(peer), selector.select(table.snapshot(), Set.of(), 1));
        selector.success(peer);
        assertEquals(0, selector.retryCount());
        assertEquals(List.of(peer), selector.select(table.snapshot(), Set.of(), 1));
    }

    @Test void deadPeersGetReservedRecoveryProbesDespiteOrdinaryTraffic() {
        var config = config(1, "");
        var table = new MembershipTable(config, self, clock::get);
        var dead = record("Dead", 2, 9010);
        table.mergeDirect(dead, List.of());
        clock.set(TimeUnit.MILLISECONDS.toNanos(config.deadAfterMillis()));
        for (int i = 0; i < 4; i++) { table.mergeDirect(record("Alive-" + i, 3 + i, 9020 + i), List.of()); }
        var selector = new PeerSelector(config, clock::get, new Random(3));
        int probes = 0;
        var ordinary = new java.util.HashSet<String>();
        for (int i = 0; i < 30; i++) {
            var peer = selector.select(table.snapshot(), Set.of(), 1).getFirst();
            if (peer.serverId().equals("Dead")) { probes++; }
            else { ordinary.add(peer.serverId()); }
        }
        assertEquals(10, probes);
        assertTrue(ordinary.size() > 1);
    }

    @Test void jitterStaysPositiveAndWithinConfiguredRoundBounds() {
        var config = config(1, "");
        var selector = new PeerSelector(config, clock::get, new Random(4));
        for (int i = 0; i < 100; i++) {
            long initial = selector.initialDelay();
            long next = selector.roundDelay();
            assertTrue(initial > 0 && initial <= TimeUnit.MILLISECONDS.toNanos(100));
            assertTrue(next >= TimeUnit.MILLISECONDS.toNanos(80) && next <= TimeUnit.MILLISECONDS.toNanos(120));
        }
    }

    @Test void duplicateBootIdsAtOneEndpointDoNotCreateDuplicateProbeWork() {
        var config = config(2, "");
        var table = new MembershipTable(config, self, clock::get);
        table.merge(List.of(record("Peer", 2, 9010), record("Peer", 3, 9010)));
        var selector = new PeerSelector(config, clock::get, new Random(5));
        assertEquals(1, selector.select(table.snapshot(), Set.of(), 2).size());
    }
}
