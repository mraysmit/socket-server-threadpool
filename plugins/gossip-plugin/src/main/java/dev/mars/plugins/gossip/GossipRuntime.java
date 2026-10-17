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

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        /** Addresses of one host, tried in order on connection failure. Clients without fallback use the first. */
        default GossipWire.Exchange exchange(List<GossipConfig.Seed> endpoints) throws IOException {
            return exchange(endpoints.getFirst());
        }
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
    private final String serverId;
    private final Map<GossipConfig.Seed, Thread> workers = new HashMap<>();
    private final Set<Thread> ownedWorkers = new HashSet<>();
    private final Set<String> resolvedInFlight = new HashSet<>();
    private final Set<InetAddress> localAddresses = new HashSet<>();
    private final Departure departure;
    private Thread scheduler;
    private boolean stopped;
    private boolean started;
    private long lastHeartbeat;
    private IOException stopFailure;
    private final ReentrantLock closeLock = new ReentrantLock();
    private volatile State state = State.INITIALIZING;

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
        log = metrics.log(config, table.self());
        serverId = table.localKey().serverId();
        this.clock = clock;
        this.resolver = resolver;
        departure = new Departure(departureTimeout);
        selector = new PeerSelector(config, clock, random);
        lastHeartbeat = clock.getAsLong();
        try { NetworkInterface.networkInterfaces().flatMap(NetworkInterface::inetAddresses).forEach(localAddresses::add); }
        catch (java.net.SocketException _) { /* Exact configured self endpoints still remain excluded. */ }
    }

    synchronized void start() {
        if (started || stopped) { throw new IllegalStateException("Gossip runtime cannot restart"); }
        started = true;
        state = State.RUNNING;
        scheduler = Thread.ofVirtual().name("gossip-" + serverId + "-rounds").unstarted(this::run);
        scheduler.start();
    }

    /** Rounds will be run by hand, through {@link #round()}, instead of by the owned scheduler. */
    synchronized void manual() { state = State.MANUAL; }

    State state() { return state; }

    /** The scheduler: a round after each delay, until the runtime stops or a departure ends. */
    private void run() {
        try {
            for (boolean first = true; awaitRound(first); first = false) {
                try { round(); }
                catch (RuntimeException e) { metrics.roundFailures.incrementAndGet(); log.roundFailure(e); }
            }
        } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
        finally {
            finishDeparture();
            synchronized (this) { if (!stopped) { state = State.FAILED; } }
        }
    }

    /** Waits until the next round is due; false once the runtime is stopping or the departure has ended. */
    private synchronized boolean awaitRound(boolean first) throws InterruptedException {
        if (stopped || departure.ended()) { return false; }
        TimeUnit.NANOSECONDS.timedWait(this, nextDelay(first));
        return !stopped;
    }

    /** Briefly while leaving, so the departure finishes promptly; otherwise the selector's jittered round delay. */
    private long nextDelay(boolean first) {
        if (departure.requested()) { return TimeUnit.MILLISECONDS.toNanos(25); }
        return first ? selector.initialDelay() : selector.roundDelay();
    }

    /** A departing scheduler stops the runtime itself, but never waits for a concurrent stop that is joining it. */
    private void finishDeparture() {
        if (!closeLock.tryLock()) { return; }
        try {
            boolean finish;
            synchronized (this) {
                finish = departure.requested() && !stopped;
                if (finish) { departure.failIfUndecided(); }
            }
            if (finish) {
                try { closeOwned(StopReason.DEPARTED); }
                catch (IOException _) { /* Retained in stopFailure and diagnostic state. */ }
            }
        } finally { closeLock.unlock(); }
    }

    /** Publish locally and wake the owned scheduler; no DNS, sockets, or joins on the caller. */
    synchronized Map<String, Object> leave() {
        if (departure.requested()) { return departure.result(); }
        if (stopped) { throw GossipWire.invalid("Cannot leave a stopped gossip runtime"); }
        // Every live member and every seed is a candidate, in that order; the departure keeps the first it may tell.
        var snapshot = table.snapshot();
        var candidates = new ArrayList<GossipConfig.Seed>();
        for (var member : snapshot.members()) {
            var owner = member.record();
            if (!owner.key().equals(snapshot.self().key()) && owner.state() != MemberRecord.State.LEFT) {
                candidates.add(owner.endpoint());
            }
        }
        candidates.addAll(config.seeds());
        table.leaveLocal();
        departure.begin(candidates, snapshot.self(), Math.max(0, config.maxMembers() - 1), clock.getAsLong());
        state = State.LEAVING;
        for (var worker : ownedWorkers) { worker.interrupt(); }
        if (!started) { start(); state = State.LEAVING; }
        notifyAll();
        return departure.result();
    }

    synchronized Map<String, Object> departure() { return departure.result(); }

    synchronized void requireActiveExchange() throws IOException {
        if (departure.requested() || stopped) { throw new IOException("Gossip runtime is leaving or stopped"); }
    }

    /** Package seam for deterministic-clock algorithm tests; production calls only from the owned scheduler. */
    synchronized void round() {
        if (stopped) { return; }
        long now = clock.getAsLong();
        recordRound();
        publishHeartbeat(now);
        table.maintain();
        ownedWorkers.removeIf(thread -> !thread.isAlive());
        if (departure.requested() && departureEnded()) { return; }
        int slots = config.maxInflight() - Math.max(ownedWorkers.size(), transport.inflight());
        if (slots <= 0) { metrics.skipped.incrementAndGet(); return; }
        startWorkers(choosePeers(slots));
    }

    private void recordRound() {
        metrics.rounds.incrementAndGet();
        metrics.lastRound = System.nanoTime();
        metrics.dnsHelpers = resolver.activeHelpers();
    }

    /** The owner's heartbeat advances on its own interval, not every round, and stops once it is leaving. */
    private void publishHeartbeat(long now) {
        if (!departure.requested() && now - lastHeartbeat >= config.heartbeatMillis() * 1_000_000) {
            table.advanceLocal();
            lastHeartbeat = now;
        }
    }

    /** Ends the departure once every target has been tried and every exchange has finished, or its budget has run out. */
    private boolean departureEnded() {
        if (departure.allTried() && ownedWorkers.isEmpty()) { departure.completed(); return true; }
        if (departure.overBudget(clock.getAsLong())) { departure.exhausted(); return true; }
        return false;
    }

    /** While leaving, the departure's next targets; otherwise the selector's choice. Peers already being dialled are excluded. */
    private List<GossipConfig.Seed> choosePeers(int slots) {
        if (departure.requested()) { return departure.next(workers.keySet(), Math.min(config.fanout(), slots)); }
        return selector.select(table.snapshot(), Set.copyOf(workers.keySet()), slots);
    }

    private void startWorkers(List<GossipConfig.Seed> peers) {
        boolean departing = departure.requested();
        var roundDestinations = new HashSet<String>(); // Endpoints this round's exchanges have claimed.
        for (var peer : peers) {
            var worker = Thread.ofVirtual().name("gossip-" + serverId + "-exchange")
                    .unstarted(() -> exchange(peer, departing, roundDestinations));
            workers.put(peer, worker);
            ownedWorkers.add(worker);
            metrics.workers = workers.size();
            if (departing) { departure.attempting(peer); }
            try { worker.start(); }
            catch (RuntimeException | Error e) {
                workers.remove(peer);
                ownedWorkers.remove(worker);
                metrics.workers = workers.size();
                if (departing) { departure.record(Departure.Outcome.FAILURE); }
                throw e;
            }
        }
    }

    /** One exchange, on its own worker: resolve the peer, claim its endpoint, exchange, and record how it ended. */
    private void exchange(GossipConfig.Seed peer, boolean departing, Set<String> roundDestinations) {
        Claim claim = null;
        var outcome = Departure.Outcome.CANCELLED;
        try {
            List<GossipConfig.Seed> endpoints;
            try {
                metrics.dnsHelpers = resolver.activeHelpers();
                endpoints = resolver.resolveAll(peer);
            } catch (IOException | RuntimeException e) {
                outcome = failed(peer, departing, e, GossipFailure.DNS, outcome);
                return;
            }
            try {
                synchronized (this) {
                    if (stopped || (!departing && departure.requested())) { return; }
                    claim = claim(peer, endpoints, roundDestinations);
                }
                if (claim == null) { metrics.skipped.incrementAndGet(); outcome = Departure.Outcome.SKIPPED; return; }
                var reply = transport.exchange(claim.attempts());
                outcome = Departure.Outcome.SUCCESS;
                answered(peer, claim.endpoint(), reply, departing);
            } catch (IOException | RuntimeException e) {
                outcome = failed(peer, departing, e, GossipFailure.of(e), outcome);
            }
        } finally { finish(peer, departing, outcome, claim); }
    }

    /**
     * Claims the resolved endpoint for this exchange, or returns null to skip it: when the endpoint is this process,
     * or when this round or another exchange already has it. Called under the runtime's lock.
     */
    private Claim claim(GossipConfig.Seed peer, List<GossipConfig.Seed> endpoints, Set<String> roundDestinations) {
        var endpoint = endpoints.getFirst();
        // A full snapshot is needed only to confirm a seed alias, so take it only then.
        selector.resolved(peer, endpoint, table::snapshot);
        int selfPort = table.self().port();
        if (isLocal(endpoint, selfPort)) { return null; }
        String key = endpoint.host() + ":" + endpoint.port();
        if (!roundDestinations.add(key) || !resolvedInFlight.add(key)) { return null; }
        // The host's other addresses are connection fallbacks; never reach this process through one.
        var attempts = endpoints.stream().filter(candidate -> candidate == endpoint || !isLocal(candidate, selfPort)).toList();
        return new Claim(endpoint, key, attempts);
    }

    /** A correlated reply: a peer that had no room for this process is backed off; any other counts as a success. */
    private void answered(GossipConfig.Seed peer, GossipConfig.Seed endpoint, GossipWire.Exchange reply, boolean departing) {
        if (!departing && reply.senderAdmission() == MembershipTable.Admission.CAPACITY) {
            selector.capacity(peer, endpoint);
            metrics.failure(GossipFailure.CAPACITY);
        } else { selector.success(peer, endpoint); }
    }

    /**
     * Records a failed exchange and returns how it ended. A busy transport is a skip, not a failure. A failure after
     * stop, or an ordinary exchange's failure after a departure began, no longer matters and leaves the outcome as it was.
     */
    private synchronized Departure.Outcome failed(GossipConfig.Seed peer, boolean departing, Exception e, GossipFailure kind,
                                                  Departure.Outcome outcome) {
        if (stopped || (!departing && departure.requested())) { return outcome; }
        if (e instanceof GossipTransport.BusyException) { metrics.skipped.incrementAndGet(); return Departure.Outcome.SKIPPED; }
        selector.failure(peer);
        metrics.failure(kind);
        return Departure.Outcome.FAILURE;
    }

    /** Counts the outcome for a departure, releases the claimed endpoint, and retires the worker. */
    private synchronized void finish(GossipConfig.Seed peer, boolean departing, Departure.Outcome outcome, Claim claim) {
        if (departing) { departure.record(outcome); }
        if (claim != null) { resolvedInFlight.remove(claim.key()); }
        workers.remove(peer);
        metrics.workers = workers.size();
        metrics.dnsHelpers = resolver.activeHelpers();
        metrics.retryingPeers = selector.retryCount();
    }

    private boolean isLocal(GossipConfig.Seed endpoint, int selfPort) {
        InetAddress address = InetAddress.ofLiteral(endpoint.host());
        return endpoint.port() == selfPort && (address.isLoopbackAddress() || localAddresses.contains(address));
    }

    @Override public void close() throws IOException {
        closeLock.lock();
        try { closeOwned(StopReason.CLOSED); }
        finally { closeLock.unlock(); }
    }

    /** Stops new work, interrupts and joins every owned thread, and records how the stop went. */
    private void closeOwned(StopReason reason) throws IOException {
        List<Thread> owned = beginStop(reason);
        long deadline = System.nanoTime() + STOP_TIMEOUT.toNanos();
        boolean interrupted = Thread.interrupted();
        var problems = new ArrayList<CleanupProblem>();
        for (Thread thread : owned) { if (thread != Thread.currentThread()) { thread.interrupt(); } }
        IOException failure = null;
        try {
            try { transport.close(); } catch (IOException e) { problems.add(new CleanupProblem("transport", e)); }
            resolver.close();
            interrupted |= joinAll(owned, deadline, problems);
        } finally {
            // The first problem is thrown, carrying the others as suppressed exceptions, in the order they occurred.
            for (var problem : problems) {
                if (failure == null) { failure = problem.failure(); } else { failure.addSuppressed(problem.failure()); }
            }
            var report = finishStop(reason, failure);
            if (interrupted) { Thread.currentThread().interrupt(); }
            for (var problem : problems) { log.cleanupFailure(problem.resource(), problem.failure()); }
            report.ifPresent(log::departure);
        }
        if (failure != null) { throw failure; }
    }

    /**
     * Marks the runtime stopped and returns the threads to join: every owned thread the first time, and only the
     * scheduler on a repeated stop, which also rethrows the first stop's failure. Stopping cancels a departure.
     */
    private synchronized List<Thread> beginStop(StopReason reason) throws IOException {
        if (stopped) {
            if (stopFailure != null) { throw stopFailure; }
            state = State.STOPPED;
            return scheduler == null ? List.of() : List.of(scheduler);
        }
        stopped = true;
        state = State.STOPPING;
        var threads = new ArrayList<>(ownedWorkers);
        if (scheduler != null) { threads.add(scheduler); }
        if (departure.requested() && reason == StopReason.CLOSED) { departure.cancelled(); }
        return List.copyOf(threads);
    }

    /** Joins each thread until the shared deadline, recording any still alive; returns whether this thread was interrupted. */
    private static boolean joinAll(List<Thread> threads, long deadline, List<CleanupProblem> problems) {
        boolean interrupted = false;
        for (Thread thread : threads) {
            if (thread == Thread.currentThread()) { continue; }
            while (thread.isAlive() && deadline - System.nanoTime() > 0) {
                try { thread.join(Duration.ofNanos(Math.max(1, deadline - System.nanoTime()))); }
                catch (InterruptedException _) { interrupted = true; }
            }
            if (thread.isAlive()) {
                problems.add(new CleanupProblem(thread.getName(),
                        new IOException("Gossip worker did not stop within " + STOP_TIMEOUT + ": " + thread.getName())));
            }
        }
        return interrupted;
    }

    /** Records the final state, publishes a departure's outcome, and returns that outcome the first time, to log it. */
    private synchronized Optional<Map<String, Object>> finishStop(StopReason reason, IOException failure) {
        state = failure != null ? State.CLEANUP_FAILED : reason.finalState;
        stopFailure = failure;
        if (!departure.requested()) { return Optional.empty(); }
        departure.publish(failure != null);
        return departure.firstReport() ? Optional.of(departure.result()) : Optional.empty();
    }

    private record Claim(GossipConfig.Seed endpoint, String key, List<GossipConfig.Seed> attempts) { }

    private record CleanupProblem(String resource, IOException failure) { }

    /** The runtime's lifecycle, as status reports it. */
    enum State { INITIALIZING, MANUAL, RUNNING, LEAVING, STOPPING, STOPPED, QUIESCED, CLEANUP_FAILED, FAILED }

    /** Why the runtime is stopping, and the state it reports once it has stopped cleanly. */
    private enum StopReason {
        CLOSED(State.STOPPED), DEPARTED(State.QUIESCED);

        final State finalState;

        StopReason(State finalState) { this.finalState = finalState; }
    }
}
