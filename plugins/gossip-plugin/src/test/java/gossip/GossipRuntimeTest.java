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

import gossip.fixture.DnsProcessFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class GossipRuntimeTest {
    @TempDir Path temporary;

    private static GossipConfig config(String seeds) {
        var properties = GossipConfigTest.minimal();
        properties.setProperty("round.millis", "20");
        properties.setProperty("heartbeat.millis", "5");
        properties.setProperty("suspect.after.millis", "10");
        properties.setProperty("dead.after.millis", "30");
        properties.setProperty("terminal.retention.millis", "60");
        properties.setProperty("seeds", seeds);
        return GossipConfig.fromProperties(properties, "Self");
    }

    @Test void isolatedRoundsAdvanceOnlyOwnerHeartbeatsAndRunRetentionMaintenance() throws Exception {
        var clock = new AtomicLong();
        var config = config("");
        var table = new MembershipTable(config, PeerSelectorTest.record("Self", 1, 9000), clock::get);
        table.merge(List.of(PeerSelectorTest.record("Old", 2, 9010)));
        var metrics = new GossipMetrics();
        var client = new Client(false, false);
        try (var runtime = new GossipRuntime(config, table, client, metrics, clock::get, new Random(1), new GossipDnsResolver(8, 2))) {
            clock.set(TimeUnit.MILLISECONDS.toNanos(60));
            runtime.round();
            assertEquals(2, table.snapshot().self().heartbeat());
            assertEquals(1, table.snapshot().tombstones());
            assertTrue(metrics.isolated());
            runtime.round();
            assertEquals(2, table.snapshot().self().heartbeat());
            clock.set(TimeUnit.MILLISECONDS.toNanos(120));
            runtime.round();
            assertEquals(0, table.snapshot().tombstones());
            assertEquals(3, metrics.rounds.get());
            assertTrue(client.calls.isEmpty());
        }
        assertTrue(client.closed);
    }

    @Test void fullWorkerBudgetSkipsNewWorkButKeepsHeartbeatsRunningAndStopJoinsEveryWorker() throws Exception {
        var clock = new AtomicLong();
        var config = config("A@127.0.0.1:9010,B@127.0.0.1:9020,C@127.0.0.1:9030");
        var table = new MembershipTable(config, PeerSelectorTest.record("Self", 1, 9000), clock::get);
        var metrics = new GossipMetrics();
        var client = new Client(true, false);
        try (var runtime = new GossipRuntime(config, table, client, metrics, clock::get, new Random(2), new GossipDnsResolver(8, 2))) {
            runtime.round();
            GossipDnsResolverTest.await(() -> client.calls.size() == 2);
            for (int i = 0; i < 10; i++) { clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(20)); runtime.round(); }
            assertEquals(2, client.calls.size());
            assertEquals(2, metrics.workers);
            assertTrue(table.snapshot().self().heartbeat() > 1);
            assertTrue(metrics.skipped.get() > 0);
            runtime.close();
            assertEquals(0, metrics.workers);
            assertEquals("STOPPED", metrics.workerState);
            assertTrue(client.threads.stream().noneMatch(Thread::isAlive));
            runtime.close();
        }
    }

    @Test void failuresBackOffInsteadOfResubmittingOnEveryRound() throws Exception {
        var clock = new AtomicLong();
        var config = config("A@127.0.0.1:9010");
        var table = new MembershipTable(config, PeerSelectorTest.record("Self", 1, 9000), clock::get);
        var metrics = new GossipMetrics();
        var client = new Client(false, true);
        try (var runtime = new GossipRuntime(config, table, client, metrics, clock::get, new Random(3), new GossipDnsResolver(8, 2))) {
            runtime.round();
            GossipDnsResolverTest.await(() -> metrics.workers == 0 && client.threads.stream().noneMatch(Thread::isAlive));
            assertEquals(1, client.calls.size());
            runtime.round();
            assertEquals(1, client.calls.size());
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(26));
            runtime.round();
            GossipDnsResolverTest.await(() -> metrics.workers == 0 && client.threads.stream().noneMatch(Thread::isAlive));
            assertEquals(2, client.calls.size());
            assertEquals(1, metrics.retryingPeers);
        }
    }

    @Test void localTransportSaturationSkipsWorkWithoutPenalizingThePeer() throws Exception {
        var config = config("A@127.0.0.1:9010");
        var table = new MembershipTable(config, PeerSelectorTest.record("Self", 1, 9000));
        var metrics = new GossipMetrics();
        var client = new Client(false, false);
        client.busy = true;
        try (var runtime = new GossipRuntime(config, table, client, metrics, System::nanoTime,
                new Random(4), new GossipDnsResolver(8, 2))) {
            runtime.round();
            GossipDnsResolverTest.await(() -> metrics.workers == 0 && client.threads.stream().noneMatch(Thread::isAlive));
            assertEquals(1, client.calls.size());
            assertEquals(0, metrics.retryingPeers);
            assertEquals(1, metrics.skipped.get());
            runtime.round();
            GossipDnsResolverTest.await(() -> metrics.workers == 0 && client.threads.stream().noneMatch(Thread::isAlive));
            assertEquals(2, client.calls.size());
        }
    }

    @Test void coldHostnameAliasCannotDialTheSameEndpointTwiceEvenAfterTheFirstCallFinishes() throws Exception {
        var config = config("Peer@wait.test:9010");
        var clock = new AtomicLong();
        var table = new MembershipTable(config, PeerSelectorTest.record("Self", 1, 9000), clock::get);
        table.mergeDirect(PeerSelectorTest.record("Peer", 2, 9010), List.of());
        var metrics = new GossipMetrics();
        var client = new Client(false, false);
        var release = temporary.resolve("release-dns");
        var helpers = new CopyOnWriteArrayList<Process>();
        try (var runtime = new GossipRuntime(config, table, client, metrics, clock::get,
                new Random(5), controlledResolver(release, helpers))) {
            runtime.round();
            GossipDnsResolverTest.await(() -> client.calls.size() == 1 && metrics.workers == 1);
            // The numeric exchange has completed; the alias is deliberately still resolving.
            Files.createFile(release);
            GossipDnsResolverTest.await(() -> metrics.workers == 0 && client.threads.stream().noneMatch(Thread::isAlive));
            assertEquals(1, client.calls.size(), "Resolved destinations must be unique for the whole round, not just while in flight");
            assertEquals(1, metrics.skipped.get());
            assertEquals(0, metrics.retryingPeers);
            assertTrue(client.threads.stream().allMatch(Thread::isVirtual));
            runtime.round();
            GossipDnsResolverTest.await(() -> metrics.workers == 0 && client.threads.stream().noneMatch(Thread::isAlive));
            assertEquals(2, client.calls.size());
            assertEquals(1, metrics.skipped.get(), "Later rounds should coalesce the verified alias before creating a worker");
            assertEquals(1, helpers.size());
        }
        assertTrue(helpers.stream().noneMatch(Process::isAlive));
    }

    @Test void aSuccessfulHostnameBootstrapBecomesOneOrdinaryTargetOnLaterRounds() throws Exception {
        var config = config("Peer@wait.test:9010");
        var clock = new AtomicLong();
        var table = new MembershipTable(config, PeerSelectorTest.record("Self", 1, 9000), clock::get);
        var metrics = new GossipMetrics();
        var calls = new CopyOnWriteArrayList<GossipConfig.Seed>();
        var threads = new CopyOnWriteArrayList<Thread>();
        var client = new GossipRuntime.ExchangeClient() {
            @Override public GossipWire.Exchange exchange(GossipConfig.Seed peer) {
                calls.add(peer); threads.add(Thread.currentThread());
                var record = new MemberRecord(new MemberKey(peer.serverId(), new UUID(0, 2)), peer.host(), peer.port(),
                        1, MemberRecord.State.ALIVE, Map.of());
                table.mergeDirect(record, List.of());
                return new GossipWire.Exchange(record, List.of());
            }
            @Override public void close() { }
        };
        var release = Files.createFile(temporary.resolve("release-dns"));
        var helpers = new CopyOnWriteArrayList<Process>();
        try (var runtime = new GossipRuntime(config, table, client, metrics, clock::get,
                new Random(6), controlledResolver(release, helpers))) {
            for (int round = 1; round <= 3; round++) {
                runtime.round();
                GossipDnsResolverTest.await(() -> metrics.workers == 0 && threads.stream().noneMatch(Thread::isAlive));
                assertEquals(round, calls.size(), "Hostname and advertised address must share one target");
            }
            assertEquals(1, helpers.size());
            assertEquals(0, metrics.skipped.get());
        }
        assertTrue(helpers.stream().noneMatch(Process::isAlive));
    }

    @Test void repeatedSchedulerFailuresAreVisibleAndRateLimitedWithoutStoppingCleanup() throws Exception {
        var config = config("");
        var clock = new AtomicLong();
        var base = PeerSelectorTest.record("Self", 1, 9000);
        var exhausted = new MemberRecord(base.key(), base.host(), base.port(), Long.MAX_VALUE, base.state(), Map.of());
        var table = new MembershipTable(config, exhausted, clock::get);
        var lines = new CopyOnWriteArrayList<String>();
        var metrics = new GossipMetrics(new GossipLog(config, exhausted, clock::get, lines::add));
        var client = new Client(false, false);
        try (var runtime = new GossipRuntime(config, table, client, metrics, clock::get, new Random(7), new GossipDnsResolver(8, 2))) {
            clock.set(TimeUnit.MILLISECONDS.toNanos(20));
            runtime.start();
            GossipDnsResolverTest.await(() -> metrics.roundFailures.get() >= 3);
            assertEquals(1, lines.size());
            assertTrue(lines.getFirst().contains("event=ROUND_FAILED"));
            assertTrue(lines.getFirst().contains("Owner heartbeat is exhausted"));
            runtime.close();
            assertEquals("STOPPED", metrics.workerState);
            assertTrue(client.closed);
            assertEquals(1, lines.size(), "Ordinary successful close must not invent departure or failure logs");
        }
    }

    private GossipDnsResolver controlledResolver(Path release, List<Process> helpers) {
        return new GossipDnsResolver(8, 2, Duration.ofSeconds(5), System::nanoTime, host -> {
            Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
            if (!Files.exists(java)) { java = Path.of(System.getProperty("java.home"), "bin", "java"); }
            try {
                String classpath = Path.of(DnsProcessFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
                var process = new ProcessBuilder(java.toString(), "-cp", classpath, DnsProcessFixture.class.getName(), host, release.toString())
                        .redirectErrorStream(true).start();
                helpers.add(process);
                return process;
            } catch (java.net.URISyntaxException e) { throw new IOException(e); }
        });
    }

    private static final class Client implements GossipRuntime.ExchangeClient {
        final List<GossipConfig.Seed> calls = new CopyOnWriteArrayList<>();
        final List<Thread> threads = new CopyOnWriteArrayList<>();
        final CountDownLatch release = new CountDownLatch(1);
        final boolean block;
        final boolean fail;
        volatile boolean closed;
        boolean busy;
        Client(boolean block, boolean fail) { this.block = block; this.fail = fail; }
        @Override public GossipWire.Exchange exchange(GossipConfig.Seed peer) throws IOException {
            calls.add(peer); threads.add(Thread.currentThread());
            if (busy) { throw new GossipTransport.BusyException(); }
            try { if (block) { release.await(); } }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
            if (fail) { throw new IOException("Unavailable fixture peer"); }
            return new GossipWire.Exchange(PeerSelectorTest.record(peer.serverId(), 2, peer.port()), List.of());
        }
        @Override public void close() { closed = true; release.countDown(); }
    }
}
