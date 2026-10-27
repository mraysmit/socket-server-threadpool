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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ReplicatorTest {
    private static final Peer TWO = new Peer("Server-002", "127.0.0.1", 9100);
    private static final Peer THREE = new Peer("Server-003", "127.0.0.1", 9200);
    private static final KvRecord WRITE = new KvRecord("a", "1", new Version(5_000, 0, "Server-001"), false);
    private static final KvRecord DELETION = new KvRecord("a", null, new Version(6_000, 0, "Server-001"), true);

    private final KvCounters counters = new KvCounters();
    private final List<Peer> alive = new ArrayList<>(List.of(TWO, THREE));
    private final List<Map.Entry<Peer, List<KvRecord>>> sent = Collections.synchronizedList(new ArrayList<>());
    private final List<Peer> unreachable = new ArrayList<>();
    private boolean gossipDown;

    private final PeerSource peers = () -> {
        if (gossipDown) { throw new IOException("gossip is not answering"); }
        return List.copyOf(alive);
    };
    private final PeerLink link = (peer, records) -> {
        if (unreachable.contains(peer)) { throw new IOException("connection refused"); }
        sent.add(Map.entry(peer, records));
    };

    /** An executor that runs each task on the caller's thread, so a test can look at the result straight away. */
    private final Replicator inline = new Replicator(peers, link, Runnable::run, counters);

    @Test void aPushSendsTheRecordToEachActivePeer() {
        inline.push(WRITE);
        assertEquals(2, sent.size());
        assertEquals(List.of(TWO, THREE), sent.stream().map(Map.Entry::getKey).toList());
        assertTrue(sent.stream().allMatch(entry -> entry.getValue().equals(List.of(WRITE))));
        assertEquals(2L, counters.pushed.get());
        assertEquals(0L, counters.pushFailures.get());
    }

    @Test void aDeletionRecordIsPushedLikeAWrite() {
        inline.push(DELETION);
        assertEquals(2, sent.size());
        assertTrue(sent.stream().allMatch(entry -> entry.getValue().equals(List.of(DELETION))));
    }

    @Test void noActivePeersMeansNothingIsSentAndNothingFails() {
        alive.clear();
        inline.push(WRITE);
        assertEquals(0, sent.size());
        assertEquals(0L, counters.pushed.get());
        assertEquals(0L, counters.pushFailures.get());
    }

    @Test void aPeerThatCannotBeReachedDoesNotStopTheOthersAndIsCounted() {
        unreachable.add(TWO);
        inline.push(WRITE);
        assertEquals(List.of(THREE), sent.stream().map(Map.Entry::getKey).toList());
        assertEquals(1L, counters.pushed.get());
        assertEquals(1L, counters.pushFailures.get());
    }

    @Test void aGossipThatCannotAnswerGivesNoPeersAndIsCountedWithoutThrowing() {
        gossipDown = true;
        assertDoesNotThrow(() -> inline.push(WRITE));
        assertEquals(0, sent.size());
        assertEquals(1L, counters.pushFailures.get());
    }

    private static List<Peer> peers(int count) {
        var many = new ArrayList<Peer>();
        for (int i = 2; i < 2 + count; i++) { many.add(new Peer("Server-" + i, "127.0.0.1", 9000 + i)); }
        return many;
    }

    private java.util.Set<Peer> targets() {
        return sent.stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
    }

    @Test void writesGoToTheSameFewPeersEveryTime() {
        alive.clear();
        alive.addAll(peers(19));
        var mesh = new Replicator(peers, link, Runnable::run, counters, 3, new java.util.Random(1));
        for (int i = 0; i < 10; i++) { mesh.push(WRITE); }
        assertEquals(30, sent.size());
        assertEquals(3, targets().size(), "Ten writes, one mesh of three");
    }

    @Test void aMeshPeerThatIsNoLongerAliveIsReplaced() {
        alive.clear();
        alive.addAll(peers(5));
        var mesh = new Replicator(peers, link, Runnable::run, counters, 2, new java.util.Random(1));
        mesh.push(WRITE);
        var first = targets();
        var gone = first.iterator().next();
        alive.remove(gone);
        sent.clear();
        mesh.push(WRITE);
        assertEquals(2, targets().size());
        assertFalse(targets().contains(gone));
        assertEquals(1, targets().stream().filter(first::contains).count(), "The peer still alive stays in the mesh");
    }

    @Test void aForwardedRecordGoesNeitherToItsSenderNorToItsWriter() {
        var four = new Peer("Server-004", "127.0.0.1", 9300);
        var writer = new Peer("Server-001", "127.0.0.1", 9000);
        alive.addAll(List.of(four, writer));
        inline.forward(List.of(WRITE), TWO.serverId());
        assertEquals(java.util.Set.of(THREE, four), targets());
    }

    @Test void aPeerThatPushesHereJoinsTheMeshUpToTwiceTheFanout() {
        alive.clear();
        alive.addAll(peers(6));
        var mesh = new Replicator(peers, link, Runnable::run, counters, 1, new java.util.Random(1));
        mesh.push(WRITE);
        assertEquals(1, targets().size());
        for (var peer : alive) { mesh.grafted(peer.serverId()); }
        sent.clear();
        mesh.push(WRITE);
        assertEquals(2, targets().size(), "A fanout of one holds at most two");
    }

    @Test void maintainingTheMeshTellsOnlyThePeersNewlyChosen() throws Exception {
        alive.clear();
        alive.addAll(peers(5));
        var grafts = Collections.synchronizedList(new ArrayList<Peer>());
        PeerLink grafting = new PeerLink() {
            @Override public void send(Peer peer, List<KvRecord> records) { sent.add(Map.entry(peer, records)); }
            @Override public void graft(Peer peer) { grafts.add(peer); }
        };
        var mesh = new Replicator(peers, grafting, Runnable::run, counters, 2, new java.util.Random(1));
        mesh.maintain();
        assertEquals(2, grafts.size());
        mesh.maintain();
        assertEquals(2, grafts.size(), "An unchanged mesh tells nobody again");
        alive.remove(grafts.getFirst());
        mesh.maintain();
        assertEquals(3, grafts.size(), "The replacement is told");
        assertEquals(0, sent.size(), "Maintaining sends no records");
    }

    @Test void aPushDoesNotMakeTheCallerWaitForSlowPeers() throws Exception {
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(2);
        PeerLink slow = (peer, records) -> {
            started.countDown();
            try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var background = new Replicator(peers, slow, executor, counters);
            long before = System.nanoTime();
            background.push(WRITE);
            long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
            assertTrue(tookMillis < 1_000, "push returned after " + tookMillis + " ms while peers were still blocked");
            assertTrue(started.await(5, TimeUnit.SECONDS), "both peers should be contacted at the same time, not one after the other");
            release.countDown();
        }
        assertEquals(2L, counters.pushed.get());
    }
}
