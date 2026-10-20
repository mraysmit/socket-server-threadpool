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
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Pushes local writes to the other servers. A push never delays the write that caused it: the peers are found and
 * contacted on the executor, each on its own task, so a slow or dead peer holds up nobody else.
 */
final class Replicator {
    private final PeerSource peers;
    private final PeerLink link;
    private final Executor executor;
    private final KvCounters counters;

    Replicator(PeerSource peers, PeerLink link, Executor executor, KvCounters counters) {
        this.peers = peers;
        this.link = link;
        this.executor = executor;
        this.counters = counters;
    }

    /** Sends the record to every peer currently believed alive. Failures are counted, never thrown. */
    void push(KvRecord record) {
        try { executor.execute(() -> pushToPeers(record)); }
        catch (RejectedExecutionException stopped) { counters.pushFailures.incrementAndGet(); }
    }

    private void pushToPeers(KvRecord record) {
        List<Peer> alive;
        try { alive = peers.activePeers(); }
        catch (IOException | RuntimeException e) {
            counters.pushFailures.incrementAndGet();
            return;
        }
        for (var peer : alive) {
            try { executor.execute(() -> pushTo(peer, record)); }
            catch (RejectedExecutionException stopped) { counters.pushFailures.incrementAndGet(); }
        }
    }

    private void pushTo(Peer peer, KvRecord record) {
        try {
            link.send(peer, List.of(record));
            counters.pushed.incrementAndGet();
        } catch (IOException | RuntimeException e) {
            counters.pushFailures.incrementAndGet();
        }
    }
}
