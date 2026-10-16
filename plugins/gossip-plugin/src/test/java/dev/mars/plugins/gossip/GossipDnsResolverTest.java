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

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class GossipDnsResolverTest {
    /** Threads that ran a lookup, one per lookup actually performed. */
    private final List<Thread> lookups = new CopyOnWriteArrayList<>();

    private InetAddress[] launch(String host) throws Exception {
        lookups.add(Thread.currentThread());
        return fixtureLookup(host, null);
    }

    /**
     * Lookup behaviour chosen by hostname prefix: {@code stall} blocks until interrupted, {@code wait} blocks until
     * {@code release} exists, {@code bad} returns no addresses, {@code fail} is unknown, {@code mixed} returns two
     * ordinary addresses around a loopback one, and anything else resolves to 127.0.0.1 and ::1.
     */
    static InetAddress[] fixtureLookup(String host, Path release) throws Exception {
        if (host.startsWith("stall")) { Thread.sleep(300_000); }
        if (host.startsWith("wait")) {
            while (!java.nio.file.Files.exists(release)) { Thread.sleep(5); }
            return new InetAddress[] {InetAddress.ofLiteral("127.0.0.1")};
        }
        if (host.startsWith("bad")) { return new InetAddress[0]; }
        if (host.startsWith("fail")) { throw new java.net.UnknownHostException(host); }
        if (host.startsWith("mixed")) {
            return new InetAddress[] {InetAddress.ofLiteral("192.0.2.1"), InetAddress.ofLiteral("127.0.0.1"), InetAddress.ofLiteral("192.0.2.2")};
        }
        return new InetAddress[] {InetAddress.ofLiteral("127.0.0.1"), InetAddress.ofLiteral("::1")};
    }

    @Test void actualSystemResolverHelperResolvesLocalhostAndNumericEndpointsNeedNoHelper() throws Exception {
        try (var resolver = new GossipDnsResolver(4, 2)) {
            var peer = resolver.resolve(new GossipConfig.Seed("Peer", "localhost", 9000));
            assertTrue(InetAddress.ofLiteral(peer.host()).isLoopbackAddress());
            assertEquals(1, resolver.cacheSize());
            assertEquals(0, resolver.activeHelpers());
            assertEquals("127.0.0.1", resolver.resolve(new GossipConfig.Seed("Peer", "127.0.0.1", 9000)).host());
            assertEquals(1, resolver.cacheSize());
        }
    }

    @Test void cachedAddressesRotateExpireAndStayWithinTheHostCap() throws Exception {
        var clock = new AtomicLong();
        try (var resolver = new GossipDnsResolver(1, 1, Duration.ofSeconds(2), clock::get, this::launch)) {
            var peer = new GossipConfig.Seed("Peer", "good.test", 9000);
            assertEquals("127.0.0.1", resolver.resolve(peer).host());
            assertTrue(InetAddress.ofLiteral(resolver.resolve(peer).host()).isLoopbackAddress());
            assertEquals(1, lookups.size());
            clock.set(TimeUnit.SECONDS.toNanos(61));
            resolver.resolve(peer);
            assertEquals(2, lookups.size());
            resolver.resolve(new GossipConfig.Seed("Peer", "another.test", 9000));
            assertEquals(1, resolver.cacheSize());
            resolver.resolve(peer);
            assertEquals(4, lookups.size());
        }
        await(() -> lookups.stream().noneMatch(Thread::isAlive));
    }

    @Test void resolveAllReturnsEveryAddressAndRotatesWhichComesFirst() throws Exception {
        try (var resolver = new GossipDnsResolver(4, 1, Duration.ofSeconds(2), System::nanoTime, this::launch)) {
            var peer = new GossipConfig.Seed("Peer", "good.test", 9000);
            var first = resolver.resolveAll(peer);
            var second = resolver.resolveAll(peer);
            assertEquals(List.of("127.0.0.1", "0:0:0:0:0:0:0:1"), first.stream().map(GossipConfig.Seed::host).toList());
            assertEquals(first.reversed(), second, "The first address rotates; the rest remain as fallbacks");
            assertTrue(first.stream().allMatch(endpoint -> endpoint.serverId().equals("Peer") && endpoint.port() == 9000));
            assertEquals(first.getFirst(), resolver.resolve(peer), "resolve returns the next first address");
            assertEquals(1, lookups.size(), "Rotation is served from the cache");
            var numeric = new GossipConfig.Seed("Peer", "127.0.0.1", 9000);
            assertEquals(List.of(numeric), resolver.resolveAll(numeric));
        }
    }

    @Test void concurrentRequestsForOneHostShareASingleLookup(@org.junit.jupiter.api.io.TempDir Path temporary) throws Exception {
        Path release = temporary.resolve("release");
        try (var resolver = new GossipDnsResolver(4, 1, Duration.ofSeconds(5), System::nanoTime, host -> {
                 lookups.add(Thread.currentThread());
                 return fixtureLookup(host, release);
             });
             var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = callers.submit(() -> resolver.resolve(new GossipConfig.Seed("Peer", "wait.test", 9000)));
            await(() -> resolver.activeHelpers() == 1);
            // With one lookup allowed, a second host is refused but the same host joins the lookup in flight.
            assertThrows(IOException.class, () -> resolver.resolve(new GossipConfig.Seed("Peer", "other.test", 9000)));
            var second = callers.submit(() -> resolver.resolve(new GossipConfig.Seed("Peer", "WAIT.test", 9010)));
            java.nio.file.Files.createFile(release);
            assertEquals(new GossipConfig.Seed("Peer", "127.0.0.1", 9000), first.get(5, TimeUnit.SECONDS));
            assertEquals(new GossipConfig.Seed("Peer", "127.0.0.1", 9010), second.get(5, TimeUnit.SECONDS));
            assertEquals(1, lookups.size(), "Both requests must be served by one lookup");
        }
    }

    @Test void timeoutAndInvalidRepliesLeaveNoHelpersOrPoisonedCacheEntries() throws Exception {
        try (var resolver = new GossipDnsResolver(4, 2, Duration.ofMillis(150), System::nanoTime, this::launch)) {
            assertThrows(SocketTimeoutException.class, () -> resolver.resolve(new GossipConfig.Seed("Peer", "stall.test", 9000)));
            for (String host : List.of("bad.test", "fail.test")) {
                assertThrows(IOException.class, () -> resolver.resolve(new GossipConfig.Seed("Peer", host, 9000)));
            }
            await(() -> resolver.activeHelpers() == 0);
            assertEquals(0, resolver.cacheSize());
        }
        await(() -> lookups.stream().noneMatch(Thread::isAlive));
    }

    @Test void closeKillsAnActiveLookupAndUnblocksTheOwnedCaller() throws Exception {
        try (var resolver = new GossipDnsResolver(4, 1, Duration.ofSeconds(10), System::nanoTime, this::launch);
             var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var lookup = worker.submit(() -> resolver.resolve(new GossipConfig.Seed("Peer", "stall.test", 9000)));
            await(() -> resolver.activeHelpers() == 1);
            assertThrows(IOException.class, () -> resolver.resolve(new GossipConfig.Seed("Peer", "other.test", 9000)));
            resolver.close();
            var failure = assertThrows(ExecutionException.class, () -> lookup.get(2, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, failure.getCause());
            assertEquals(0, resolver.activeHelpers());
            await(() -> lookups.stream().noneMatch(Thread::isAlive));
            assertThrows(IOException.class, () -> resolver.resolve(new GossipConfig.Seed("Peer", "127.0.0.1", 9000)));
        }
    }

    @Test void scheduledDnsWorkersAreKilledAndJoinedBeforeRuntimeCloseReturns() throws Exception {
        var config = GossipSwarmTest.config("Peer@stall.test:9010");
        var table = new MembershipTable(config, PeerSelectorTest.record("Self", 1, 9000));
        var metrics = new GossipMetrics();
        var lookupThreads = new CopyOnWriteArrayList<Thread>();
        var resolver = new GossipDnsResolver(8, 2, Duration.ofSeconds(10), System::nanoTime, host -> {
            lookupThreads.add(Thread.currentThread());
            return launch(host);
        });
        var client = new GossipRuntime.ExchangeClient() {
            @Override public GossipWire.Exchange exchange(GossipConfig.Seed peer) { throw new AssertionError("Stalled DNS must not reach TCP"); }
            @Override public void close() { }
        };
        try (var runtime = new GossipRuntime(config, table, client, metrics, System::nanoTime, new java.util.Random(1), resolver)) {
            runtime.start();
            await(() -> resolver.activeHelpers() == 1);
            runtime.close();
            assertEquals("STOPPED", metrics.workerState);
            assertEquals(0, metrics.workers);
            assertTrue(lookupThreads.stream().noneMatch(Thread::isAlive));
            await(() -> lookups.stream().noneMatch(Thread::isAlive));
        }
    }

    @Test void departureDeadlineKillsAStalledDnsHelperAndJoinsItsWorker() throws Exception {
        var config = GossipSwarmTest.config("Peer@stall.test:9010");
        var table = new MembershipTable(config, PeerSelectorTest.record("Self", 1, 9000));
        var metrics = new GossipMetrics();
        var callers = new CopyOnWriteArrayList<Thread>();
        var resolver = new GossipDnsResolver(8, 2, Duration.ofSeconds(10), System::nanoTime, host -> {
            callers.add(Thread.currentThread());
            return launch(host);
        });
        var client = new GossipRuntime.ExchangeClient() {
            @Override public GossipWire.Exchange exchange(GossipConfig.Seed peer) { throw new AssertionError("Stalled DNS must not reach TCP"); }
            @Override public void close() { }
        };
        try (var runtime = new GossipRuntime(config, table, client, metrics, System::nanoTime,
                new java.util.Random(1), resolver, Duration.ofMillis(500))) {
            runtime.leave();
            await(() -> resolver.activeHelpers() == 1);
            await(() -> "QUIESCED".equals(metrics.workerState));
            assertEquals("EXHAUSTED", runtime.departure().get("state"));
            assertEquals(1, runtime.departure().get("cancelled"));
            assertEquals(0, resolver.activeHelpers());
            assertTrue(callers.stream().noneMatch(Thread::isAlive));
            await(() -> lookups.stream().noneMatch(Thread::isAlive));
        }
    }

    static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) { Thread.sleep(10); }
        assertTrue(condition.getAsBoolean(), "Condition did not become true before deadline");
    }
}
