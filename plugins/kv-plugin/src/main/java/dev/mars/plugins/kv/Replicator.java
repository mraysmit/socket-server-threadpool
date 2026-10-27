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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Pushes writes to other servers: a local write, and a record from a peer that was new to this server, which is passed
 * on. Both go to this server's push mesh: {@code fanout} peers chosen at random among those alive and kept from one write
 * to the next, as gossipsub keeps its mesh. A stable mesh means a server talks to the same few peers, so the connections
 * it keeps open to them are reused, rather than to every server in the swarm over time.
 *
 * <p>A mesh is made two-way, like gossipsub's GRAFT: a peer that pushes to this server is taken into this server's mesh
 * too, up to twice the fanout, and a peer newly chosen is told so at once ({@link #maintain}). Without that, a server that
 * no other server happened to choose would hear of writes only from the periodic comparison. A peer whose pushes are
 * refused for a clock too far ahead is dropped from the mesh, and kept out of it for a while ({@link #penalise}).
 *
 * <p>Records for one peer are collected for {@code batchMillis} and sent together, a key written several times in that
 * time once at its newest version. A record whose value is larger than {@code announceBytes} is first announced by key and
 * version, and sent only if the peer asks for it, as Ethereum announces large transactions by hash: most pushes of a
 * spreading write reach servers that already have it, and those then cost a few bytes, not the whole value.
 *
 * <p>A record is passed on only when it was new to this server, and never to the server it came from or the one that
 * wrote it, so the spreading stops once every server holds it. A push never delays the write that caused it: the peers
 * are found and contacted on the executor, each on its own task, so a slow or dead peer holds up nobody else.
 */
final class Replicator {
    /**
     * How pushes are made. {@code maxBatchBytes} keeps a batch well within the host's request line limit; a single
     * record larger than it is still sent, alone.
     */
    record Settings(int fanout, long batchMillis, int maxBatchBytes, int announceBytes, Duration penalty) {
        Settings {
            if (fanout <= 0) { throw new IllegalArgumentException("Push fanout must be positive"); }
            if (batchMillis < 0) { throw new IllegalArgumentException("Batch time must not be negative"); }
            if (maxBatchBytes <= 0 || announceBytes <= 0) { throw new IllegalArgumentException("Byte limits must be positive"); }
        }

        /** Every write sent at once and whole, to {@code fanout} peers. */
        static Settings immediate(int fanout) {
            return new Settings(fanout, 0, Integer.MAX_VALUE, Integer.MAX_VALUE, Duration.ofMinutes(1));
        }

        static Settings of(KvConfig config) {
            return new Settings(config.pushFanout(), config.pushBatchMillis(), config.syncMaxBytes(),
                    config.pushAnnounceBytes(), Duration.ofMinutes(1));
        }
    }

    /** Wire overhead of a record besides its key and value: its version, flags and JSON punctuation, generously. */
    private static final int RECORD_OVERHEAD_BYTES = 128;

    private final PeerSource peers;
    private final PeerLink link;
    private final Executor executor;
    private final KvCounters counters;
    private final Settings settings;
    /** The most peers the mesh holds once other servers have taken this one into theirs. */
    private final int meshLimit;
    private final Random random;
    /** The server IDs of the push mesh, in the order they joined; guarded by this replicator. */
    private final Set<String> mesh = new LinkedHashSet<>();
    /** Servers kept out of the mesh, until the {@code System.nanoTime()} given; guarded by this replicator. */
    private final Map<String, Long> penalisedUntil = new HashMap<>();
    /** Records waiting to be sent to each peer, by server ID; guarded by this replicator. */
    private final Map<String, Batch> batches = new HashMap<>();

    /** Pushes to every peer, however many there are. */
    Replicator(PeerSource peers, PeerLink link, Executor executor, KvCounters counters) {
        this(peers, link, executor, counters, Integer.MAX_VALUE, new Random());
    }

    Replicator(PeerSource peers, PeerLink link, Executor executor, KvCounters counters, int fanout, Random random) {
        this(peers, link, executor, counters, Settings.immediate(fanout), random);
    }

    Replicator(PeerSource peers, PeerLink link, Executor executor, KvCounters counters, Settings settings, Random random) {
        this.peers = peers;
        this.link = link;
        this.executor = executor;
        this.counters = counters;
        this.settings = settings;
        meshLimit = settings.fanout() > Integer.MAX_VALUE / 2 ? Integer.MAX_VALUE : 2 * settings.fanout();
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
        if (mesh.size() < meshLimit && !penalised(serverId)) { mesh.add(serverId); }
    }

    /**
     * Drops a peer that sent records this server refused, for a clock too far ahead, from the mesh, and keeps it out for
     * the penalty time: neither chosen nor taken in when it pushes. Its records are still checked and taken one by one.
     */
    synchronized void penalise(String serverId) {
        mesh.remove(serverId);
        penalisedUntil.put(serverId, System.nanoTime() + settings.penalty().toNanos());
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

    /** Whether a server is still serving a penalty; one whose penalty is over is forgotten. Callers hold the lock. */
    private boolean penalised(String serverId) {
        var until = penalisedUntil.get(serverId);
        if (until == null) { return false; }
        if (System.nanoTime() - until < 0) { return true; }
        penalisedUntil.remove(serverId);
        return false;
    }

    /**
     * Drops mesh peers no longer alive, and fills the mesh up to the fanout with peers chosen at random. Returns the
     * peers added. A server taken in by a graft but not yet alive in this server's own view is dropped too.
     */
    private synchronized List<Peer> refresh(List<Peer> alive) {
        var byId = new HashMap<String, Peer>();
        for (var peer : alive) { byId.put(peer.serverId(), peer); }
        mesh.retainAll(byId.keySet());
        var added = new ArrayList<Peer>();
        if (mesh.size() >= settings.fanout()) { return added; }
        var candidates = new ArrayList<Peer>();
        for (var peer : alive) {
            if (!mesh.contains(peer.serverId()) && !penalised(peer.serverId())) { candidates.add(peer); }
        }
        if (candidates.size() > settings.fanout() - mesh.size()) { Collections.shuffle(candidates, random); }
        for (var peer : candidates) {
            if (mesh.size() >= settings.fanout()) { break; }
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
            if (settings.batchMillis() == 0) { submit(peer, records); }
            else { enqueue(peer, records); }
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

    /** Records waiting for one peer: the newest version of each key, and their approximate size on the wire. */
    private static final class Batch {
        final Peer peer;
        final LinkedHashMap<String, KvRecord> records = new LinkedHashMap<>();
        long bytes;

        Batch(Peer peer) { this.peer = peer; }
    }

    /**
     * Adds records to the peer's batch. The first record of a batch starts its timer; a batch that would grow past the
     * byte limit is sent at once and a new one started.
     */
    private void enqueue(Peer peer, List<KvRecord> records) {
        var full = new ArrayList<List<KvRecord>>();
        boolean started = false;
        synchronized (this) {
            var batch = batches.get(peer.serverId());
            if (batch == null) {
                batch = new Batch(peer);
                batches.put(peer.serverId(), batch);
                started = true;
            }
            for (var record : records) {
                var held = batch.records.get(record.key());
                if (held != null && held.version().compareTo(record.version()) >= 0) { continue; }
                long size = wireBytes(record);
                if (held == null && !batch.records.isEmpty() && batch.bytes + size > settings.maxBatchBytes()) {
                    full.add(List.copyOf(batch.records.values()));
                    batch.records.clear();
                    batch.bytes = 0;
                }
                if (held != null) { batch.bytes -= wireBytes(held); }
                batch.records.put(record.key(), record);
                batch.bytes += size;
            }
        }
        for (var chunk : full) { submit(peer, chunk); }
        if (started) {
            try { executor.execute(() -> flushLater(peer.serverId())); }
            catch (RejectedExecutionException stopped) { flush(peer.serverId()); }
        }
    }

    private void flushLater(String serverId) {
        try { TimeUnit.MILLISECONDS.sleep(settings.batchMillis()); }
        catch (InterruptedException stopping) { Thread.currentThread().interrupt(); }
        flush(serverId);
    }

    /** Sends whatever is waiting for the peer, on this thread. */
    private void flush(String serverId) {
        Batch batch;
        synchronized (this) { batch = batches.remove(serverId); }
        if (batch != null && !batch.records.isEmpty()) { pushTo(batch.peer, List.copyOf(batch.records.values())); }
    }

    private void submit(Peer peer, List<KvRecord> records) {
        try { executor.execute(() -> pushTo(peer, records)); }
        catch (RejectedExecutionException stopped) { counters.pushFailures.incrementAndGet(); }
    }

    /** Sends small records whole, and announces large ones first, sending those the peer asks for. */
    private void pushTo(Peer peer, List<KvRecord> records) {
        try {
            var whole = new ArrayList<KvRecord>();
            var large = new ArrayList<KvRecord>();
            for (var record : records) { (valueBytes(record) > settings.announceBytes() ? large : whole).add(record); }
            if (!large.isEmpty()) {
                var wanted = new HashSet<>(link.announce(peer, large));
                counters.announced.addAndGet(large.size());
                for (var record : large) {
                    if (wanted.contains(record.key())) {
                        whole.add(record);
                        counters.fetched.incrementAndGet();
                    }
                }
            }
            if (!whole.isEmpty()) { link.send(peer, whole); }
            counters.pushed.incrementAndGet();
        } catch (IOException | RuntimeException e) {
            counters.pushFailures.incrementAndGet();
        }
    }

    private static int valueBytes(KvRecord record) {
        return record.deleted() ? 0 : record.valueJson().getBytes(StandardCharsets.UTF_8).length;
    }

    private static long wireBytes(KvRecord record) {
        return (long) record.key().length() * 4 + valueBytes(record) + RECORD_OVERHEAD_BYTES;
    }
}
