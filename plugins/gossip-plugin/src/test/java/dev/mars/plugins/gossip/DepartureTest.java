package dev.mars.plugins.gossip;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DepartureTest {
    private static final MemberRecord SELF = PeerSelectorTest.record("Self", 1, 9000);
    private static final GossipConfig.Seed ME = new GossipConfig.Seed("Self", "127.0.0.1", 9000);
    private static final GossipConfig.Seed A = new GossipConfig.Seed("A", "127.0.0.1", 9010);
    private static final GossipConfig.Seed B = new GossipConfig.Seed("B", "127.0.0.1", 9011);
    private static final GossipConfig.Seed C = new GossipConfig.Seed("C", "127.0.0.1", 9012);

    @Test void beginsWithAtMostTheCapOfTargetsAndNeverThisProcess() {
        var departure = new Departure(Duration.ofSeconds(5));
        assertFalse(departure.requested());
        assertEquals(Map.of("state", "NOT_REQUESTED", "budgetMillis", 5000L, "targets", 0, "attempts", 0, "successes", 0,
                "failures", 0, "skipped", 0, "cancelled", 0), departure.result());
        departure.begin(List.of(ME, A, A, B, C), SELF, 2, 100);
        assertTrue(departure.requested());
        assertEquals("DISSEMINATING", departure.result().get("state"));
        assertEquals(2, departure.result().get("targets"));
        assertEquals(List.of(A, B), departure.next(Set.of(), 5));
    }

    @Test void triesEachTargetOnceAndCountsHowEachAttemptEnded() {
        var departure = started(A, B, C);
        assertEquals(List.of(B, C), departure.next(Set.of(A), 2), "A target an exchange is busy with is passed over");
        departure.attempting(A);
        departure.attempting(B);
        assertEquals(List.of(C), departure.next(Set.of(), 5));
        departure.record(Departure.Outcome.SUCCESS);
        departure.record(Departure.Outcome.FAILURE);
        departure.record(Departure.Outcome.SKIPPED);
        departure.record(Departure.Outcome.CANCELLED);
        assertFalse(departure.allTried());
        departure.attempting(C);
        assertTrue(departure.allTried());
        var result = departure.result();
        assertEquals(List.of(3, 3, 1, 1, 1, 1), List.of(result.get("targets"), result.get("attempts"), result.get("successes"),
                result.get("failures"), result.get("skipped"), result.get("cancelled")));
    }

    @Test void howItEndedIsReportedOnlyOncePublished() {
        var departure = started(A);
        long budget = Duration.ofSeconds(5).toNanos();
        assertFalse(departure.overBudget(100 + budget - 1));
        assertTrue(departure.overBudget(100 + budget));
        assertFalse(departure.ended());
        departure.exhausted();
        assertTrue(departure.ended());
        assertEquals("DISSEMINATING", departure.result().get("state"), "Not published until the runtime stops");
        departure.failIfUndecided();
        departure.publish(false);
        assertEquals("EXHAUSTED", departure.result().get("state"), "Failing an undecided departure leaves a decided one alone");
    }

    @Test void stoppingCancelsItAndAFailedCleanupReportsFailure() {
        var cancelled = started(A);
        cancelled.completed();
        cancelled.cancelled();
        cancelled.publish(false);
        assertEquals("CANCELLED", cancelled.result().get("state"), "Stopping replaces how it was going to end");
        var undecided = started(A);
        undecided.failIfUndecided();
        undecided.publish(false);
        assertEquals("FAILED", undecided.result().get("state"));
        var cleanup = started(A);
        cleanup.completed();
        cleanup.publish(true);
        assertEquals("FAILED", cleanup.result().get("state"));
        assertTrue(cleanup.allTried(), "Publishing drops the targets not yet tried");
        assertTrue(cleanup.firstReport());
        assertFalse(cleanup.firstReport(), "The outcome is reported once");
    }

    @Test void rejectsABudgetThatIsNotPositive() {
        assertThrows(IllegalArgumentException.class, () -> new Departure(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new Departure(Duration.ofSeconds(-1)));
    }

    private static Departure started(GossipConfig.Seed... targets) {
        var departure = new Departure(Duration.ofSeconds(5));
        departure.begin(List.of(targets), SELF, 10, 100);
        return departure;
    }
}
