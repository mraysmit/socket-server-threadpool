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

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * A voluntary departure: which peers still need telling, how each attempt went, and how the departure ended. How it
 * ended is decided while it runs, but reported only once the runtime has stopped. Not thread-safe: the runtime calls
 * it under its own lock.
 */
final class Departure {
    /** How one exchange ended, as the departure counts it. */
    enum Outcome { SUCCESS, FAILURE, SKIPPED, CANCELLED }

    /** Its progress, as the departure result reports it. The last four are how it ended. */
    private enum State { NOT_REQUESTED, DISSEMINATING, COMPLETED, EXHAUSTED, CANCELLED, FAILED }

    private final long budgetNanos;
    private final Set<GossipConfig.Seed> pending = new LinkedHashSet<>(); // Targets not yet tried, in order.
    private State state = State.NOT_REQUESTED; // What the result reports.
    private State ending; // How it ended, once decided; reported only when published.
    private long started;
    private int targets;
    private int attempts;
    private int successes;
    private int failures;
    private int skipped;
    private int cancelled;
    private boolean reported;

    Departure(Duration budget) {
        if (budget.isNegative() || budget.isZero()) {
            throw new IllegalArgumentException("Positive departure timeout required");
        }
        budgetNanos = budget.toNanos();
    }

    boolean requested() { return state != State.NOT_REQUESTED; }

    /** Starts the departure, aiming at the first {@code cap} distinct candidates other than this process. */
    void begin(List<GossipConfig.Seed> candidates, MemberRecord self, int cap, long now) {
        for (var peer : candidates) {
            if (pending.size() < cap && !PeerSelector.isSelf(self, peer)) { pending.add(peer); }
        }
        started = now;
        state = State.DISSEMINATING;
        targets = pending.size();
    }

    /** Up to {@code limit} targets not yet tried, in order, passing over any that an exchange is busy with. */
    List<GossipConfig.Seed> next(Set<GossipConfig.Seed> busy, int limit) {
        return pending.stream().filter(peer -> !busy.contains(peer)).limit(limit).toList();
    }

    /** An exchange is trying this target, so it is not tried again. */
    void attempting(GossipConfig.Seed peer) {
        pending.remove(peer);
        attempts++;
    }

    void record(Outcome outcome) {
        switch (outcome) {
            case SUCCESS -> successes++;
            case FAILURE -> failures++;
            case SKIPPED -> skipped++;
            case CANCELLED -> cancelled++;
        }
    }

    boolean allTried() { return pending.isEmpty(); }

    boolean overBudget(long now) { return now - started >= budgetNanos; }

    boolean ended() { return ending != null; }

    void completed() { ending = State.COMPLETED; }

    void exhausted() { ending = State.EXHAUSTED; }

    /** The runtime was stopped. This replaces how the departure was going to end. */
    void cancelled() { ending = State.CANCELLED; }

    /** The scheduler stopped without deciding how the departure ended. */
    void failIfUndecided() { if (ending == null) { ending = State.FAILED; } }

    /**
     * The runtime has stopped: report how the departure ended, or that it failed if cleanup did, and drop what is left.
     */
    void publish(boolean cleanupFailed) {
        state = cleanupFailed ? State.FAILED : ending;
        pending.clear();
    }

    /** True only the first time, so that the outcome is logged once. */
    boolean firstReport() {
        if (reported) { return false; }
        reported = true;
        return true;
    }

    Map<String, Object> result() {
        return Map.of("state", state.name(), "budgetMillis", TimeUnit.NANOSECONDS.toMillis(budgetNanos),
                "targets", targets, "attempts", attempts, "successes", successes, "failures", failures,
                "skipped", skipped, "cancelled", cancelled);
    }
}
