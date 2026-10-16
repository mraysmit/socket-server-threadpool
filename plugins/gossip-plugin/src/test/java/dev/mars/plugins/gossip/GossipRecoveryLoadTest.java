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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class GossipRecoveryLoadTest {
    private static List<GossipTestNetwork.Node> swarm(GossipTestNetwork network, int size, String host) throws Exception {
        var nodes = new ArrayList<GossipTestNetwork.Node>();
        for (int i = 0; i < size; i++) {
            String seed = i == 0 ? "" : "Node-0@" + host + ":" + nodes.getFirst().relay().port();
            nodes.add(network.start("Node-" + i, GossipTestNetwork.config(size, size * 2, 3, 4096, host, seed), true));
        }
        return nodes;
    }

    private static boolean allAlive(List<GossipTestNetwork.Node> nodes, int size) throws Exception {
        for (var node : nodes) {
            if (((Number) ((Map<?, ?>) node.status().get("assessments")).get("ALIVE")).intValue() != size) { return false; }
        }
        return true;
    }

    private static Map<String, Map<?, ?>> rows(GossipTestNetwork.Node node) throws Exception {
        var result = new HashMap<String, Map<?, ?>>();
        for (var row : node.members()) { result.put((String) row.get("serverId"), row); }
        return result;
    }

    @Test void sixNodePartitionRecoversCurrentHeartbeatsAndTerminalDepartureAfterHealing() throws Exception {
        try (var network = new GossipTestNetwork()) {
            var nodes = swarm(network, 6, "127.0.0.1");
            long convergence = GossipTestNetwork.await(Duration.ofSeconds(12), () -> allAlive(nodes, 6));
            var departed = nodes.getLast().self();
            network.partition = Set.of("Node-0", "Node-1", "Node-2");
            GossipTestNetwork.await(Duration.ofSeconds(8), () -> {
                for (var node : nodes) {
                    var local = rows(node);
                    for (var peer : nodes) {
                        if (network.partition.contains(node.id()) != network.partition.contains(peer.id())
                                && !"DEAD".equals(local.get(peer.id()).get("assessment"))) { return false; }
                    }
                }
                return true;
            });
            assertTrue(network.dropped.get() > 0, "Partition did not intercept real requests");
            long staleHeartbeat = ((Number) rows(nodes.getFirst()).get("Node-3").get("heartbeat")).longValue();
            GossipTestNetwork.await(Duration.ofSeconds(3), () -> nodes.get(3).self().heartbeat() > staleHeartbeat + 2);
            assertEquals(staleHeartbeat, ((Number) rows(nodes.getFirst()).get("Node-3").get("heartbeat")).longValue(), "Forwarded endpoints bypassed the partition");
            nodes.getLast().client().execute("gossip", Map.of("operation", "leave"));
            GossipTestNetwork.await(Duration.ofSeconds(7), () -> "QUIESCED".equals(nodes.getLast().status().get("workerState"))
                    && "LEFT".equals(rows(nodes.get(3)).get("Node-5").get("state"))
                    && "LEFT".equals(rows(nodes.get(4)).get("Node-5").get("state")));
            assertEquals("ALIVE", rows(nodes.getFirst()).get("Node-5").get("state"), "Departure crossed an unhealed partition");
            var heartbeatFloors = new HashMap<String, Long>();
            for (var node : nodes.subList(0, 5)) { heartbeatFloors.put(node.id(), node.self().heartbeat()); }
            network.partition = null;
            long recovery = GossipTestNetwork.await(Duration.ofSeconds(12), () -> {
                for (var node : nodes.subList(0, 5)) {
                    var local = rows(node);
                    if (local.size() != 6 || !"LEFT".equals(local.get("Node-5").get("state"))) { return false; }
                    for (var floor : heartbeatFloors.entrySet()) {
                        var row = local.get(floor.getKey());
                        if (!"ALIVE".equals(row.get("assessment")) || ((Number) row.get("heartbeat")).longValue() < floor.getValue()) { return false; }
                    }
                }
                return true;
            });
            var wire = new GossipWire(nodes.getFirst().config());
            nodes.getFirst().client().execute("gossip", wire.parameters(nodes.get(3).self(), List.of(departed)));
            assertEquals("LEFT", rows(nodes.getFirst()).get("Node-5").get("state"), "Stale ALIVE revived a departed boot after healing");
            for (var node : nodes) {
                var capacity = (Map<?, ?>) node.status().get("capacity");
                assertEquals(6, capacity.get("retainedIdentities"));
                assertTrue(((Number) capacity.get("activeMembers")).intValue() <= 6);
            }
            System.out.printf("GOSSIP_MEASURE partition nodes=6 batch=3 bytes=4096 convergenceMs=%d recoveryMs=%d dropped=%d terminalPreserved=true%n", convergence, recovery, network.dropped.get());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"127.0.0.1", "localhost"})
    void scheduledSwarmAtItsConfiguredCapBoundsResourcesAndCleansUpWhileAllWorkersAreStalled(String host) throws Exception {
        int size = host.equals("localhost") ? 6 : 12;
        try (var network = new GossipTestNetwork()) {
            long began = System.nanoTime();
            var nodes = swarm(network, size, host);
            try (var monitor = new Monitor(nodes)) {
                GossipTestNetwork.await(Duration.ofSeconds(20), () -> allAlive(nodes, size));
                long convergence = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
                for (var node : nodes) {
                    assertEquals(size, ((Map<?, ?>) node.status().get("capacity")).get("activeMembers"));
                    assertEquals(size, ((Map<?, ?>) node.status().get("capacity")).get("retainedIdentities"));
                }
                network.stalled = true;
                GossipTestNetwork.await(Duration.ofSeconds(5), () -> {
                    for (var node : nodes) {
                        if (((Number) ((Map<?, ?>) node.status().get("runtime")).get("workers")).intValue() != node.config().maxInflight()) { return false; }
                    }
                    return network.active.get() == size * 2;
                });
                long heartbeat = nodes.getFirst().self().heartbeat();
                GossipTestNetwork.await(Duration.ofSeconds(3), () -> nodes.getFirst().self().heartbeat() >= heartbeat + 3);
                assertEquals(size * 2, network.active.get(), "Worker saturation unexpectedly created extra exchanges");
                long cleanupStart = System.nanoTime();
                try (var closers = Executors.newVirtualThreadPerTaskExecutor()) {
                    var closes = new ArrayList<java.util.concurrent.Future<?>>();
                    for (var node : nodes) { closes.add(closers.submit(() -> { node.close(); return null; })); }
                    for (var close : closes) { close.get(5, TimeUnit.SECONDS); }
                }
                GossipTestNetwork.await(Duration.ofSeconds(2), () -> network.active.get() == 0
                        && nodes.stream().allMatch(node -> node.relay().openSockets() == 0));
                long cleanup = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cleanupStart);
                assertTrue(cleanup < 8000, "Saturated cleanup exceeded test deadline");
                for (var node : nodes) {
                    assertEquals("STOPPED", node.status().get("workerState"));
                    var runtime = (Map<?, ?>) node.status().get("runtime");
                    assertEquals(0, runtime.get("workers"));
                    assertEquals(0, runtime.get("dnsHelpers"));
                    assertEquals(0, ((Map<?, ?>) node.status().get("exchanges")).get("inflight"));
                }
                monitor.close();
                assertEquals(2, monitor.peakWorkers, "Test never saturated an instance's worker cap");
                // In-process localhost lookups finish in microseconds, too fast to sample reliably; convergence through
                // the hostname seed already proves they ran. Numeric endpoints must never start one.
                if (!host.equals("localhost")) { assertEquals(0, monitor.peakHelpers); }
                System.out.printf("GOSSIP_MEASURE load host=%s nodes=%d batch=3 bytes=4096 convergenceMs=%d cleanupMs=%d peakWorkersPerNode=%d peakHelpersPerNode=%d peakInflightPerNode=%d peakTotalWorkers=%d relayPeak=%d%n",
                        host, size, convergence, cleanup, monitor.peakWorkers, monitor.peakHelpers, monitor.peakInflight, monitor.peakTotalWorkers, network.peak.get());
            }
        }
    }

    /** Sample public diagnostics during bootstrap, sustained saturation, and shutdown; failures propagate to the test. */
    private static final class Monitor implements AutoCloseable {
        private final Thread worker;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private volatile boolean stopped;
        volatile int peakWorkers;
        volatile int peakHelpers;
        volatile int peakInflight;
        volatile int peakTotalWorkers;
        Monitor(List<GossipTestNetwork.Node> nodes) {
            worker = Thread.ofVirtual().name("gossip-test-resource-sampler").start(() -> {
                try {
                    while (!stopped) {
                        int total = 0;
                        for (var node : nodes) {
                            var state = node.status();
                            var runtime = (Map<?, ?>) state.get("runtime");
                            int workers = ((Number) runtime.get("workers")).intValue();
                            int helpers = ((Number) runtime.get("dnsHelpers")).intValue();
                            int inflight = ((Number) ((Map<?, ?>) state.get("exchanges")).get("inflight")).intValue();
                            assertTrue(workers >= 0 && workers <= node.config().maxInflight());
                            assertTrue(helpers >= 0 && helpers <= node.config().maxInflight());
                            assertTrue(inflight >= 0 && inflight <= node.config().maxInflight());
                            var capacity = (Map<?, ?>) state.get("capacity");
                            assertTrue(((Number) capacity.get("activeMembers")).intValue() <= node.config().maxMembers());
                            assertTrue(((Number) capacity.get("retainedIdentities")).intValue() <= node.config().maxRecords());
                            peakWorkers = Math.max(peakWorkers, workers);
                            peakHelpers = Math.max(peakHelpers, helpers);
                            peakInflight = Math.max(peakInflight, inflight);
                            total += workers;
                        }
                        peakTotalWorkers = Math.max(peakTotalWorkers, total);
                        Thread.sleep(5);
                    }
                } catch (InterruptedException finished) { Thread.currentThread().interrupt(); }
                catch (Throwable e) { failure.set(e); }
            });
        }
        @Override public void close() throws Exception {
            stopped = true;
            worker.interrupt();
            worker.join(Duration.ofSeconds(2));
            assertFalse(worker.isAlive(), "Resource sampler leaked");
            if (failure.get() != null) { throw new AssertionError("Resource caps failed during sampling", failure.get()); }
        }
    }
}
