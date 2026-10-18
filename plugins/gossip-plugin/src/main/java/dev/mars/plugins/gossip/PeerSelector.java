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

import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;

/**
 * Chooses each round's peers: a random fanout of healthy peers, and turns in a queue for recovery probes, so that
 * peers needing one are reached without crowding out ordinary traffic. Retry backoff and DNS aliases are bounded.
 */
final class PeerSelector {
    private static final long MAX_BACKOFF = Duration.ofSeconds(30).toNanos();
    private static final long ALIAS_NANOS = Duration.ofSeconds(60).toNanos();
    /** Every third selection keeps its first slot for a recovery probe, even when healthy peers could fill it. */
    private static final int RESERVED_EVERY = 3;
    private final GossipConfig config;
    private final LongSupplier clock;
    private final RandomGenerator random;
    private final long roundNanos;
    private final Map<GossipConfig.Seed, Retry> retries = new LinkedHashMap<>();
    private final Map<GossipConfig.Seed, Alias> aliases = new LinkedHashMap<>();
    /** Each recovery peer's place in the probe queue, by canonical endpoint: the lowest turn goes next. */
    private final Map<GossipConfig.Seed, Long> recoveryTurns = new HashMap<>();
    private long lastTurn;
    private long selections;

    PeerSelector(GossipConfig config, LongSupplier clock, RandomGenerator random) {
        this.config = config;
        this.clock = clock;
        this.random = random;
        roundNanos = TimeUnit.MILLISECONDS.toNanos(config.roundMillis());
    }

    synchronized List<GossipConfig.Seed> select(MembershipTable.Snapshot snapshot, Set<GossipConfig.Seed> busy,
            int slots) {
        long now = clock.getAsLong();
        aliases.values().removeIf(alias -> now - alias.verifiedAt() >= ALIAS_NANOS);
        var candidates = candidates(snapshot);
        var busyKeys = busy.stream().map(this::canonical).collect(Collectors.toSet());
        retries.keySet().removeIf(peer -> !candidates.containsKey(peer) && !busyKeys.contains(peer));
        recoveryTurns.keySet().removeIf(peer -> !(candidates.get(peer) instanceof Candidate candidate && candidate.recovery()));
        var ordinary = new ArrayList<GossipConfig.Seed>();
        var recovery = new ArrayList<Waiting>();

        for (var item : candidates.entrySet()) {
            var key = item.getKey();
            var candidate = item.getValue();
            if (candidate.recovery()) {
                // A peer joins the back of the queue when it first needs recovery, and keeps its place while busy or
                // backing off.
                long turn = recoveryTurns.computeIfAbsent(key, _ -> ++lastTurn);
                if (available(key, busyKeys, now)) { recovery.add(new Waiting(key, candidate.peer(), turn)); }
            } else if (available(key, busyKeys, now)) {
                ordinary.add(candidate.peer());
            }
        }

        Collections.shuffle(ordinary, random);
        int count = Math.clamp(slots, 0, config.fanout());
        boolean reserved = ++selections % RESERVED_EVERY == 0;
        // A reserved slot goes to a recovery peer first; healthy peers fill the rest, and recovery peers any left over.
        int reservedSlots = reserved && count > 0 && !recovery.isEmpty() ? 1 : 0;
        int ordinaryTaken = Math.min(ordinary.size(), count - reservedSlots);
        var due = takeTurns(recovery, Math.min(recovery.size(), count - ordinaryTaken));
        var selected = new ArrayList<>(due.subList(0, reservedSlots));
        selected.addAll(ordinary.subList(0, ordinaryTaken));
        selected.addAll(due.subList(reservedSlots, due.size()));
        return List.copyOf(selected);
    }

