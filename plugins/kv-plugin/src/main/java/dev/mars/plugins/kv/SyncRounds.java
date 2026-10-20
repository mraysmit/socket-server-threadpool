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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * One round of comparing this server's pairs with a few random peers, and the first pull when a server starts. Failures
 * are recorded, never thrown, so a peer that is down never stops the others or the next round.
 */
final class SyncRounds {
    private final PeerSource peers;
    private final Syncer syncer;
    private final VersionedStore store;
    private final SyncStats stats;
    private final int fanout;
    private final Random random;

    SyncRounds(PeerSource peers, Syncer syncer, VersionedStore store, SyncStats stats, int fanout, Random random) {
        this.peers = peers;
        this.syncer = syncer;
        this.store = store;
        this.stats = stats;
        this.fanout = fanout;
        this.random = random;
    }

    /** Drops what has expired, then compares with up to the configured number of peers chosen at random from those alive. */
    void round() {
        stats.roundRan();
        store.purgeExpired();
        var alive = alivePeers();
        if (alive == null) { return; }
        for (var peer : alive.subList(0, Math.min(fanout, alive.size()))) { syncWith(peer); }
    }

    /** The pull a server makes as it starts: it compares with one peer, then a second, to cover what the first lacked. Says how many succeeded. */
    int bootstrap() {
        var alive = alivePeers();
        if (alive == null) { return 0; }
        int succeeded = 0;
        for (var peer : alive) {
            if (syncWith(peer)) { succeeded++; }
            if (succeeded == 2) { break; }
        }
        return succeeded;
    }

    /** The peers alive now in a random order, or null if they could not be found out. */
    private List<Peer> alivePeers() {
        List<Peer> alive;
        try { alive = new ArrayList<>(peers.activePeers()); }
        catch (IOException | RuntimeException e) {
            stats.failed();
            return null;
        }
        Collections.shuffle(alive, random);
        return alive;
    }

    private boolean syncWith(Peer peer) {
        try {
            var outcome = syncer.syncWith(peer);
            if (!outcome.equal()) { stats.differenceFound(); }
            stats.peerSynced(peer.serverId(), outcome.equal() ? "EQUAL" : "SYNCED", "");
            return true;
        } catch (IOException | RuntimeException e) {
            stats.failed();
            stats.peerSynced(peer.serverId(), "FAILED", String.valueOf(e.getMessage()));
            return false;
        }
    }
}
