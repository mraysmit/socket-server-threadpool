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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Pushes writes to other servers: a local write, and a record from a peer that was new to this server, which is passed
 * on. Both go to this server's push mesh: {@code fanout} peers chosen at random among those alive and kept from one write
 * to the next, as gossipsub keeps its mesh. A stable mesh means a server talks to the same few peers, so the connections
 * it keeps open to them are reused, rather than to every server in the swarm over time.
 *
 * <p>A mesh is made two-way, like gossipsub's GRAFT: a peer that pushes to this server is taken into this server's mesh
 * too, up to twice the fanout, and a peer newly chosen is told so at once ({@link #maintain}). Without that, a server that
 * no other server happened to choose would hear of writes only from the periodic comparison.
 *
 * <p>A record is passed on only when it was new to this server, and never to the server it came from or the one that
 * wrote it, so the spreading stops once every server holds it. A push never delays the write that caused it: the peers
 * are found and contacted on the executor, each on its own task, so a slow or dead peer holds up nobody else.
 */
final class Replicator {
    private final PeerSource peers;
    private final PeerLink link;
    private final Executor executor;
    private final KvCounters counters;
    private final int fanout;
    /** The most peers the mesh holds once other servers have taken this one into theirs. */
    private final int meshLimit;
    private final Random random;
    /** The server IDs of the push mesh, in the order they joined; guarded by this replicator. */
    private final Set<String> mesh = new LinkedHashSet<>();

    /** Pushes to every peer, however many there are. */
    Replicator(PeerSource peers, PeerLink link, Executor executor, KvCounters counters) {
        this(peers, link, executor, counters, Integer.MAX_VALUE, new Random());
    }

    Replicator(PeerSource peers, PeerLink link, Executor executor, KvCounters counters, int fanout, Random random) {
        if (fanout <= 0) { throw new IllegalArgumentException("Push fanout must be positive"); }
        this.peers = peers;
        this.link = link;
        this.executor = executor;
        this.counters = counters;
        this.fanout = fanout;
        meshLimit = fanout > Integer.MAX_VALUE / 2 ? Integer.MAX_VALUE : 2 * fanout;
        this.random = random;
    }

    /** Sends a local write to the push mesh. Failures are counted, never thrown. */
    void push(KvRecord record) { send(List.of(record), Set.of()); }

    /**
     * Passes on records from a peer that were new to this server, to the push mesh, leaving out the servers that wrote
     * them and the server that sent them, which hold them already. {@code sender} is null when it is not known.
     */
    void forward(List<KvRecord> records, String sender) {
        if (records.isEmpty()) { return; }
        var leaveOut = new HashSet<String>();
        for (var record : records) { leaveOut.add(record.version().n()); }
        if (sender != null) { leaveOut.add(sender); }
        send(records, leaveOut);
    }

    /** A peer that pushed to this server is taken into its mesh while there is room, so pushes flow both ways. */
    synchronized void grafted(String serverId) {
        if (mesh.size() < meshLimit) { mesh.add(serverId); }
    }

    /**
     * Brings the mesh up to date with the peers alive, and tells each peer newly chosen, so that it takes this server
     * into its own mesh. Run every sync round. A peer that cannot be told is still pushed to.
     */
    void maintain() {
        List<Peer> alive;
        try { alive = peers.activePeers(); }
        catch (IOException | RuntimeException e) { return; }
        for (var peer : refresh(alive)) {
            try { link.graft(peer); }
            catch (IOException | RuntimeException e) { /* It still receives this server's pushes. */ }
        }
    }

    /** The server IDs now in the push mesh. */
    synchronized List<String> mesh() { return List.copyOf(mesh); }

    /**
     * Drops mesh peers no longer alive, and fills the mesh up to the fanout with peers chosen at random. Returns the
     * peers added. A server taken in by a graft but not yet alive in this server's own view is dropped too.
     */
    private synchronized List<Peer> refresh(List<Peer> alive) {
        var byId = new HashMap<String, Peer>();
        for (var peer : alive) { byId.put(peer.serverId(), peer); }
        mesh.retainAll(byId.keySet());
        var added = new ArrayList<Peer>();
        if (mesh.size() >= fanout) { return added; }
        var candidates = new ArrayList<Peer>();
        for (var peer : alive) { if (!mesh.contains(peer.serverId())) { candidates.add(peer); } }
        if (candidates.size() > fanout - mesh.size()) { Collections.shuffle(candidates, random); }
        for (var peer : candidates) {
            if (mesh.size() >= fanout) { break; }
            mesh.add(peer.serverId());
            added.add(peer);
        }
        return added;
    }

    private void send(List<KvRecord> records, Set<String> leaveOut) {
        try { executor.execute(() -> sendToPeers(records, leaveOut)); }
        catch (RejectedExecutionException stopped) { counters.pushFailures.incrementAndGet(); }
    }

    private void sendToPeers(List<KvRecord> records, Set<String> leaveOut) {
        List<Peer> chosen;
        try { chosen = choose(peers.activePeers(), leaveOut); }
        catch (IOException | RuntimeException e) {
            counters.pushFailures.incrementAndGet();
            return;
        }
        for (var peer : chosen) {
            try { executor.execute(() -> pushTo(peer, records)); }
            catch (RejectedExecutionException stopped) { counters.pushFailures.incrementAndGet(); }
        }
    }

    /** The mesh peers alive, less those left out, in the order the peer source lists them. */
    private List<Peer> choose(List<Peer> alive, Set<String> leaveOut) {
        refresh(alive);
        var chosen = new ArrayList<Peer>();
        synchronized (this) {
            for (var peer : alive) {
                if (mesh.contains(peer.serverId()) && !leaveOut.contains(peer.serverId())) { chosen.add(peer); }
            }
        }
        return chosen;
    }

    private void pushTo(Peer peer, List<KvRecord> records) {
        try {
            link.send(peer, records);
            counters.pushed.incrementAndGet();
        } catch (IOException | RuntimeException e) {
            counters.pushFailures.incrementAndGet();
        }
    }
}