    /**
     * Every peer that could be gossiped with, by canonical endpoint: the members that have not left and the configured
     * seeds, apart from this process. Where two share an endpoint, {@link Candidate#preferred} decides which is kept.
     */
    private Map<GossipConfig.Seed, Candidate> candidates(MembershipTable.Snapshot snapshot) {
        var candidates = new LinkedHashMap<GossipConfig.Seed, Candidate>();
        var self = snapshot.self();
        // Every candidate is checked against the local host, so it is parsed once; each candidate's host once too.
        var selfHost = Host.of(self.host());

        for (var member : snapshot.members()) {
            var known = member.record();
            if (known.key().equals(self.key()) || known.state() == MemberRecord.State.LEFT) { continue; }
            var peer = known.endpoint();
            var host = Host.of(peer.host());
            if (!isSelf(self, selfHost, peer, host)) {
                // Only a member assessed alive is healthy; an unverified, suspect or dead one needs recovery probes.
                boolean recovery = member.assessment() != MembershipTable.Assessment.ALIVE;
                candidates.merge(canonical(peer, host), new Candidate(peer, recovery), Candidate::preferred);
            }
        }
        
        for (var seed : config.seeds()) {
            var host = Host.of(seed.host());
            if (!isSelf(self, selfHost, seed, host)) {
                candidates.merge(canonical(seed, host), new Candidate(seed, true), Candidate::preferred);
            }
        }
        return candidates;
    }

    /** Not busy with an exchange already, and not backing off after a failure. */
    private boolean available(GossipConfig.Seed key, Set<GossipConfig.Seed> busyKeys, long now) {
        if (busyKeys.contains(key)) { return false; }
        Retry retry = retries.get(key);
        return retry == null || now - retry.attempted() >= retry.delay();
    }

    /**
     * The {@code count} recovery peers that have waited longest, each of which goes to the back of the queue. A peer
     * waits behind every peer that was already waiting when it arrived, so newcomers can never starve the others.
     */
    private List<GossipConfig.Seed> takeTurns(List<Waiting> recovery, int count) {
        if (count == 0) { return List.of(); }
        var due = recovery.stream().sorted(Comparator.comparingLong(Waiting::turn)).limit(count).toList();
        for (var waiting : due) { recoveryTurns.put(waiting.key(), ++lastTurn); }
        return due.stream().map(Waiting::peer).toList();
    }

    /**
     * DNS may confirm an alias of an endpoint whose identity is already directly verified. The snapshot is taken only
     * for a configured seed whose resolved address differs from it.
     */
    synchronized void resolved(GossipConfig.Seed peer, GossipConfig.Seed endpoint,
            Supplier<MembershipTable.Snapshot> snapshots) {
        var resolved = normalized(endpoint);
        if (!config.seeds().contains(peer) || normalized(peer).equals(resolved)) { return; }
        var snapshot = snapshots.get();
        boolean verified = snapshot.members().stream().anyMatch(member -> member.endpointVerified()
                && !member.record().key().equals(snapshot.self().key())
                && member.assessment() == MembershipTable.Assessment.ALIVE
                && normalized(member.record().endpoint()).equals(resolved));
        if (verified) { rememberAlias(peer, resolved); }
    }

    /** Called only after a correlated, identity-checked exchange succeeds. Never resolve DNS here. */
    synchronized void success(GossipConfig.Seed peer, GossipConfig.Seed endpoint) {
        var previous = canonical(peer);
        var resolved = normalized(endpoint);
        rememberAlias(peer, resolved);
        retries.remove(previous);
        retries.remove(resolved);
    }

    /** A reachable peer may be full. Preserve exponential backoff and verified DNS identity. */
    synchronized void capacity(GossipConfig.Seed peer, GossipConfig.Seed endpoint) {
        Retry retry = retries.remove(canonical(peer));
        rememberAlias(peer, normalized(endpoint));
        // Selection looks for the backoff under the peer's canonical endpoint, which a seed's new alias may just have
        // moved.
        if (retry != null) { retries.put(canonical(peer), retry); }
        failure(peer);
    }

    private void rememberAlias(GossipConfig.Seed peer, GossipConfig.Seed resolved) {
        if (config.seeds().contains(peer) && !normalized(peer).equals(resolved)) {
            aliases.put(normalized(peer), new Alias(resolved, clock.getAsLong()));
        }
    }

    synchronized void failure(GossipConfig.Seed peer) {
        peer = canonical(peer);
        Retry old = retries.get(peer);
        // The count stops long after the backoff does.
        int failures = old == null ? 1 : Math.min(31, old.failures() + 1);
        long delay = backoff(failures);
        if (retries.containsKey(peer) || retries.size() < (long) config.maxMembers() + config.seeds().size()) {
            retries.put(peer, new Retry(failures, clock.getAsLong(), delay));
        }
    }

