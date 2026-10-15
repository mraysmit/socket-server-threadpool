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

import dev.mars.client.CommandExecutionException;
import dev.mars.plugin.CommandPlugin;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

/** Per-instance jittered rounds and a bounded set of owned virtual exchange workers; no task queue. */
final class GossipRuntime implements AutoCloseable {
    interface ExchangeClient extends AutoCloseable {
        GossipWire.Exchange exchange(GossipConfig.Seed peer) throws IOException;
        default int inflight() { return 0; }
        @Override void close() throws IOException;
    }
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration DEPARTURE_TIMEOUT = Duration.ofSeconds(5);
    private final GossipConfig config;
    private final MembershipTable table;
    private final ExchangeClient transport;
    private final GossipDnsResolver resolver;
    private final GossipMetrics metrics;
    private final LongSupplier clock;
    private final PeerSelector selector;
    private final GossipLog log;
    private final Map<GossipConfig.Seed, Thread> workers = new HashMap<>();
    private final Set<Thread> ownedWorkers = new HashSet<>();
    private final Set<String> resolvedInFlight = new HashSet<>();
    private final Set<InetAddress> localAddresses = new HashSet<>();
    private Thread scheduler;
    private boolean stopped;
    private boolean started;
    private long lastHeartbeat;
    private IOException stopFailure;
    private final ReentrantLock closeLock = new ReentrantLock();
    private final long departureNanos;
    private final Set<GossipConfig.Seed> departurePending = new LinkedHashSet<>();
    private boolean leaving;
    private long departureStarted;
    private String departureState = "NOT_REQUESTED";
    private String departureOutcome;
    private int departureTargets;
    private int departureAttempts;
    private int departureSuccesses;
    private int departureFailures;
    private int departureSkipped;
    private int departureCancelled;
    private boolean departureLogged;

    GossipRuntime(GossipConfig config, MembershipTable table, GossipTransport transport, GossipMetrics metrics) throws IOException {
        this(config, table, transport, metrics, System::nanoTime, new Random(),
                new GossipDnsResolver(config.maxMembers(), config.maxInflight()));
    }

    GossipRuntime(GossipConfig config, MembershipTable table, ExchangeClient transport, GossipMetrics metrics,
                  LongSupplier clock, RandomGenerator random, GossipDnsResolver resolver) throws IOException {
        this(config, table, transport, metrics, clock, random, resolver, DEPARTURE_TIMEOUT);
    }

