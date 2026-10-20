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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** One round of comparing with a few random peers, and the first pull when a server starts. Several stores in one process stand in for a swarm. */
class SyncRoundsTest {
    private long now = 1_000_000;
    private final VersionedStore mine = new VersionedStore("Server-1", () -> now, KvConfig.defaults());
    private final Map<String, VersionedStore> stores = new HashMap<>();
    private final Map<String, Peer> peers = new HashMap<>();
    private final Set<String> failing = new HashSet<>();
    private final List<String> contacted = new ArrayList<>();
    private boolean gossipDown;
    private final SyncStats stats = new SyncStats(() -> now);

    private final PeerSource source = () -> {
        if (gossipDown) { throw new IOException("gossip is not answering"); }
        return List.copyOf(peers.values());
    };

    /** Routes each request to the store of the peer it is for, counting contacts and failing for the peers told to fail. */
    private final SyncLink link = new SyncLink() {
        private SyncResponder responder(Peer peer) throws IOException {
            if (failing.contains(peer.serverId())) { throw new IOException("connection refused by " + peer.serverId()); }
            return new SyncResponder(stores.get(peer.serverId()), 32_768);
        }

        @Override public SyncResponder.RootReply syncRoot(Peer peer, String root) throws IOException {
            contacted.add(peer.serverId());
            return responder(peer).root(root);
        }

        @Override public SyncResponder.DigestReply syncDigest(Peer peer, SyncResponder.DigestRequest request) throws IOException {
            return responder(peer).digest(request);
        }

        @Override public void apply(Peer peer, List<KvRecord> records) throws IOException {
            responder(peer);
            stores.get(peer.serverId()).apply(records);
        }
    };

    private SyncRounds rounds(int fanout, long seed) {
        return new SyncRounds(source, new Syncer(mine, link, 32_768), mine, stats, fanout, new Random(seed));
    }

    private VersionedStore peer(String id) {
        var store = new VersionedStore(id, () -> now, KvConfig.defaults());
        stores.put(id, store);
        peers.put(id, new Peer(id, "localhost", 9000 + stores.size()));
        return store;
    }

    private static KvRecord written(String key, long w, String server) { return new KvRecord(key, "\"v\"", new Version(w, 0, server), false); }

    @Test void aRoundTalksToTheConfiguredNumberOfPeersAndNoMore() {
        for (int i = 2; i <= 6; i++) { peer("Server-" + i); }
        rounds(2, 1).round();
        assertEquals(2, new HashSet<>(contacted).size());
        assertEquals(2, contacted.size());
    }

    @Test void overManyRoundsEveryPeerGetsChosen() {
        for (int i = 2; i <= 5; i++) { peer("Server-" + i); }
        var rounds = rounds(1, 7);
        for (int i = 0; i < 60; i++) { rounds.round(); }
        assertEquals(Set.of("Server-2", "Server-3", "Server-4", "Server-5"), new HashSet<>(contacted));
    }

    @Test void aFanoutLargerThanThePeerCountTalksToEveryone() {
        peer("Server-2");
        peer("Server-3");
        rounds(5, 1).round();
        assertEquals(Set.of("Server-2", "Server-3"), new HashSet<>(contacted));
    }

    @Test void aRoundSettlesTheDifferenceWithEachPeerItTalksTo() {
        var two = peer("Server-2");
        two.apply(List.of(written("from-two", 500, "Server-2")));
        mine.apply(List.of(written("from-me", 600, "Server-1")));
        rounds(1, 1).round();
        assertEquals(two.root(), mine.root());
        assertTrue(mine.get("from-two").isPresent());
        assertTrue(two.get("from-me").isPresent());
    }

    @Test void aFailingPeerDoesNotStopTheRoundAndIsRecorded() {
        peer("Server-2");
        peer("Server-3");
        failing.add("Server-2");
        stores.get("Server-3").apply(List.of(written("x", 500, "Server-3")));
        rounds(2, 1).round();
        assertTrue(mine.get("x").isPresent(), "The other peer was still synced");
        var byPeer = new HashMap<String, SyncStats.PeerSync>();
        stats.peers().forEach(p -> byPeer.put(p.serverId(), p));
        assertEquals("FAILED", byPeer.get("Server-2").result());
        assertTrue(byPeer.get("Server-2").detail().contains("refused"), byPeer.get("Server-2").detail());
        assertEquals("SYNCED", byPeer.get("Server-3").result());
        assertEquals(1, stats.failures());
    }

