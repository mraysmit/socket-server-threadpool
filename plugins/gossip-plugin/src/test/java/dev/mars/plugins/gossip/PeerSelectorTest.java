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
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static dev.mars.plugins.gossip.MembershipTable.Assessment.ALIVE;
import static dev.mars.plugins.gossip.MembershipTable.Assessment.CANDIDATE;
import static dev.mars.plugins.gossip.MembershipTable.Assessment.DEAD;
import static org.junit.jupiter.api.Assertions.*;

class PeerSelectorTest {
    private final AtomicLong clock = new AtomicLong();
    private final MemberRecord self = record("Self", 1, 9000);

    static MemberRecord record(String id, long boot, int port) { return at(id, boot, "127.0.0.1", port); }

    private static MemberRecord at(String id, long boot, String host, int port) {
        return new MemberRecord(new MemberKey(id, new UUID(0, boot)), host, port, 1, MemberRecord.State.ALIVE, Map.of());
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

    @Test void endpointsWrittenDifferentlyAreStillRecognisedAsSelfOrBusy() {
        var config = config(8, "Self@[0:0:0:0:0:0:0:1]:9000,Seed@[::1]:9010");
        var table = new MembershipTable(config, at("Self", 1, "::1", 9000), clock::get);
        // The local address written in full, busy endpoints written differently from the busy set, and neighbours
        // that share the local port at other addresses, as when every node listens on the same port.
        table.merge(List.of(at("Twin", 2, "0:0:0:0:0:0:0:1", 9000), at("Busy", 3, "0:0:0:0:0:0:0:1", 9020),
                at("Named", 4, "Node.Example", 9030), at("Free", 5, "0:0:0:0:0:0:0:1", 9040),
                at("Neighbour", 6, "::2", 9000), at("Host", 7, "node.example", 9000)));
        var busy = Set.of(new GossipConfig.Seed("Busy", "::1", 9020), new GossipConfig.Seed("Named", "node.example", 9030));
        var selected = new PeerSelector(config, clock::get, new Random(12)).select(table.snapshot(), busy, 8);
        assertEquals(Set.of("Seed", "Free", "Neighbour", "Host"),
                selected.stream().map(GossipConfig.Seed::serverId).collect(java.util.stream.Collectors.toSet()));
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
        selector.success(peer, peer);
        assertEquals(0, selector.retryCount());
        assertEquals(List.of(peer), selector.select(table.snapshot(), Set.of(), 1));
    }

    @Test void fullMembershipCannotHideAlternateSeedsAndCapacityBackoffGrowsUntilAdmissionSucceeds() {
        var properties = GossipConfigTest.minimal();
        properties.setProperty("round.millis", "100");
        properties.setProperty("max.members", "2");
        properties.setProperty("fanout", "1");
        properties.setProperty("seeds", "Full@full.test:9010,Other@127.0.0.1:9020");
        var config = GossipConfig.fromProperties(properties, "Self");
        var table = new MembershipTable(config, self, clock::get);
        var full = new GossipConfig.Seed("Full", "127.0.0.1", 9010);
        table.mergeDirect(record("Full", 2, 9010), List.of());
        var selector = new PeerSelector(config, clock::get, new Random(2));
        var seed = config.seeds().getFirst();
        selector.capacity(seed, full);
        assertEquals(List.of(config.seeds().getLast()), selector.select(table.snapshot(), Set.of(), 1));
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(126));
        assertEquals(List.of(full), selector.select(table.snapshot(), Set.of(config.seeds().getLast()), 1));
        selector.capacity(seed, full);
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(126));
        assertTrue(selector.select(table.snapshot(), Set.of(config.seeds().getLast()), 1).isEmpty(), "Second capacity hint must preserve exponential backoff");
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(125));
        assertEquals(List.of(full), selector.select(table.snapshot(), Set.of(config.seeds().getLast()), 1));
        selector.success(seed, full);
        assertEquals(0, selector.retryCount());
        assertEquals(List.of(full), selector.select(table.snapshot(), Set.of(config.seeds().getLast()), 1));
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
        selector.resolved(seed, numeric, table::snapshot);

        assertEquals(List.of(numeric), selector.select(table.snapshot(), Set.of(), 2));
        assertTrue(selector.select(table.snapshot(), Set.of(seed), 2).isEmpty());
        assertTrue(selector.select(table.snapshot(), Set.of(numeric), 2).isEmpty());
        selector.failure(seed);
        assertTrue(selector.select(table.snapshot(), Set.of(), 2).isEmpty());
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(126));
        assertEquals(List.of(numeric), selector.select(table.snapshot(), Set.of(), 2));
        selector.success(numeric, numeric);
        assertEquals(0, selector.retryCount());
        selector.failure(numeric);
        assertTrue(selector.select(table.snapshot(), Set.of(), 2).isEmpty());
        selector.success(seed, seed);
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

    /** Members at distinct endpoints, in order. */
    private static List<MemberRecord> members(String... ids) {
        var members = new ArrayList<MemberRecord>();
        for (String id : ids) { members.add(record(id, members.size() + 2, 9101 + members.size())); }
        return members;
    }

    private static MembershipTable.MemberView view(MemberRecord member, MembershipTable.Assessment assessment) {
        return new MembershipTable.MemberView(member, assessment, 0, null, assessment == ALIVE, false);
    }

    /** A snapshot of the local member followed by these, in order. */
    private MembershipTable.Snapshot snapshot(List<MembershipTable.MemberView> members) {
        var views = new ArrayList<MembershipTable.MemberView>();
        views.add(view(self, ALIVE));
        views.addAll(members);
        return new MembershipTable.Snapshot(self, views, Set.of(), views.size(), views.size(), 0, 0);
    }

    /** A snapshot in which no member is verified, so every one is a recovery peer. */
    private MembershipTable.Snapshot unverified(List<MemberRecord> records) {
        return snapshot(records.stream().map(member -> view(member, CANDIDATE)).toList());
    }

    /** The one recovery peer a single-slot selection probes. */
    private String probe(PeerSelector selector, List<MemberRecord> members) {
        return selector.select(unverified(members), Set.of(), 1).getFirst().serverId();
    }

    @Test void removingAnEarlierRecoveryPeerDoesNotMakeTheRotationSkipTheNextOne() {
        var selector = new PeerSelector(config(1, ""), clock::get, new Random(1));
        var members = members("A", "B", "C", "D", "E");
        for (String expected : List.of("A", "B", "C")) { assertEquals(expected, probe(selector, members)); }
        // D is next. Removing B shifts D down one place; the rotation must still offer D, not skip to E.
        var withoutB = new ArrayList<>(members);
        withoutB.remove(1);
        assertEquals("D", probe(selector, withoutB));
        assertEquals("E", probe(selector, withoutB));
    }

    @Test void whenThePeerDueNextLeavesTheOneWaitingLongestIsProbedNext() {
        var selector = new PeerSelector(config(1, ""), clock::get, new Random(1));
        var members = members("A", "B", "C", "D");
        assertEquals("A", probe(selector, members));
        assertEquals("B", probe(selector, members));
        // C was due next and leaves, with A. D has never been probed and B just was, so D comes first.
        var remaining = List.of(members.get(1), members.get(3));
        assertEquals("D", probe(selector, remaining));
        assertEquals("B", probe(selector, remaining));
    }

    @Test void aNewRecoveryPeerWaitsBehindEveryPeerAlreadyWaiting() {
        var selector = new PeerSelector(config(1, ""), clock::get, new Random(1));
        var members = members("A", "B", "C");
        var first = members.subList(0, 2);
        assertEquals("A", probe(selector, first));
        // C arrives after A's probe put A back in the queue, so C waits behind B and A.
        for (String expected : List.of("B", "A", "C", "B")) { assertEquals(expected, probe(selector, members)); }
    }

    @Test void aPeerThatRecoversAndThenNeedsRecoveryAgainRejoinsAtTheBack() {
        var selector = new PeerSelector(config(1, ""), clock::get, new Random(1));
        var members = members("A", "B", "D");
        assertEquals("A", probe(selector, members.subList(0, 2)));
        // A is healthy for one round, in which D first needs recovery.
        selector.select(snapshot(List.of(view(members.get(0), ALIVE), view(members.get(1), CANDIDATE), view(members.get(2), CANDIDATE))), Set.of(), 1);
        // A needs recovery again, so it waits behind D, which was already waiting.
        for (String expected : List.of("B", "D", "A")) { assertEquals(expected, probe(selector, members)); }
    }

    @Test void aSeedThatFailedByHostnameAndIsThenFoundFullKeepsGrowingItsBackoff() {
        var config = config(1, "Full@full.test:9010");
        var table = new MembershipTable(config, self, clock::get);
        table.mergeDirect(record("Full", 2, 9010), List.of());
        var selector = new PeerSelector(config, clock::get, new Random(2));
        var seed = config.seeds().getFirst();
        selector.failure(seed);
        selector.capacity(seed, new GossipConfig.Seed("Full", "127.0.0.1", 9010));
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(126));
        assertTrue(selector.select(table.snapshot(), Set.of(), 1).isEmpty(), "The capacity hint must continue the backoff the failure began");
    }

    @Test void aMemberThatHasLeftIsNeverOffered() {
        var config = config(1, "");
        var table = new MembershipTable(config, self, clock::get);
        var leaving = record("Leaving", 2, 9010);
        table.mergeDirect(leaving, List.of());
        table.merge(List.of(new MemberRecord(leaving.key(), leaving.host(), leaving.port(), leaving.heartbeat(), MemberRecord.State.LEFT, Map.of())));
        assertTrue(table.snapshot().members().stream().anyMatch(member -> member.record().state() == MemberRecord.State.LEFT));
        var selector = new PeerSelector(config, clock::get, new Random(1));
        for (int i = 0; i < 3; i++) { assertTrue(selector.select(table.snapshot(), Set.of(), 1).isEmpty()); }
    }

    @Test void aHealthyRecordWinsOverARecoveringOneAtTheSameEndpoint() {
        var selector = new PeerSelector(config(1, ""), clock::get, new Random(1));
        // Two boots of one server at one endpoint, the first unverified and the second alive, and a dead peer.
        var snapshot = snapshot(List.of(view(record("Twice", 2, 9010), CANDIDATE), view(record("Twice", 3, 9010), ALIVE),
                view(record("Dead", 4, 9020), DEAD)));
        var offered = new ArrayList<String>();
        for (int i = 0; i < 6; i++) { offered.add(selector.select(snapshot, Set.of(), 1).getFirst().serverId()); }
        // The endpoint is healthy, so it takes the ordinary slots, and the dead peer only every third, reserved one.
        assertEquals(List.of("Twice", "Twice", "Dead", "Twice", "Twice", "Dead"), offered);
    }

    @Test void retryStateIsDroppedForAPeerNoLongerOfferedUnlessItsExchangeIsStillRunning() {
        var selector = new PeerSelector(config(1, ""), clock::get, new Random(1));
        var peer = new GossipConfig.Seed("Gone", "127.0.0.1", 9010);
        selector.failure(peer);
        selector.select(unverified(List.of()), Set.of(peer), 1);
        assertEquals(1, selector.retryCount(), "A running exchange may still fail, and its backoff must then grow");
        selector.select(unverified(List.of()), Set.of(), 1);
        assertEquals(0, selector.retryCount());
    }

    @Test void neverOffersMoreThanTheFanoutHoweverManySlotsAreFree() {
        var selector = new PeerSelector(config(1, ""), clock::get, new Random(1));
        var snapshot = snapshot(members("A", "B", "C").stream().map(member -> view(member, ALIVE)).toList());
        assertEquals(1, selector.select(snapshot, Set.of(), 3).size());
    }

    @Test void aSuccessClearsBackoffKeptUnderTheResolvedAddressAfterTheAliasExpires() {
        var config = config(1, "Peer@peer.example.test:9010");
        var selector = new PeerSelector(config, clock::get, new Random(1));
        var seed = config.seeds().getFirst();
        var numeric = new GossipConfig.Seed("Peer", "127.0.0.1", 9010);
        selector.capacity(seed, numeric);
        clock.addAndGet(TimeUnit.SECONDS.toNanos(60));
        selector.success(seed, numeric);
        assertEquals(0, selector.retryCount());
    }

    @Test void aFirstFailureBacksOffForBetweenThreeQuartersOfARoundAndARoundAndAQuarter() {
        var config = config(1, "Peer@127.0.0.1:9010");
        var table = new MembershipTable(config, self, clock::get);
        var peer = config.seeds().getFirst();
        int early = 0;
        for (int seed = 0; seed < 50; seed++) {
            clock.set(0);
            var selector = new PeerSelector(config, clock::get, new Random(seed));
            selector.failure(peer);
            clock.set(TimeUnit.MILLISECONDS.toNanos(74));
            assertTrue(selector.select(table.snapshot(), Set.of(), 1).isEmpty());
            clock.set(TimeUnit.MILLISECONDS.toNanos(90));
            if (!selector.select(table.snapshot(), Set.of(), 1).isEmpty()) { early++; }
            clock.set(TimeUnit.MILLISECONDS.toNanos(126));
            assertEquals(List.of(peer), selector.select(table.snapshot(), Set.of(), 1));
        }
        assertTrue(early > 0, "Jitter must also shorten the wait, so peers that failed together are not retried together");
    }

    @Test void retryStateIsBoundedByTheMembersAndSeedsItCouldBeFor() {
        var config = config(1, "");
        var selector = new PeerSelector(config, clock::get, new Random(1));
        for (int i = 0; i < 10; i++) { selector.failure(new GossipConfig.Seed("P" + i, "127.0.0.1", 9100 + i)); }
        assertEquals(config.maxMembers(), selector.retryCount());
    }

    @Test void theLocalHostnameIsRecognisedWhateverItsCase() {
        assertTrue(PeerSelector.isSelf(at("Self", 1, "Node.Example", 9000), new GossipConfig.Seed("Self", "node.example", 9000)));
    }

    @Test void aLocalHostnameIsNotMistakenForAnotherHostOnItsPort() {
        var named = at("Self", 1, "node-a.example", 9000);
        assertFalse(PeerSelector.isSelf(named, new GossipConfig.Seed("Other", "node-b.example", 9000)));
        assertFalse(PeerSelector.isSelf(named, new GossipConfig.Seed("Other", "127.0.0.1", 9000)));
    }

    @Test void aFailureAfterAnAliasExpiresIsChargedToTheHostnameNotTheOldAddress() {
        var config = config(2, "Peer@peer.example.test:9010");
        var table = new MembershipTable(config, self, clock::get);
        table.mergeDirect(record("Peer", 2, 9010), List.of());
        var selector = new PeerSelector(config, clock::get, new Random(8));
        var seed = config.seeds().getFirst();
        var numeric = new GossipConfig.Seed("Peer", "127.0.0.1", 9010);
        selector.success(seed, numeric);
        clock.addAndGet(TimeUnit.SECONDS.toNanos(60));
        selector.failure(seed); // A fresh lookup of the hostname failed; the member at the old address is unaffected.
        assertEquals(List.of(numeric), selector.select(table.snapshot(), Set.of(), 2));
    }

    @Test void aSuccessfulExchangeGivesOnlyAConfiguredSeedAnAlias() {
        var config = config(2, "");
        var table = new MembershipTable(config, self, clock::get);
        table.mergeDirect(record("Peer", 2, 9010), List.of());
        table.merge(List.of(at("Peer", 3, "peer.example.test", 9010)));
        var selector = new PeerSelector(config, clock::get, new Random(9));
        selector.success(new GossipConfig.Seed("Peer", "peer.example.test", 9010), new GossipConfig.Seed("Peer", "127.0.0.1", 9010));
        assertEquals(2, selector.select(table.snapshot(), Set.of(), 2).size());
    }

    @Test void aSeedNotYetSeenAsAHealthyMemberIsProbedOnlyInReservedSlots() {
        var config = config(1, "Unseen@127.0.0.1:9030");
        var table = new MembershipTable(config, self, clock::get);
        table.mergeDirect(record("Healthy", 2, 9010), List.of());
        var selector = new PeerSelector(config, clock::get, new Random(6));
        for (int i = 1; i <= 30; i++) {
            assertEquals(i % 3 == 0 ? "Unseen" : "Healthy", selector.select(table.snapshot(), Set.of(), 1).getFirst().serverId());
        }
    }

    @Test void backoffNeverExceedsThirtySecondsEvenWhenARoundIsLonger() {
        var properties = GossipConfigTest.minimal();
        properties.setProperty("round.millis", "60000");
        properties.setProperty("seeds", "Peer@127.0.0.1:9010");
        var config = GossipConfig.fromProperties(properties, "Self");
        var table = new MembershipTable(config, self, clock::get);
        var selector = new PeerSelector(config, clock::get, new Random(1));
        var peer = config.seeds().getFirst();
        selector.failure(peer);
        clock.set(TimeUnit.SECONDS.toNanos(30));
        assertEquals(List.of(peer), selector.select(table.snapshot(), Set.of(), 1));
    }

    @Test void aFullMemberKnownByHostnameKeepsItsBackoff() {
        var config = config(1, "");
        var table = new MembershipTable(config, self, clock::get);
        var named = at("Named", 2, "named.example.test", 9010);
        table.mergeDirect(named, List.of());
        var selector = new PeerSelector(config, clock::get, new Random(2));
        var peer = named.endpoint();
        var answered = new GossipConfig.Seed("Named", "127.0.0.1", 9010);
        selector.capacity(peer, answered);
        assertTrue(selector.select(table.snapshot(), Set.of(), 1).isEmpty(), "A full peer must wait before it is tried again");
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(126));
        assertEquals(List.of(peer), selector.select(table.snapshot(), Set.of(), 1));
        selector.capacity(peer, answered);
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(126));
        assertTrue(selector.select(table.snapshot(), Set.of(), 1).isEmpty(), "A second capacity hint must preserve exponential backoff");
    }

    @Test void hostValidationGivesTheSameAnswerWhenCachedAndAcrossEviction() {
        for (int pass = 0; pass < 2; pass++) {
            // The second pass is answered from the cache, and must agree with the first.
            assertTrue(GossipValidation.validHost("node-1.example.test"));
            assertTrue(GossipValidation.validHost("10.0.0.1"));
            assertTrue(GossipValidation.validHost("fe80::1"));
            assertTrue(GossipValidation.validHost("a".repeat(253)));
            for (String bad : List.of("", " ", "[::1]", "host/path", "user@host", "host:80", "a".repeat(254))) {
                assertFalse(GossipValidation.validHost(bad), bad);
            }
            assertFalse(GossipValidation.validHost(null));
        }
        // More distinct hosts than the cache keeps: evicted hosts are simply parsed again.
        for (int i = 0; i < 2000; i++) { assertTrue(GossipValidation.validHost("host-" + i + ".example.test")); }
        assertTrue(GossipValidation.cachedHosts() <= 1024, "Hosts arrive from the network, so the cache must stay bounded");
        assertTrue(GossipValidation.validHost("host-0.example.test"));
        assertFalse(GossipValidation.validHost("host-0.example.test/path"));
    }

    @Test void ipLiteralsAreParsedAndHostnamesAreRecognisedWithoutParsing() throws Exception {
        assertEquals(java.net.InetAddress.ofLiteral("127.0.0.1"), GossipValidation.literal("127.0.0.1"));
        assertEquals(java.net.InetAddress.ofLiteral("::1"), GossipValidation.literal("0:0:0:0:0:0:0:1"));
        assertEquals(java.net.InetAddress.ofLiteral("::1"), GossipValidation.literal("::1"));
        for (String host : List.of("peer.example.test", "localhost", "Node-7", "1.2.3.example")) { assertNull(GossipValidation.literal(host), host); }
        // Digits and dots alone, or anything with a colon, still go to the real parser.
        assertNull(GossipValidation.literal("1.2.3.4.5"));
        assertNull(GossipValidation.literal("not:an:address"));
        assertTrue(PeerSelector.isSelf(record("Self", 1, 9000), new GossipConfig.Seed("Self", "127.0.0.1", 9000)));
        assertFalse(PeerSelector.isSelf(record("Self", 1, 9000), new GossipConfig.Seed("Self", "other.example", 9000)));
    }

    @Test void idValidationAcceptsExactlyTheDocumentedCharactersAndLengths() {
        var documented = java.util.regex.Pattern.compile("[A-Za-z0-9._-]{1,128}");
        for (int c = 0; c <= 0xFFFF; c++) {
            for (String id : List.of(String.valueOf((char) c), "a" + (char) c + "b")) {
                assertEquals(documented.matcher(id).matches(), GossipValidation.validId(id), "U+" + Integer.toHexString(c));
            }
        }
        for (int length : new int[] {0, 1, 127, 128, 129, 1000}) {
            String id = "Ab9._-".repeat(200).substring(0, length);
            assertEquals(documented.matcher(id).matches(), GossipValidation.validId(id), "length " + length);
        }
        assertFalse(GossipValidation.validId(null));
    }

    @Test void literalAnswersExactlyAsTheAddressParserDoes() {
        // ASCII and beyond, plus look-alikes of digits and dots that a careless character check might accept.
        var characters = new ArrayList<Integer>();
        for (int c = 0; c < 0x800; c++) { characters.add(c); }
        characters.addAll(List.of(0x0966, 0x2024, 0xFF0E, 0xFF10, 0xFF19, 0xFE52));
        for (int c : characters) {
            char character = (char) c;
            for (String host : List.of(String.valueOf(character), "1.2.3." + character, character + "1.2.3", "1" + character + "2", "::" + character)) {
                java.net.InetAddress expected;
                try { expected = java.net.InetAddress.ofLiteral(host); } catch (IllegalArgumentException notLiteral) { expected = null; }
                assertEquals(expected, GossipValidation.literal(host), "U+" + Integer.toHexString(c));
            }
        }
    }

    @Test void aliasConfirmationTakesASnapshotOnlyForASeedWhoseAddressDiffers() {
        var config = config(2, "Peer@peer.example.test:9010");
        var table = new MembershipTable(config, self, clock::get);
        table.mergeDirect(new MemberRecord(new MemberKey("Peer", new UUID(0, 2)), "127.0.0.1", 9010, 1, MemberRecord.State.ALIVE, Map.of()), List.of());
        var selector = new PeerSelector(config, clock::get, new Random(13));
        var snapshots = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Supplier<MembershipTable.Snapshot> counted = () -> { snapshots.incrementAndGet(); return table.snapshot(); };
        var numeric = new GossipConfig.Seed("Peer", "127.0.0.1", 9010);
        selector.resolved(new GossipConfig.Seed("Gossiped", "127.0.0.9", 9020), numeric, counted);
        assertEquals(0, snapshots.get(), "Members learned by gossip are not seeds and need no alias check");
        selector.resolved(numeric, numeric, counted);
        assertEquals(0, snapshots.get(), "An address resolving to itself needs no alias check");
        selector.resolved(config.seeds().getFirst(), numeric, counted);
        assertEquals(1, snapshots.get());
        assertEquals(List.of(numeric), selector.select(table.snapshot(), Set.of(), 2), "The verified alias collapses seed and member");
    }

    @Test void unverifiedHostnamesAndDifferentExpectedIdentitiesAreNotSilentlyCollapsed() {
        var config = config(2, "Other@peer.example.test:9010");
        var table = new MembershipTable(config, self, clock::get);
        table.mergeDirect(record("Peer", 2, 9010), List.of());
        var selector = new PeerSelector(config, clock::get, new Random(9));
        selector.resolved(config.seeds().getFirst(), new GossipConfig.Seed("Other", "127.0.0.1", 9010), table::snapshot);
        assertEquals(2, selector.select(table.snapshot(), Set.of(), 2).size());
        selector.success(config.seeds().getFirst(), new GossipConfig.Seed("Other", "127.0.0.1", 9010));
        assertEquals(2, selector.select(table.snapshot(), Set.of(), 2).size());
    }

    @Test void forwardedUnverifiedMembersCannotEstablishHostnameAliases() {
        var config = config(2, "Peer@peer.example.test:9010");
        var table = new MembershipTable(config, self, clock::get);
        table.merge(List.of(record("Peer", 2, 9010)));
        var selector = new PeerSelector(config, clock::get, new Random(11));
        selector.resolved(config.seeds().getFirst(), new GossipConfig.Seed("Peer", "127.0.0.1", 9010), table::snapshot);
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