    GossipRuntime(GossipConfig config, MembershipTable table, ExchangeClient transport, GossipMetrics metrics,
                  LongSupplier clock, RandomGenerator random, GossipDnsResolver resolver, Duration departureTimeout) throws IOException {
        this.config = config;
        this.table = table;
        this.transport = transport;
        this.metrics = metrics;
        log = metrics.log(config, table.snapshot().self());
        this.clock = clock;
        this.resolver = resolver;
        if (departureTimeout.isNegative() || departureTimeout.isZero()) { throw new IllegalArgumentException("Positive departure timeout required"); }
        departureNanos = departureTimeout.toNanos();
        selector = new PeerSelector(config, clock, random);
        lastHeartbeat = clock.getAsLong();
        try {
            var interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    var addresses = interfaces.nextElement().getInetAddresses();
                    while (addresses.hasMoreElements()) { localAddresses.add(addresses.nextElement()); }
                }
            }
        } catch (java.net.SocketException unavailable) { /* Exact configured self endpoints still remain excluded. */ }
    }

    synchronized void start() {
        if (started || stopped) { throw new IllegalStateException("Gossip runtime cannot restart"); }
        started = true;
        metrics.workerState = "RUNNING";
        String id = table.snapshot().self().key().serverId();
        scheduler = Thread.ofVirtual().name("gossip-" + id + "-rounds").unstarted(this::run);
        scheduler.setContextClassLoader(CommandPlugin.class.getClassLoader());
        scheduler.start();
    }

    private void run() {
        try {
            boolean first = true;
            while (true) {
                synchronized (this) {
                    if (stopped || departureOutcome != null) { break; }
                    long delay = leaving ? TimeUnit.MILLISECONDS.toNanos(25)
                            : first ? selector.initialDelay() : selector.roundDelay();
                    TimeUnit.NANOSECONDS.timedWait(this, delay);
                    if (stopped) { break; }
                }
                try { round(); }
                catch (RuntimeException e) { metrics.roundFailures.incrementAndGet(); log.roundFailure(e); }
                first = false;
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        finally {
            // Never wait for a concurrent stop that is joining this scheduler.
            if (closeLock.tryLock()) {
                try {
                    boolean finish;
                    synchronized (this) {
                        finish = leaving && !stopped;
                        if (finish && departureOutcome == null) { departureOutcome = "FAILED"; }
                    }
                    if (finish) {
                        try { closeOwned(true); }
                        catch (IOException e) { /* Retained in stopFailure and diagnostic state. */ }
                    }
                } finally { closeLock.unlock(); }
            }
            synchronized (this) { if (!stopped) { metrics.workerState = "FAILED"; } }
        }
    }

    /** Publish locally and wake the owned scheduler; no DNS, sockets, or joins on the caller. */
    synchronized Map<String, Object> leave() {
        if (leaving) { return departure(); }
        if (stopped) { throw GossipWire.invalid("Cannot leave a stopped gossip runtime"); }
        var snapshot = table.snapshot();
        int cap = Math.max(0, config.maxMembers() - 1);
        for (var member : snapshot.members()) {
            var record = member.record();
            if (record.key().equals(snapshot.self().key()) || record.state() == MemberRecord.State.LEFT) { continue; }
            addDepartureTarget(new GossipConfig.Seed(record.key().serverId(), record.host(), record.port()), snapshot.self(), cap);
        }
        for (var seed : config.seeds()) { addDepartureTarget(seed, snapshot.self(), cap); }
        table.leaveLocal();
        leaving = true;
        departureStarted = System.nanoTime();
        departureState = "DISSEMINATING";
        departureTargets = departurePending.size();
        metrics.workerState = "LEAVING";
        for (var worker : ownedWorkers) { worker.interrupt(); }
        if (!started) { start(); metrics.workerState = "LEAVING"; }
        notifyAll();
        return departure();
    }

    private void addDepartureTarget(GossipConfig.Seed peer, MemberRecord self, int cap) {
        if (departurePending.size() < cap && !PeerSelector.isSelf(self, peer)) { departurePending.add(peer); }
    }

    synchronized Map<String, Object> departure() {
        return Map.of("state", departureState, "budgetMillis", departureNanos / 1_000_000,
                "targets", departureTargets, "attempts", departureAttempts, "successes", departureSuccesses,
                "failures", departureFailures, "skipped", departureSkipped, "cancelled", departureCancelled);
    }

    synchronized void requireActiveExchange() throws IOException {
        if (leaving || stopped) { throw new IOException("Gossip runtime is leaving or stopped"); }
    }

    /** Package seam for deterministic-clock algorithm tests; production calls only from the owned scheduler. */
    synchronized void round() {
        if (stopped) { return; }
        long now = clock.getAsLong();
        metrics.rounds.incrementAndGet();
        metrics.lastRound = System.nanoTime();
        metrics.dnsHelpers = resolver.activeHelpers();
        if (!leaving && now - lastHeartbeat >= config.heartbeatMillis() * 1_000_000) {
            table.advanceLocal();
            lastHeartbeat = now;
        }
        table.maintain();
        ownedWorkers.removeIf(thread -> !thread.isAlive());
        if (leaving) {
            if (departurePending.isEmpty() && ownedWorkers.isEmpty()) { departureOutcome = "COMPLETED"; return; }
            if (System.nanoTime() - departureStarted >= departureNanos) { departureOutcome = "EXHAUSTED"; return; }
        }
        int slots = config.maxInflight() - Math.max(ownedWorkers.size(), transport.inflight());
        if (slots <= 0) { metrics.skipped.incrementAndGet(); return; }
        List<GossipConfig.Seed> peers;
        if (leaving) {
            peers = departurePending.stream().filter(peer -> !workers.containsKey(peer))
                    .limit(Math.min(config.fanout(), slots)).toList();
        } else { peers = selector.select(table.snapshot(), Set.copyOf(workers.keySet()), slots); }
        var roundDestinations = new HashSet<String>();
        for (var peer : peers) {
            String id = table.snapshot().self().key().serverId();
            boolean departing = leaving;
            var worker = Thread.ofVirtual().name("gossip-" + id + "-exchange").unstarted(() -> exchange(peer, departing, roundDestinations));
            worker.setContextClassLoader(CommandPlugin.class.getClassLoader());
            workers.put(peer, worker);
            ownedWorkers.add(worker);
            metrics.workers = workers.size();
            if (departing) { departurePending.remove(peer); departureAttempts++; }
            try { worker.start(); }
            catch (RuntimeException | Error e) {
                workers.remove(peer); ownedWorkers.remove(worker); metrics.workers = workers.size();
                if (departing) { departureFailures++; }
                throw e;
            }
        }
    }

    private void exchange(GossipConfig.Seed peer, boolean departing, Set<String> roundDestinations) {
        String resolvedKey = null;
        boolean acquired = false;
        boolean resolving = true;
        String outcome = "CANCELLED";
        try {
            metrics.dnsHelpers = resolver.activeHelpers();
            var endpoint = resolver.resolve(peer);
            resolving = false;
            InetAddress address = InetAddress.ofLiteral(endpoint.host());
            resolvedKey = endpoint.host() + ":" + endpoint.port();
            synchronized (this) {
                if (stopped || (!departing && leaving)) { return; }
                selector.resolved(peer, endpoint, table.snapshot());
                if (endpoint.port() == table.snapshot().self().port() && (address.isLoopbackAddress() || localAddresses.contains(address))) {
                    metrics.skipped.incrementAndGet();
                    outcome = "SKIPPED";
                    return;
                }
                if (!roundDestinations.add(resolvedKey) || !resolvedInFlight.add(resolvedKey)) {
                    resolvedKey = null; metrics.skipped.incrementAndGet(); outcome = "SKIPPED"; return;
                }
                acquired = true;
            }
            var exchange = transport.exchange(endpoint);
            outcome = "SUCCESS";
            if (!departing && exchange.senderAdmission() == MembershipTable.Admission.CAPACITY) {
                selector.capacity(peer, endpoint);
                metrics.failure(GossipMetrics.Failure.CAPACITY);
            } else { selector.success(peer, endpoint); }
        } catch (IOException | RuntimeException e) {
            synchronized (this) {
                if (!stopped && (departing || !leaving)) {
                    outcome = "FAILURE";
                    if (e instanceof GossipTransport.BusyException) { metrics.skipped.incrementAndGet(); outcome = "SKIPPED"; }
                    else {
                        selector.failure(peer);
                        metrics.failure(resolving ? GossipMetrics.Failure.DNS : classify(e));
                    }
                }
            }
        } finally {
            synchronized (this) {
                if (departing) {
                    switch (outcome) {
                        case "SUCCESS" -> departureSuccesses++;
                        case "FAILURE" -> departureFailures++;
                        case "SKIPPED" -> departureSkipped++;
                        default -> departureCancelled++;
                    }
                }
                if (acquired) { resolvedInFlight.remove(resolvedKey); }
                workers.remove(peer);
                metrics.workers = workers.size();
                metrics.dnsHelpers = resolver.activeHelpers();
                metrics.retryingPeers = selector.retryCount();
            }
        }
    }

    static GossipMetrics.Failure classify(Exception failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException) { return GossipMetrics.Failure.TIMEOUT; }
            if (cause instanceof java.nio.charset.CharacterCodingException) { return GossipMetrics.Failure.PROTOCOL; }
            if (cause instanceof MembershipException member) {
                if (member.reason() == MembershipException.Reason.CAPACITY) { return GossipMetrics.Failure.CAPACITY; }
                if (member.reason() == MembershipException.Reason.CONFLICT) { return GossipMetrics.Failure.IDENTITY; }
                return classifyProtocol(member.getMessage());
            }
            if (cause instanceof CommandExecutionException remote) { return classifyProtocol(remote.reply().error().message()); }
        }
        String message = failure.getMessage();
        if (message != null && (message.contains("mismatch") || message.contains("disagrees"))) { return GossipMetrics.Failure.IDENTITY; }
        if (message != null && message.startsWith("Invalid message reply")) { return GossipMetrics.Failure.PROTOCOL; }
        if (message != null && message.contains("exceeds max.exchange.bytes")) { return GossipMetrics.Failure.PROTOCOL; }
        return GossipMetrics.Failure.NETWORK;
    }

    private static GossipMetrics.Failure classifyProtocol(String message) {
        if (message.contains("CAPACITY:")) { return GossipMetrics.Failure.CAPACITY; }
        if (message.contains("CONFLICT:")) { return GossipMetrics.Failure.IDENTITY; }
        if (message.contains("gossipVersion")) { return GossipMetrics.Failure.VERSION; }
        if (message.contains("swarmId")) { return GossipMetrics.Failure.SWARM; }
        return GossipMetrics.Failure.PROTOCOL;
    }

    @Override public void close() throws IOException {
        closeLock.lock();
        try { closeOwned(false); }
        finally { closeLock.unlock(); }
    }

    private void closeOwned(boolean departed) throws IOException {
        List<Thread> owned;
        synchronized (this) {
            if (stopped) {
                if (stopFailure != null) { throw stopFailure; }
                metrics.workerState = "STOPPED";
                owned = scheduler == null ? List.of() : List.of(scheduler);
            } else {
                stopped = true;
                metrics.workerState = "STOPPING";
                var threads = new java.util.ArrayList<>(ownedWorkers);
                if (scheduler != null) { threads.add(scheduler); }
                owned = List.copyOf(threads);
                if (leaving && !departed) { departureOutcome = "CANCELLED"; }
            }
        }
        long deadline = System.nanoTime() + STOP_TIMEOUT.toNanos();
        boolean interrupted = Thread.interrupted();
        IOException failure = null;
        var cleanupProblems = new java.util.ArrayList<CleanupProblem>();
        for (Thread thread : owned) { if (thread != Thread.currentThread()) { thread.interrupt(); } }
        try {
            try { transport.close(); }
            catch (IOException e) { failure = e; cleanupProblems.add(new CleanupProblem("transport", e)); }
            try { resolver.close(); }
            catch (IOException e) {
                cleanupProblems.add(new CleanupProblem("dnsResolver", e));
                if (failure == null) { failure = e; } else { failure.addSuppressed(e); }
            }
            for (Thread thread : owned) {
                if (thread == Thread.currentThread()) { continue; }
                while (thread.isAlive() && thread != Thread.currentThread() && deadline - System.nanoTime() > 0) {
                    try { thread.join(Duration.ofNanos(Math.max(1, deadline - System.nanoTime()))); }
                    catch (InterruptedException e) { interrupted = true; }
                }
                if (thread.isAlive()) {
                    var error = new IOException("Gossip worker did not stop within " + STOP_TIMEOUT + ": " + thread.getName());
                    cleanupProblems.add(new CleanupProblem(thread.getName(), error));
                    if (failure == null) { failure = error; } else { failure.addSuppressed(error); }
                }
            }
        } finally {
            Map<String, Object> outcome = null;
            synchronized (this) {
                metrics.workerState = failure == null ? departed ? "QUIESCED" : "STOPPED" : "CLEANUP_FAILED";
                if (leaving) { departureState = failure == null ? departureOutcome : "FAILED"; }
                departurePending.clear();
                stopFailure = failure;
                if (leaving && !departureLogged) { departureLogged = true; outcome = departure(); }
            }
            if (interrupted) { Thread.currentThread().interrupt(); }
            for (var problem : cleanupProblems) { log.cleanupFailure(problem.resource(), problem.failure()); }
            if (outcome != null) { log.departure(outcome); }
        }
        if (failure != null) { throw failure; }
    }

    private record CleanupProblem(String resource, Throwable failure) { }
}
