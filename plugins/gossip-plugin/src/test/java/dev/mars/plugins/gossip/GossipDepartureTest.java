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

import dev.mars.client.TextMessageClient;
import dev.mars.plugin.InvalidParametersException;
import dev.mars.testing.TestServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class GossipDepartureTest {
    @Test void concurrentLeavePublishesOnceAndAttemptsEachBoundedTargetOnce() throws Exception {
        var config = GossipSwarmTest.config("Good@127.0.0.1:9010,Bad@127.0.0.1:9020,Self@127.0.0.1:9000");
        var table = new MembershipTable(config, PeerSelectorTest.record("Self", 1, 9000));
        var logs = new CopyOnWriteArrayList<String>();
        var metrics = new GossipMetrics(new GossipLog(config, table.snapshot().self(), System::nanoTime, logs::add));
        var client = new DepartureClient(table, false);
        try (var runtime = new GossipRuntime(config, table, client, metrics, System::nanoTime,
                new Random(1), new GossipDnsResolver(8, 2));
             var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            var requests = new java.util.ArrayList<java.util.concurrent.Future<Map<String, Object>>>();
            for (int i = 0; i < 16; i++) {
                requests.add(callers.submit(() -> { gate.await(); return runtime.leave(); }));
            }
            gate.countDown();
            for (var request : requests) { assertEquals(2, request.get(2, TimeUnit.SECONDS).get("targets")); }
            GossipDnsResolverTest.await(() -> runtime.state() == GossipRuntime.State.QUIESCED);
            GossipDnsResolverTest.await(() -> logs.size() == 1);
            var departure = runtime.departure();
            assertEquals("COMPLETED", departure.get("state"));
            assertEquals(2, departure.get("attempts"));
            assertEquals(1, departure.get("successes"));
            assertEquals(1, departure.get("failures"));
            assertEquals(0, departure.get("cancelled"));
            assertEquals(2, client.calls.stream().distinct().count());
            assertEquals(List.of(MemberRecord.State.LEFT, MemberRecord.State.LEFT), client.states);
            assertEquals(1, table.snapshot().self().heartbeat());
            assertEquals(departure, runtime.leave());
            assertTrue(client.threads.stream().noneMatch(Thread::isAlive));
            assertTrue(client.closed);
            assertThrows(IOException.class, runtime::requireActiveExchange);
            runtime.close();
            assertEquals(departure, runtime.departure());
            assertEquals(1, logs.size(), "Repeated leave/close must not duplicate the outcome log");
            for (var field : List.of("event=DEPARTURE_FINISHED", "state=COMPLETED", "targets=2", "attempts=2", "successes=1", "failures=1")) {
                assertTrue(logs.getFirst().contains(field), logs.getFirst());
            }
        }
    }

    @Test void finiteDepartureDeadlineCancelsStalledWorkersAndFreezesHeartbeat() throws Exception {
        var config = GossipSwarmTest.config("Good@127.0.0.1:9010");
        var table = new MembershipTable(config, PeerSelectorTest.record("Self", 1, 9000));
        var metrics = new GossipMetrics();
        var client = new DepartureClient(table, true);
        try (var runtime = new GossipRuntime(config, table, client, metrics, System::nanoTime, new Random(2),
                new GossipDnsResolver(8, 2), Duration.ofMillis(200))) {
            long began = System.nanoTime();
            runtime.leave();
            assertTrue(System.nanoTime() - began < TimeUnit.SECONDS.toNanos(1));
            assertTrue(client.entered.await(2, TimeUnit.SECONDS));
            GossipDnsResolverTest.await(() -> runtime.state() == GossipRuntime.State.QUIESCED);
            assertEquals("EXHAUSTED", runtime.departure().get("state"));
            assertEquals(1, runtime.departure().get("attempts"));
            assertEquals(1, runtime.departure().get("cancelled"));
            assertEquals(0, metrics.workers);
            assertTrue(client.threads.stream().noneMatch(Thread::isAlive));
            assertEquals(MemberRecord.State.LEFT, table.snapshot().self().state());
            assertEquals(1, table.snapshot().self().heartbeat());
        }
    }

    @Test void stopDuringDepartureCancelsImmediatelyAndRemainsIdempotent() throws Exception {
        var config = GossipSwarmTest.config("Good@127.0.0.1:9010");
        var table = new MembershipTable(config, PeerSelectorTest.record("Self", 1, 9000));
        var metrics = new GossipMetrics();
        var client = new DepartureClient(table, true);
        try (var runtime = new GossipRuntime(config, table, client, metrics, System::nanoTime,
                new Random(3), new GossipDnsResolver(8, 2));
             var stoppers = Executors.newVirtualThreadPerTaskExecutor()) {
            runtime.leave();
            assertTrue(client.entered.await(2, TimeUnit.SECONDS));
            var first = stoppers.submit(() -> { runtime.close(); return null; });
            var second = stoppers.submit(() -> { runtime.close(); return null; });
            first.get(2, TimeUnit.SECONDS); second.get(2, TimeUnit.SECONDS);
            assertEquals("CANCELLED", runtime.departure().get("state"));
            assertEquals(GossipRuntime.State.STOPPED, runtime.state());
            assertTrue(client.threads.stream().noneMatch(Thread::isAlive));
            assertEquals(runtime.departure(), runtime.leave());
        }
    }

    @Test void isolatedLeaveCompletesAndLeaveAfterUnrequestedStopIsRejected() throws Exception {
        var config = GossipSwarmTest.config("");
        var table = new MembershipTable(config, PeerSelectorTest.record("Self", 1, 9000));
        var metrics = new GossipMetrics();
        try (var runtime = new GossipRuntime(config, table, new DepartureClient(table, false), metrics,
                System::nanoTime, new Random(4), new GossipDnsResolver(8, 2))) {
            runtime.leave();
            GossipDnsResolverTest.await(() -> "COMPLETED".equals(runtime.departure().get("state")));
            assertEquals(0, runtime.departure().get("targets"));
            assertEquals(0, runtime.departure().get("attempts"));
        }
        var plugin = new GossipPlugin(config, false);
        plugin.start(new dev.mars.plugin.PluginContext("Self", 9000, 9001));
        plugin.stop();
        assertThrows(InvalidParametersException.class, () -> plugin.execute(Map.of("operation", "leave")));
    }

    @Test void threeNodeDeparturePropagatesAndStaleAliveCannotReviveItWhileOtherNodesKeepWorking() throws Exception {
        var owner = new GossipPlugin(GossipSwarmTest.config(""));
        try (var a = TestServer.start("Owner", owner)) {
            var seed = "Owner@127.0.0.1:" + a.getPort();
            var departing = new GossipPlugin(GossipSwarmTest.config(seed));
            var other = new GossipPlugin(GossipSwarmTest.config(seed));
            try (var b = TestServer.start("Departing", departing);
                 var c = TestServer.start("Other", other)) {
                GossipDnsResolverTest.await(() -> {
                    try { return GossipSwarmTest.members(departing).size() == 3 && GossipSwarmTest.members(other).size() == 3; }
                    catch (Exception e) { throw new RuntimeException(e); }
                });
                var before = MemberRecord.fromWire(GossipSwarmTest.status(departing).get("self"));
                var client = new TextMessageClient("127.0.0.1", b.getPort(), "Departing");
                assertThrows(dev.mars.client.CommandExecutionException.class,
                        () -> client.execute("gossip", Map.of("operation", "leave", "extra", true)));
                assertEquals("ALIVE", ((Map<?, ?>) GossipSwarmTest.status(departing).get("self")).get("state"));
                client.execute("gossip", Map.of("operation", "leave"));
                GossipDnsResolverTest.await(() -> {
                    try {
                        return "QUIESCED".equals(GossipSwarmTest.status(departing).get("workerState"))
                                && hasLeft(owner, before.key()) && hasLeft(other, before.key());
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
                var departure = (Map<?, ?>) GossipSwarmTest.status(departing).get("departure");
                assertEquals("COMPLETED", departure.get("state"));
                assertTrue(((Number) departure.get("successes")).intValue() > 0);
                var wire = new GossipWire(GossipSwarmTest.config(""));
                var sender = MemberRecord.fromWire(GossipSwarmTest.status(other).get("self"));
                owner.execute(wire.parameters(sender, List.of(before)));
                assertTrue(hasLeft(owner, before.key()));
                var repeated = (Map<?, ?>) client.execute("gossip", Map.of("operation", "leave")).result();
                assertEquals(normalize(departure), normalize((Map<?, ?>) repeated.get("departure")));
                assertThrows(IOException.class, () -> departing.exchangeWith(new GossipConfig.Seed("Owner", "127.0.0.1", a.getPort())));
                assertEquals("Received: after leave", client.send("after leave").text());
                assertFalse(GossipSwarmTest.members(departing).isEmpty());
                var replyAfterQuiescing = other.exchangeWith(new GossipConfig.Seed("Departing", "127.0.0.1", b.getPort()));
                assertEquals(MemberRecord.State.LEFT, replyAfterQuiescing.sender().state());
                assertEquals("RUNNING", GossipSwarmTest.status(other).get("workerState"));
                Object heartbeat = ((Map<?, ?>) GossipSwarmTest.status(departing).get("self")).get("heartbeat");
                Thread.sleep(180);
                assertEquals(heartbeat, ((Map<?, ?>) GossipSwarmTest.status(departing).get("self")).get("heartbeat"));
                b.close(); b.close();
                assertEquals("STOPPED", GossipSwarmTest.status(departing).get("workerState"));
            }
        }
    }

    @Test void departedOriginAcceptsPeerExchangeForwardingItsLastAliveRecord() throws Exception {
        var config = GossipSwarmTest.config("");
        var departing = new GossipPlugin(config, false);
        var peer = new GossipPlugin(config, false);
        try (var origin = TestServer.start("Departing", departing);
             var remote = TestServer.start("Peer", peer)) {
            var stale = MemberRecord.fromWire(GossipSwarmTest.status(departing).get("self"));
            var sender = MemberRecord.fromWire(GossipSwarmTest.status(peer).get("self"));
            var wire = new GossipWire(config);
            // The peer learns ALIVE without introducing itself to the origin, so leave has no outbound targets.
            peer.execute(wire.parameters(stale, List.of()));
            var client = new TextMessageClient("127.0.0.1", origin.getPort(), "Departing");
            client.execute("gossip", Map.of("operation", "leave"));
            GossipDnsResolverTest.await(() -> {
                try { return "QUIESCED".equals(GossipSwarmTest.status(departing).get("workerState")); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            assertFalse(hasLeft(peer, stale.key()), "Peer must still carry the pre-departure ALIVE record");

            var reply = peer.exchangeWith(new GossipConfig.Seed("Departing", "127.0.0.1", origin.getPort()));

            assertEquals(stale.key(), reply.sender().key());
            assertEquals(stale.heartbeat(), reply.sender().heartbeat());
            assertEquals(MemberRecord.State.LEFT, reply.sender().state());
            assertTrue(hasLeft(peer, stale.key()), "The successful reply must teach the stale peer LEFT");
            assertTrue(GossipSwarmTest.members(departing).stream()
                    .anyMatch(row -> sender.key().bootId().toString().equals(row.get("bootId"))));
            assertEquals(0L, ((Number) ((Map<?, ?>) GossipSwarmTest.status(departing).get("exchanges")).get("rejected")).longValue());
            System.out.println("Departure replay test PASSED | origin=Departing | peer=Peer | stale=ALIVE | reply=LEFT");
        }
    }

    @Test void actualStalledTcpDepartureIsCancelledAtTheDocumentedDeadline() throws Exception {
        try (var peer = new GossipSwarmTest.StalledPeer()) {
            // Disable ordinary rounds so the first wire request must carry LEFT.
            var plugin = new GossipPlugin(GossipSwarmTest.config("Stalled@127.0.0.1:" + peer.port()), false);
            try (var server = TestServer.start("Self", plugin)) {
                var client = new TextMessageClient("127.0.0.1", server.getPort(), "Self");
                long began = System.nanoTime();
                client.execute("gossip", Map.of("operation", "leave"));
                assertTrue(System.nanoTime() - began < TimeUnit.SECONDS.toNanos(2), "Leave handler blocked on outbound I/O");
                var request = dev.mars.protocol.CommandProtocol.parseRequest(peer.payload.get(2, TimeUnit.SECONDS));
                assertEquals("LEFT", ((Map<?, ?>) request.parameters().get("sender")).get("state"));
                assertTrue(peer.closed.get(7, TimeUnit.SECONDS));
                GossipDnsResolverTest.await(() -> {
                    try { return "QUIESCED".equals(GossipSwarmTest.status(plugin).get("workerState")); }
                    catch (Exception e) { throw new RuntimeException(e); }
                });
                assertTrue(System.nanoTime() - began < TimeUnit.SECONDS.toNanos(8));
                var departure = (Map<?, ?>) GossipSwarmTest.status(plugin).get("departure");
                assertEquals("EXHAUSTED", departure.get("state"));
                assertEquals(1, departure.get("cancelled"));
                assertEquals(0, ((Map<?, ?>) GossipSwarmTest.status(plugin).get("runtime")).get("workers"));
            }
        }
    }

    @Test void leaveCancelsAnExistingAliveExchangeBeforeStartingItsTerminalAttempt() throws Exception {
        var config = GossipSwarmTest.config("Good@127.0.0.1:9010");
        var table = new MembershipTable(config, PeerSelectorTest.record("Self", 1, 9000));
        var metrics = new GossipMetrics();
        var client = new DepartureClient(table, true);
        try (var runtime = new GossipRuntime(config, table, client, metrics, System::nanoTime,
                new Random(5), new GossipDnsResolver(8, 2))) {
            runtime.start();
            assertTrue(client.entered.await(2, TimeUnit.SECONDS));
            assertEquals(List.of(MemberRecord.State.ALIVE), client.states);
            runtime.leave();
            // The client records each state after its call, so wait for the state, or the check can see one of two.
            GossipDnsResolverTest.await(() -> client.states.size() == 2);
            assertEquals(List.of(MemberRecord.State.ALIVE, MemberRecord.State.LEFT), client.states);
            client.release.countDown();
            GossipDnsResolverTest.await(() -> runtime.state() == GossipRuntime.State.QUIESCED);
            assertEquals("COMPLETED", runtime.departure().get("state"));
            assertEquals(1, runtime.departure().get("attempts"));
            assertEquals(1, runtime.departure().get("successes"));
            assertTrue(client.threads.stream().noneMatch(Thread::isAlive));
        }
    }

    @Test void cleanupFailureIsReportedAndPreservedForRepeatedStop() throws Exception {
        var config = GossipSwarmTest.config("");
        var table = new MembershipTable(config, PeerSelectorTest.record("Self", 1, 9000));
        var logs = new CopyOnWriteArrayList<String>();
        var metrics = new GossipMetrics(new GossipLog(config, table.snapshot().self(), System::nanoTime, logs::add));
        var expected = new IOException("Fixture cleanup failure");
        var client = new GossipRuntime.ExchangeClient() {
            @Override public GossipWire.Exchange exchange(GossipConfig.Seed peer) { throw new AssertionError("No targets"); }
            @Override public void close() throws IOException { throw expected; }
        };
        var runtime = new GossipRuntime(config, table, client, metrics, System::nanoTime,
                new Random(6), new GossipDnsResolver(8, 2));
        try {
            runtime.leave();
            GossipDnsResolverTest.await(() -> "FAILED".equals(runtime.departure().get("state")));
            GossipDnsResolverTest.await(() -> logs.size() == 2);
            assertEquals(GossipRuntime.State.CLEANUP_FAILED, runtime.state());
            assertSame(expected, assertThrows(IOException.class, runtime::close));
            assertEquals(1, logs.stream().filter(line -> line.contains("event=CLEANUP_FAILED")).count());
            assertTrue(logs.getFirst().contains("resource=transport"));
            assertTrue(logs.getFirst().contains("IOException: Fixture cleanup failure"));
            assertTrue(logs.getLast().contains("event=DEPARTURE_FINISHED"));
            assertTrue(logs.getLast().contains("state=FAILED"));
            assertEquals(2, logs.size());
        } finally { assertSame(expected, assertThrows(IOException.class, runtime::close)); }
    }

    @Test void aBrokenLogSinkCannotMaskCleanupFailureOrRepeatItOnLaterClose() throws Exception {
        var config = GossipSwarmTest.config("");
        var table = new MembershipTable(config, PeerSelectorTest.record("Self", 1, 9000));
        var attemptedLogs = new java.util.concurrent.atomic.AtomicInteger();
        var metrics = new GossipMetrics(new GossipLog(config, table.snapshot().self(), System::nanoTime, line -> {
            attemptedLogs.incrementAndGet();
            throw new IllegalStateException("Broken diagnostic sink");
        }));
        var expected = new IOException("Cleanup must remain the primary failure");
        var client = new GossipRuntime.ExchangeClient() {
            @Override public GossipWire.Exchange exchange(GossipConfig.Seed peer) { throw new AssertionError("No targets"); }
            @Override public void close() throws IOException { throw expected; }
        };
        var runtime = new GossipRuntime(config, table, client, metrics, System::nanoTime,
                new Random(7), new GossipDnsResolver(8, 2));
        assertSame(expected, assertThrows(IOException.class, runtime::close));
        assertSame(expected, assertThrows(IOException.class, runtime::close));
        assertEquals(GossipRuntime.State.CLEANUP_FAILED, runtime.state());
        assertEquals("NOT_REQUESTED", runtime.departure().get("state"));
        assertEquals(1, attemptedLogs.get());
    }

    @Test void administrativeShutdownCancelsAnActiveDepartureWithoutWaitingForItsBudget() throws Exception {
        try (var peer = new GossipSwarmTest.StalledPeer()) {
            var plugin = new GossipPlugin(GossipSwarmTest.config("Stalled@127.0.0.1:" + peer.port()), false);
            try (var server = TestServer.start("Self", plugin)) {
                plugin.execute(Map.of("operation", "leave"));
                assertTrue(peer.request.get(2, TimeUnit.SECONDS));
                long began = System.nanoTime();
                try (var socket = new java.net.Socket("127.0.0.1", server.getAdminPort())) {
                    socket.setSoTimeout(2000);
                    socket.getOutputStream().write("shutdown\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    assertEquals("Shutting down server...", new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8)).readLine());
                }
                assertTrue(peer.closed.get(2, TimeUnit.SECONDS));
                GossipDnsResolverTest.await(() -> {
                    try { return "CANCELLED".equals(((Map<?, ?>) GossipSwarmTest.status(plugin).get("departure")).get("state")); }
                    catch (Exception e) { throw new RuntimeException(e); }
                });
                assertTrue(System.nanoTime() - began < TimeUnit.SECONDS.toNanos(3));
                server.close(); server.close();
                assertEquals("STOPPED", GossipSwarmTest.status(plugin).get("workerState"));
            }
        }
    }

    private static Map<String, Object> normalize(Map<?, ?> wire) {
        var result = new java.util.LinkedHashMap<String, Object>();
        wire.forEach((key, value) -> result.put((String) key, value instanceof Number number ? number.longValue() : value));
        return result;
    }

    private static boolean hasLeft(GossipPlugin plugin, MemberKey key) throws Exception {
        return GossipSwarmTest.members(plugin).stream().anyMatch(row -> key.bootId().toString().equals(row.get("bootId")) && "LEFT".equals(row.get("state")));
    }

    private static final class DepartureClient implements GossipRuntime.ExchangeClient {
        final MembershipTable table;
        final boolean stall;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<MemberRecord.State> states = new CopyOnWriteArrayList<>();
        final List<Thread> threads = new CopyOnWriteArrayList<>();
        volatile boolean closed;
        DepartureClient(MembershipTable table, boolean stall) { this.table = table; this.stall = stall; }
        @Override public GossipWire.Exchange exchange(GossipConfig.Seed peer) throws IOException {
            threads.add(Thread.currentThread());
            calls.add(peer.serverId());
            states.add(table.snapshot().self().state());
            entered.countDown();
            if (stall) {
                try { release.await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
            }
            if (peer.serverId().equals("Bad")) { throw new IOException("Unavailable peer"); }
            return new GossipWire.Exchange(PeerSelectorTest.record(peer.serverId(), 2, peer.port()), List.of(), 1, null);
        }
        @Override public void close() { closed = true; release.countDown(); }
    }
}
