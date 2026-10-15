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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @Test void healthySeedsUseOrdinaryFanoutWithoutCompetingForReservedRecoverySlots() {
        var config = config(1, "Healthy@127.0.0.1:9010");
        var table = new MembershipTable(config, self, clock::get);
        table.mergeDirect(record("Dead", 2, 9020), List.of());
        clock.set(TimeUnit.MILLISECONDS.toNanos(config.deadAfterMillis()));
        table.mergeDirect(record("Healthy", 3, 9010), List.of());
        var selector = new PeerSelector(config, clock::get, new Random(6));
        for (int i = 1; i <= 30; i++) {
            assertEquals(i % 3 == 0 ? "Dead" : "Healthy",
                    selector.select(table.snapshot(), Set.of(), 1).getFirst().serverId());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"127.0.0.1", "::1"})
    void verifiedHostnameAliasesShareSelectionBusyStateAndBackoffWithNumericMembers(String address) {
        var config = config(2, "Peer@peer.example.test:9010");
        var table = new MembershipTable(config, self, clock::get);
        var record = new MemberRecord(new MemberKey("Peer", new UUID(0, 2)), address, 9010, 1, MemberRecord.State.ALIVE, Map.of());
        table.mergeDirect(record, List.of());
        var selector = new PeerSelector(config, clock::get, new Random(7));
        var seed = config.seeds().getFirst();
        var numeric = new GossipConfig.Seed("Peer", address, 9010);
        selector.resolved(seed, numeric, table.snapshot());

        assertEquals(List.of(numeric), selector.select(table.snapshot(), Set.of(), 2));
        assertTrue(selector.select(table.snapshot(), Set.of(seed), 2).isEmpty());
        assertTrue(selector.select(table.snapshot(), Set.of(numeric), 2).isEmpty());
        selector.failure(seed);
        assertTrue(selector.select(table.snapshot(), Set.of(), 2).isEmpty());
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(126));
        assertEquals(List.of(numeric), selector.select(table.snapshot(), Set.of(), 2));
        selector.success(numeric);
        assertEquals(0, selector.retryCount());
        selector.failure(numeric);
        assertTrue(selector.select(table.snapshot(), Set.of(), 2).isEmpty());
        selector.success(seed);
        assertEquals(0, selector.retryCount());
    }

    @Test void verifiedAliasesExpireSoChangedDnsCanBeRetriedAndDistinctEndpointsStayDistinct() {
        var config = config(2, "Peer@peer.example.test:9010");
        var table = new MembershipTable(config, self, clock::get);
        table.mergeDirect(record("Peer", 2, 9010), List.of());
        var selector = new PeerSelector(config, clock::get, new Random(8));
        var seed = config.seeds().getFirst();
        var numeric = new GossipConfig.Seed("Peer", "127.0.0.1", 9010);
        selector.success(seed, numeric);
        assertEquals(1, selector.select(table.snapshot(), Set.of(), 2).size());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(60));
        assertTrue(selector.select(table.snapshot(), Set.of(), 2).contains(seed), "Expired aliases must allow a fresh hostname lookup");

        var moved = new MemberRecord(new MemberKey("Peer", new UUID(0, 3)), "127.0.0.2", 9010, 1, MemberRecord.State.ALIVE, Map.of());
        table.mergeDirect(moved, List.of());
        selector.success(seed, new GossipConfig.Seed("Peer", "127.0.0.2", 9010));
        var selected = selector.select(table.snapshot(), Set.of(), 2);
        assertEquals(2, selected.size());
        assertEquals(Set.of("127.0.0.1", "127.0.0.2"), selected.stream().map(GossipConfig.Seed::host).collect(java.util.stream.Collectors.toSet()));
    }

    @Test void unverifiedHostnamesAndDifferentExpectedIdentitiesAreNotSilentlyCollapsed() {
        var config = config(2, "Other@peer.example.test:9010");
        var table = new MembershipTable(config, self, clock::get);
        table.mergeDirect(record("Peer", 2, 9010), List.of());
        var selector = new PeerSelector(config, clock::get, new Random(9));
        selector.resolved(config.seeds().getFirst(), new GossipConfig.Seed("Other", "127.0.0.1", 9010), table.snapshot());
        assertEquals(2, selector.select(table.snapshot(), Set.of(), 2).size());
        selector.success(config.seeds().getFirst(), new GossipConfig.Seed("Other", "127.0.0.1", 9010));
        assertEquals(2, selector.select(table.snapshot(), Set.of(), 2).size());
    }

    @Test void forwardedUnverifiedMembersCannotEstablishHostnameAliases() {
        var config = config(2, "Peer@peer.example.test:9010");
        var table = new MembershipTable(config, self, clock::get);
        table.merge(List.of(record("Peer", 2, 9010)));
        var selector = new PeerSelector(config, clock::get, new Random(11));
        selector.resolved(config.seeds().getFirst(), new GossipConfig.Seed("Peer", "127.0.0.1", 9010), table.snapshot());
        assertEquals(2, selector.select(table.snapshot(), Set.of(), 2).size());
    }

    @Test void aFailedNumericSeedRetainsHostnameRecoveryAndMultipleAliasesDoNotDuplicateFanout() {
        var properties = GossipConfigTest.minimal();
        properties.setProperty("heartbeat.millis", "5");
        properties.setProperty("suspect.after.millis", "10");
        properties.setProperty("dead.after.millis", "30");
        properties.setProperty("terminal.retention.millis", "60");
        properties.setProperty("seeds", "Peer@one.example.test:9010,Peer@two.example.test:9010");
        var config = GossipConfig.fromProperties(properties, "Self");
        var table = new MembershipTable(config, self, clock::get);
        table.mergeDirect(record("Peer", 2, 9010), List.of());
        var selector = new PeerSelector(config, clock::get, new Random(10));
        var numeric = new GossipConfig.Seed("Peer", "127.0.0.1", 9010);
        for (var seed : config.seeds()) { selector.success(seed, numeric); }
        assertEquals(List.of(numeric), selector.select(table.snapshot(), Set.of(), 2));
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(config.deadAfterMillis()));
        var selected = selector.select(table.snapshot(), Set.of(), 2);
        assertEquals(1, selected.size());
        assertTrue(config.seeds().contains(selected.getFirst()), "Recovery must retain a hostname path for alternate DNS answers");
    }
}