    /**
     * How long to leave a peer after {@code failures} failures in a row: one round, doubled for each failure after the
     * first up to 30 s, then jittered to between 75% and 125% of that, but never past 30 s.
     */
    private long backoff(int failures) {
        long base = Math.min(roundNanos, MAX_BACKOFF);
        for (int i = 1; i < failures && base < MAX_BACKOFF; i++) { base = Math.min(MAX_BACKOFF, base * 2); }
        long low = base - base / 4;
        long spread = Math.min(MAX_BACKOFF - low, base / 2);
        return low + random.nextLong(spread + 1);
    }

    synchronized long initialDelay() { return 1 + random.nextLong(roundNanos); }
    synchronized long roundDelay() {
        long low = roundNanos - roundNanos / 5;
        long spread = Math.min(Long.MAX_VALUE - low, roundNanos / 5 * 2);
        return low + random.nextLong(spread + 1);
    }
    synchronized int retryCount() { return retries.size(); }

    /** The key a peer's retry state and selection are kept by: its normal form, or the address its alias confirmed. */
    private GossipConfig.Seed canonical(GossipConfig.Seed peer) { return canonical(peer, Host.of(peer.host())); }

    /** As above, with the peer's host already parsed. */
    private GossipConfig.Seed canonical(GossipConfig.Seed peer, Host host) {
        var normalized = normalized(peer, host);
        Alias alias = aliases.get(normalized);
        return alias != null && clock.getAsLong() - alias.verifiedAt() < ALIAS_NANOS ? alias.endpoint() : normalized;
    }

    private static GossipConfig.Seed normalized(GossipConfig.Seed peer) {
        return normalized(peer, Host.of(peer.host()));
    }

    /** The peer with its host in normal form. */
    private static GossipConfig.Seed normalized(GossipConfig.Seed peer, Host host) {
        String normal = host.normal();
        // Most hosts are already in this form; reusing the seed avoids validating an identical copy.
        return normal.equals(peer.host()) ? peer : new GossipConfig.Seed(peer.serverId(), normal, peer.port());
    }

    /** Whether the peer is this process: the same port, at the same address or hostname however it is written. */
    static boolean isSelf(MemberRecord self, GossipConfig.Seed peer) {
        // The hosts are parsed only when the ports match.
        return self.port() == peer.port() && isSelf(self, Host.of(self.host()), peer, Host.of(peer.host()));
    }

    /** As above, with both hosts already parsed. */
    private static boolean isSelf(MemberRecord self, Host selfHost, GossipConfig.Seed peer, Host peerHost) {
        return self.port() == peer.port() && selfHost.equals(peerHost);
    }

    /**
     * A host as a record or seed writes it: an IP literal, compared as an address, or a hostname, compared without
     * case. Each is parsed once and passed along, so no host is parsed twice in a round.
     */
    private sealed interface Host {
        static Host of(String text) {
            var address = GossipValidation.literal(text);
            return address != null ? new Literal(address) : new Name(text.toLowerCase(Locale.ROOT));
        }

        /** The host in normal form: an address in its standard notation, or a hostname in lower case. */
        String normal();
    }

    private record Literal(InetAddress address) implements Host {
        @Override public String normal() { return address.getHostAddress(); }
    }

    private record Name(String name) implements Host {
        @Override public String normal() { return name; }
    }

    private record Retry(int failures, long attempted, long delay) { }
    private record Alias(GossipConfig.Seed endpoint, long verifiedAt) { }
    /** A recovery peer available this round, under the canonical endpoint its turn is kept by. */
    private record Waiting(GossipConfig.Seed key, GossipConfig.Seed peer, long turn) { }

    /** A peer to gossip with, and whether it needs a recovery probe rather than a place in the healthy fanout. */
    private record Candidate(GossipConfig.Seed peer, boolean recovery) {
        /**
         * Which of two candidates at one endpoint to keep: the known one if it is healthy, so that a healthy member
         * stays
         * in the fanout; otherwise the later one, so that a recovering seed keeps its hostname and with it the DNS
         * fallback.
         */
        static Candidate preferred(Candidate known, Candidate later) { return known.recovery() ? later : known; }
    }
}