    @Test void aPeerThatIsAlreadyEqualIsRecordedAsEqual() {
        var two = peer("Server-2");
        two.apply(List.of(written("a", 500, "Server-2")));
        mine.apply(List.of(written("a", 500, "Server-2")));
        rounds(1, 1).round();
        assertEquals("EQUAL", stats.peers().getFirst().result());
        assertEquals(0, stats.differences());
        assertEquals(1, stats.rounds());
    }

    @Test void theCountersAddUpAcrossRounds() {
        var two = peer("Server-2");
        var rounds = rounds(1, 1);
        rounds.round();
        two.apply(List.of(written("a", 500, "Server-2")));
        rounds.round();
        rounds.round();
        assertEquals(3, stats.rounds());
        assertEquals(1, stats.differences());
        assertEquals(0, stats.failures());
    }

    @Test void whenGossipCannotAnswerTheRoundIsCountedAsAFailureAndDoesNotThrow() {
        peer("Server-2");
        gossipDown = true;
        assertDoesNotThrow(() -> rounds(1, 1).round());
        assertEquals(1, stats.failures());
        assertEquals(0, contacted.size());
    }

    @Test void withNoPeersARoundIsQuietlyEmpty() {
        rounds(1, 1).round();
        assertEquals(1, stats.rounds());
        assertEquals(0, stats.failures());
    }

    @Test void eachRoundDropsDeletionRecordsPastTheRetention() {
        var shortLived = new VersionedStore("Server-1", () -> now, new KvConfig(256, 16_384, 1_000, true, 2_000, 1, 32_768, 1_000));
        shortLived.apply(List.of(new KvRecord("gone", null, new Version(now - 5_000, 0, "Server-2"), true)));
        assertEquals(1, shortLived.deletedCount());
        new SyncRounds(source, new Syncer(shortLived, link, 32_768), shortLived, stats, 1, new Random(1)).round();
        assertEquals(0, shortLived.deletedCount());
    }

    @Test void theTimeOfEachPeersLastSyncComesFromTheClock() {
        peer("Server-2");
        now = 5_000_000;
        rounds(1, 1).round();
        assertEquals(5_000_000L, stats.peers().getFirst().atMillis());
    }

    @Test void startingUpPullsEverythingFromAPeerAndThenFromASecond() {
        var two = peer("Server-2");
        var three = peer("Server-3");
        two.apply(List.of(written("a", 500, "Server-2")));
        three.apply(List.of(written("b", 600, "Server-3")));
        int successes = rounds(1, 1).bootstrap();
        assertEquals(2, successes);
        assertTrue(mine.get("a").isPresent() && mine.get("b").isPresent(), "Both peers' data arrived");
    }

    @Test void startingUpWithOnePeerSyncsOnceAndWithNoneReportsNone() {
        var two = peer("Server-2");
        two.apply(List.of(written("a", 500, "Server-2")));
        assertEquals(1, rounds(1, 1).bootstrap());
        assertTrue(mine.get("a").isPresent());
        peers.clear();
        assertEquals(0, rounds(1, 1).bootstrap());
    }

    @Test void startingUpSkipsPeersThatFailAndTriesTheNext() {
        peer("Server-2");
        var three = peer("Server-3");
        three.apply(List.of(written("c", 700, "Server-3")));
        failing.add("Server-2");
        assertEquals(1, rounds(1, 1).bootstrap());
        assertTrue(mine.get("c").isPresent());
    }

    @Test void startingUpWhenGossipCannotAnswerReportsNone() {
        peer("Server-2");
        gossipDown = true;
        assertEquals(0, rounds(1, 1).bootstrap());
        assertEquals(1, stats.failures());
    }
}
