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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

import static dev.mars.plugins.gossip.MembershipException.Reason.*;
import static dev.mars.plugins.gossip.MembershipTable.Assessment.*;
import static org.junit.jupiter.api.Assertions.*;

class MembershipTableTest {
    private final Clock clock = new Clock();
    private final MemberRecord self = member("Self", 1, 0);
    private final MemberRecord peer = member("Peer", 2, 1);

    private static GossipConfig config(int members, int records) {
        var properties = GossipConfigTest.minimal();
        properties.setProperty("round.millis", "1");
        properties.setProperty("heartbeat.millis", "5");
        properties.setProperty("suspect.after.millis", "10");
        properties.setProperty("dead.after.millis", "30");
        properties.setProperty("terminal.retention.millis", "60");
        properties.setProperty("max.members", Integer.toString(members));
        properties.setProperty("max.records", Integer.toString(records));
        properties.setProperty("max.batch.members", Integer.toString(records));
        return GossipConfig.fromProperties(properties, "Self");
    }

    private MembershipTable table() { return new MembershipTable(config(4, 8), self, clock); }

    private static MemberRecord member(String id, long boot, long heartbeat) {
        return new MemberRecord(new MemberKey(id, new UUID(0, boot)), "localhost", 9000, heartbeat,
                MemberRecord.State.ALIVE, Map.of());
    }

    private static MemberRecord version(MemberRecord member, long heartbeat) {
        return new MemberRecord(member.key(), member.host(), member.port(), heartbeat, member.state(), member.metadata());
    }

    private static MemberRecord departed(MemberRecord member) {
        return new MemberRecord(member.key(), member.host(), member.port(), member.heartbeat(), MemberRecord.State.LEFT, member.metadata());
    }

    private static MembershipTable.MemberView view(MembershipTable table, MemberKey key) {
        return table.snapshot().members().stream().filter(member -> member.record().key().equals(key)).findFirst().orElseThrow();
    }

    @Test void duplicateAndOlderHeartbeatsCannotKeepForwardedRecordsFresh() {
        var table = table();
        table.merge(List.of(peer));
        assertEquals(CANDIDATE, view(table, peer.key()).assessment());
        clock.advance(9);
        table.merge(List.of(peer));
        assertEquals(TimeUnit.MILLISECONDS.toNanos(9), view(table, peer.key()).advanceAgeNanos());
        clock.advance(1);
        table.merge(List.of(version(peer, 0)));
        assertEquals(SUSPECT, view(table, peer.key()).assessment());
        clock.advance(20);
        assertEquals(DEAD, view(table, peer.key()).assessment());
        table.merge(List.of(version(peer, 2)));
        assertEquals(CANDIDATE, view(table, peer.key()).assessment());
        assertEquals(0, view(table, peer.key()).advanceAgeNanos());
        assertEquals(2, view(table, peer.key()).record().heartbeat());
    }

    @Test void directSuccessRefreshesOnlyTheResponderAndCanRecoverLocalDeath() {
        var table = table();
        var forwarded = member("Forwarded", 3, 1);
        table.mergeDirect(peer, List.of(forwarded));
        assertEquals(MembershipTable.Assessment.ALIVE, view(table, peer.key()).assessment());
        assertEquals(CANDIDATE, view(table, forwarded.key()).assessment());
        assertNull(view(table, forwarded.key()).directSuccessAgeNanos());
        clock.advance(30);
        assertEquals(DEAD, view(table, peer.key()).assessment());
        table.mergeDirect(peer, List.of(forwarded));
        assertEquals(MembershipTable.Assessment.ALIVE, view(table, peer.key()).assessment());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(30), view(table, peer.key()).advanceAgeNanos());
        assertEquals(0L, view(table, peer.key()).directSuccessAgeNanos());
        assertEquals(DEAD, view(table, forwarded.key()).assessment());
    }

    @Test void forwardedEndpointChangesNeedFreshDirectVerification() {
        var table = table();
        table.mergeDirect(peer, List.of());
        var moved = new MemberRecord(peer.key(), "127.0.0.2", 9010, 2, MemberRecord.State.ALIVE, Map.of());
        clock.advance(1);
        table.merge(List.of(moved));
        assertEquals(CANDIDATE, view(table, peer.key()).assessment());
        assertFalse(view(table, peer.key()).endpointVerified());
        table.mergeDirect(peer, List.of());
        assertFalse(view(table, peer.key()).endpointVerified(), "An old endpoint must not verify the moved endpoint");
        table.mergeDirect(moved, List.of());
        assertTrue(view(table, peer.key()).endpointVerified());
        assertEquals(MembershipTable.Assessment.ALIVE, view(table, peer.key()).assessment());
    }

    @Test void malformedAndConflictingBatchesLeaveRecordsAndDirectObservationsUnchanged() {
        var table = table();
        var other = member("Other", 3, 1);
        table.mergeDirect(peer, List.of(other));
        clock.advance(1);
        var before = table.snapshot();
        var malformed = new LinkedHashMap<>(other.toWire());
        malformed.put("heartbeat", 1.5);
        assertReason(INVALID_RECORD, () -> table.mergeEncoded(List.of(version(peer, 2).toWire(), malformed)));
        assertEquals(before, table.snapshot());
        var conflict = new MemberRecord(other.key(), "elsewhere.invalid", 9000, 1, MemberRecord.State.ALIVE, Map.of());
        assertReason(CONFLICT, () -> table.mergeDirect(peer, List.of(conflict)));
        assertEquals(before, table.snapshot(), "The responder's direct-success time must roll back too");
        assertReason(CONFLICT, () -> table.merge(List.of(member("New", 4, 0), conflict)));
        assertEquals(before, table.snapshot());
    }

    @Test void forwardedCopiesCannotAdvanceOrReplaceTheLocalOwner() {
        var table = table();
        var owned = table.advanceLocal();
        var before = table.snapshot();
        assertReason(CONFLICT, () -> table.merge(List.of(peer, version(owned, 2))));
        assertEquals(before, table.snapshot());
        var forged = new MemberRecord(owned.key(), "other.invalid", 9010, owned.heartbeat(), MemberRecord.State.ALIVE, Map.of());
        assertReason(CONFLICT, () -> table.merge(List.of(forged)));
        assertReason(CONFLICT, () -> table.mergeDirect(owned, List.of()));
        table.merge(List.of(self)); // Older copy is harmless and cannot refresh the owner.
        assertEquals(owned, table.snapshot().self());
    }

    @Test void terminalDepartureDominatesAliveAndRepeatedCopiesDoNotExtendSuppression() {
        var table = table();
        table.merge(List.of(version(peer, 9)));
        var left = departed(version(peer, 2));
        table.merge(List.of(left));
        assertEquals(MembershipTable.Assessment.LEFT, view(table, peer.key()).assessment());
        table.mergeDirect(version(peer, Long.MAX_VALUE), List.of());
        assertEquals(left, view(table, peer.key()).record());
        clock.advance(59);
        table.merge(List.of(left));
        table.maintain();
        assertEquals(0, table.snapshot().tombstones());
        clock.advance(1);
        table.maintain();
        assertEquals(1, table.snapshot().tombstones());
        assertEquals(1, table.snapshot().members().size());
        table.mergeDirect(version(peer, Long.MAX_VALUE), List.of());
        assertEquals(1, table.snapshot().members().size());
        clock.advance(59);
        table.merge(List.of(left));
        clock.advance(1);
        table.maintain();
        assertEquals(0, table.snapshot().tombstones());
        table.merge(List.of(peer)); // Finite retention permits stale resurrection after suppression expires.
        assertEquals(CANDIDATE, view(table, peer.key()).assessment());
    }

    static Stream<Arguments> staleLocalAliveCopies() {
        return Stream.of(false, true).flatMap(direct -> Stream.of(0L, 1L, 2L)
                .map(heartbeat -> Arguments.of(direct, heartbeat)));
    }

    @ParameterizedTest
    @MethodSource("staleLocalAliveCopies")
    void departedOwnerIgnoresStaleAliveCopiesAndStillCommitsPeerUpdates(boolean direct, long heartbeat) {
        var table = table();
        table.advanceLocal();
        table.advanceLocal();
        var left = table.leaveLocal();
        table.merge(List.of(peer));
        clock.advance(5);
        var localBefore = view(table, self.key());
        var update = version(peer, 2);
        var stale = version(self, heartbeat);

        var result = direct ? table.mergeDirect(update, List.of(stale)) : table.merge(List.of(update, stale));

        assertEquals(new MembershipTable.MergeResult(0, 1, 1), result);
        assertEquals(left, table.snapshot().self());
        assertEquals(localBefore, view(table, self.key()), "Replay must not refresh local departure timing");
        assertEquals(update, view(table, peer.key()).record());
        assertEquals(0, view(table, peer.key()).advanceAgeNanos());
        assertEquals(direct, view(table, peer.key()).endpointVerified());
    }

    @ParameterizedTest
    @CsvSource({"ALIVE, 3", "LEFT, 2", "LEFT, 3"})
    void departedOwnerStillRejectsForgedRecordsAndRollsBackTheWholeExchange(MemberRecord.State state, long heartbeat) {
        var table = table();
        table.advanceLocal();
        table.advanceLocal();
        table.leaveLocal();
        table.mergeDirect(peer, List.of());
        clock.advance(5);
        var before = table.snapshot();
        var forged = new MemberRecord(self.key(), "forged.invalid", 9010, heartbeat, state, Map.of("forged", true));

        assertReason(CONFLICT, () -> table.mergeDirect(version(peer, 2), List.of(forged)));

        assertEquals(before, table.snapshot(), "Neither the peer update nor its direct-success time may commit");
    }

    @Test void inactiveSuppressionBlocksStaleForwardingButAllowsDirectOrNewerRecovery() {
        var table = table();
        table.merge(List.of(peer));
        clock.advance(60);
        table.maintain();
        assertEquals(1, table.snapshot().tombstones());
        table.merge(List.of(peer, version(self, 0)));
        assertEquals(1, table.snapshot().members().size());
        table.mergeDirect(peer, List.of());
        assertEquals(MembershipTable.Assessment.ALIVE, view(table, peer.key()).assessment());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(60), view(table, peer.key()).advanceAgeNanos());
        clock.advance(60);
        table.maintain();
        table.merge(List.of(version(peer, 2)));
        assertEquals(CANDIDATE, view(table, peer.key()).assessment());
        assertEquals(0, view(table, peer.key()).advanceAgeNanos());
    }

    @Test void compactSuppressionStillDetectsEqualVersionConflictsWithoutPartialAdmission() {
        var table = table();
        table.merge(List.of(peer));
        clock.advance(60);
        table.maintain();
        var before = table.snapshot();
        var conflicting = new MemberRecord(peer.key(), peer.host(), peer.port(), 1, MemberRecord.State.ALIVE, Map.of("new", true));
        assertReason(CONFLICT, () -> table.merge(List.of(member("New", 3, 0), conflicting)));
        assertEquals(before, table.snapshot());
        table.merge(List.of(departed(peer)));
        clock.advance(60);
        table.maintain();
        var terminalConflict = new MemberRecord(peer.key(), peer.host(), peer.port(), 1, MemberRecord.State.LEFT, Map.of("new", true));
        assertReason(CONFLICT, () -> table.merge(List.of(terminalConflict)));
        assertEquals(1, table.snapshot().tombstones());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void memberCapacityUsesTheFinalBatchStateAndExistingUpdatesStillWork(boolean departureFirst) {
        var table = new MembershipTable(config(2, 4), self, clock);
        table.merge(List.of(peer));
        var other = member("Other", 3, 1);
        var before = table.snapshot();
        assertEquals(new MembershipTable.MergeResult(0, 0, 1), table.merge(List.of(other)));
        assertEquals(before.members(), table.snapshot().members());
        assertEquals(1, table.snapshot().admissionsSkipped());
        table.merge(List.of(version(peer, 2)));
        assertEquals(2, view(table, peer.key()).record().heartbeat());
        var left = departed(version(peer, 2));
        table.merge(departureFirst ? List.of(left, other) : List.of(other, left));
        assertEquals(2, table.snapshot().activeMembers());
        assertEquals(3, table.snapshot().retainedIdentities());
        assertEquals(MembershipTable.Assessment.LEFT, view(table, peer.key()).assessment());
    }

    @Test void retainedIdentityCapacityCountsCompactTombstonesAndExpiresOnlyOnMaintenance() {
        var table = new MembershipTable(config(2, 2), self, clock);
        table.merge(List.of(departed(peer)));
        clock.advance(60);
        table.maintain();
        assertEquals(1, table.snapshot().activeMembers());
        assertEquals(2, table.snapshot().retainedIdentities());
        assertEquals(1, table.snapshot().tombstones());
        var other = member("Other", 3, 0);
        assertEquals(new MembershipTable.MergeResult(0, 0, 1), table.merge(List.of(other)));
        table.advanceLocal();
        clock.advance(60);
        assertEquals(new MembershipTable.MergeResult(0, 0, 1), table.merge(List.of(other)));
        table.maintain();
        table.merge(List.of(other));
        assertEquals(2, table.snapshot().activeMembers());
    }

    @Test void aNewTerminalRecordDoesNotConsumeAnActiveMemberSlot() {
        var table = new MembershipTable(config(2, 4), self, clock);
        table.merge(List.of(peer));
        table.merge(List.of(departed(member("Gone", 3, 0))));
        assertEquals(2, table.snapshot().activeMembers());
        assertEquals(3, table.snapshot().retainedIdentities());
    }

    @Test void selfAndLocalKeyFollowTheLocalRecordWithoutASnapshot() {
        var table = table();
        assertEquals(self.key(), table.localKey());
        assertEquals(table.snapshot().self(), table.self());
        var advanced = table.advanceLocal();
        assertEquals(advanced, table.self());
        assertEquals(self.heartbeat() + 1, table.self().heartbeat());
        var departed = table.leaveLocal();
        assertEquals(departed, table.self());
        assertEquals(MemberRecord.State.LEFT, table.self().state());
        assertEquals(self.key(), table.localKey());
    }

    @Test void restartsRemainDistinctAndReachableDuplicateLogicalIdsAreReportedWithoutAWinner() {
        var table = table();
        var restart = member("Peer", 3, 0);
        table.mergeDirect(peer, List.of());
        table.mergeDirect(restart, List.of());
        assertEquals(java.util.Set.of("Peer"), table.snapshot().identityConflicts());
        assertEquals(table.snapshot().identityConflicts(), table.identityConflicts());
        assertTrue(view(table, peer.key()).identityConflict());
        assertTrue(view(table, restart.key()).identityConflict());
        assertEquals(1, view(table, peer.key()).record().heartbeat());
        assertEquals(0, view(table, restart.key()).record().heartbeat());
        table.merge(List.of(departed(peer)));
        assertTrue(table.snapshot().identityConflicts().isEmpty());
        assertTrue(table.identityConflicts().isEmpty());
        table.mergeDirect(member("Self", 4, 0), List.of());
        assertEquals(java.util.Set.of("Self"), table.snapshot().identityConflicts());
        assertEquals(java.util.Set.of("Self"), table.identityConflicts());
        assertEquals(self, table.snapshot().self());
    }

    @Test void localPublicationIsImmutableAdvancesOnlyOnChangesAndCannotResumeAfterDeparture() {
        var table = table();
        var before = table.snapshot();
        assertEquals(1, table.advanceLocal().heartbeat());
        var input = new LinkedHashMap<String, Object>();
        input.put("instructions", new ArrayList<>(List.of("echo")));
        var published = table.updateLocal("::1", 9010, input);
        assertEquals(2, published.heartbeat());
        input.clear();
        assertEquals(List.of("echo"), published.metadata().get("instructions"));
        assertEquals(published, table.updateLocal("::1", 9010, published.metadata()));
        assertEquals(0, before.self().heartbeat());
        assertThrows(UnsupportedOperationException.class, before.members()::clear);
        var left = table.leaveLocal();
        assertEquals(MemberRecord.State.LEFT, left.state());
        assertEquals(left, table.leaveLocal());
        assertThrows(IllegalStateException.class, table::advanceLocal);
        assertThrows(IllegalStateException.class, () -> table.updateLocal("localhost", 9000, Map.of()));
        clock.advance(1000);
        table.maintain();
        assertEquals(left, table.snapshot().self());
        var exhausted = new MembershipTable(config(4, 8), version(self, Long.MAX_VALUE), clock);
        assertThrows(IllegalStateException.class, exhausted::advanceLocal);
        assertEquals(Long.MAX_VALUE, exhausted.snapshot().self().heartbeat());
        assertEquals(MemberRecord.State.LEFT, exhausted.leaveLocal().state());
    }

    @Test void batchCountsIncludeTheSenderAndDuplicateOrNullRecordsAreRejectedAtomically() {
        var table = new MembershipTable(config(2, 2), self, clock);
        var before = table.snapshot();
        assertReason(INVALID_RECORD, () -> table.merge(List.of(peer, peer)));
        assertReason(INVALID_RECORD, () -> table.merge(Arrays.asList(peer, null)));
        assertReason(INVALID_RECORD, () -> table.merge(null));
        assertReason(INVALID_RECORD, () -> table.mergeDirect(peer, List.of(self, member("Other", 3, 0))));
        assertReason(INVALID_RECORD, () -> table.merge(List.of(peer, self, member("Other", 3, 0))));
        assertEquals(before, table.snapshot());
    }

    @Test void aSingleOversizedRecordCannotBypassExchangeLimitsOrMutateTheLocalRecord() {
        var properties = GossipConfigTest.minimal();
        properties.setProperty("max.exchange.bytes", "1024");
        var table = new MembershipTable(GossipConfig.fromProperties(properties, "Self"), self, clock);
        var before = table.snapshot();
        var metadata = Map.<String, Object>of("large", "a".repeat(1000));
        var oversized = new MemberRecord(peer.key(), peer.host(), peer.port(), 1, MemberRecord.State.ALIVE, metadata);
        assertReason(INVALID_RECORD, () -> table.merge(List.of(oversized)));
        assertReason(INVALID_RECORD, () -> table.updateLocal("localhost", 9000, metadata));
        assertEquals(before, table.snapshot());
    }

    @Test void elapsedTimeUsesSignedNanoTimeDifferencesAcrossWraparound() {
        clock.nanos = Long.MAX_VALUE - TimeUnit.MILLISECONDS.toNanos(5);
        var table = table();
        table.merge(List.of(peer));
        clock.advance(9);
        assertEquals(TimeUnit.MILLISECONDS.toNanos(9), view(table, peer.key()).advanceAgeNanos());
        assertEquals(CANDIDATE, view(table, peer.key()).assessment());
        clock.advance(1);
        assertEquals(SUSPECT, view(table, peer.key()).assessment());
        clock.advance(20);
        assertEquals(DEAD, view(table, peer.key()).assessment());
        assertEquals(MembershipTable.Assessment.ALIVE, view(table, self.key()).assessment());
    }

    @Test @Timeout(10)
    void concurrentMergesPreserveTheHighestVersionAndCannotOverfillTheTable() throws Exception {
        var table = table();
        table.merge(List.of(peer));
        var gate = new CountDownLatch(1);
        var results = new ArrayList<Future<?>>();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 1; i <= 32; i++) {
                int heartbeat = i;
                results.add(workers.submit(() -> {
                    gate.await();
                    table.merge(List.of(version(peer, heartbeat)));
                    return null;
                }));
                results.add(workers.submit(() -> {
                    gate.await();
                    var result = table.merge(List.of(member("Extra-" + heartbeat, 100 + heartbeat, 0)));
                    assertEquals(1, result.admitted() + result.ignored());
                    return null;
                }));
            }
            gate.countDown();
            for (var result : results) { result.get(5, TimeUnit.SECONDS); }
        }
        assertEquals(32, view(table, peer.key()).record().heartbeat());
        assertEquals(4, table.snapshot().activeMembers());
        assertEquals(4, table.snapshot().retainedIdentities());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void saturationSkipsNewIdentitiesButCommitsKnownUpdatesInEitherOrder(boolean excessFirst) {
        var table = new MembershipTable(config(2, 2), self, clock);
        table.mergeDirect(peer, List.of());
        clock.advance(5);
        var updated = version(peer, 2);
        var excess = member("Excess", 3, 1);

        var result = table.merge(excessFirst ? List.of(excess, updated) : List.of(updated, excess));

        assertEquals(new MembershipTable.MergeResult(0, 1, 1), result);
        assertEquals(updated, view(table, peer.key()).record());
        assertEquals(0, view(table, peer.key()).advanceAgeNanos());
        assertEquals(2, table.snapshot().retainedIdentities());
        assertEquals(2, table.snapshot().activeMembers());
    }

    @Test void aSkippedAdmissionDoesNotHideLaterValidationOrVersionConflicts() {
        var table = new MembershipTable(config(2, 4), self, clock);
        table.mergeDirect(peer, List.of());
        clock.advance(5);
        var before = table.snapshot();
        var excess = member("Excess", 3, 1);
        var forged = new MemberRecord(self.key(), "forged.invalid", 9010, 0, self.state(), Map.of());
        assertReason(CONFLICT, () -> table.mergeDirect(version(peer, 2), List.of(excess, forged)));
        assertEquals(before, table.snapshot());
        var malformed = new LinkedHashMap<>(excess.toWire());
        malformed.put("heartbeat", 1.5);
        assertReason(INVALID_RECORD, () -> table.mergeEncoded(List.of(version(peer, 2).toWire(), malformed)));
        assertEquals(before, table.snapshot());
    }

    @Test void skippedSuppressionRecoveryPreservesItsVersionAndCanRecoverAfterAKnownDeparture() {
        var table = new MembershipTable(config(2, 3), self, clock);
        table.merge(List.of(peer));
        clock.advance(60);
        table.maintain();
        var replacement = member("Replacement", 3, 1);
        table.merge(List.of(replacement));
        assertEquals(new MembershipTable.MergeResult(0, 0, 1), table.mergeDirect(peer, List.of()));
        assertEquals(1, table.snapshot().tombstones());
        assertEquals(3, table.snapshot().retainedIdentities());
        var forged = new MemberRecord(peer.key(), "forged.invalid", 9010, peer.heartbeat(), peer.state(), Map.of());
        var before = table.snapshot();
        assertReason(CONFLICT, () -> table.merge(List.of(forged, version(replacement, 2))));
        assertEquals(before, table.snapshot());

        var result = table.mergeDirect(peer, List.of(departed(replacement)));

        assertEquals(new MembershipTable.MergeResult(1, 1, 0), result);
        assertEquals(0, table.snapshot().tombstones());
        assertEquals(3, table.snapshot().retainedIdentities());
        assertEquals(2, table.snapshot().activeMembers());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(60), view(table, peer.key()).advanceAgeNanos());
        assertEquals(0L, view(table, peer.key()).directSuccessAgeNanos());
    }

    @Test void skippedAliveAdmissionsDoNotBlockTerminalRecordsThatStillFit() {
        var table = new MembershipTable(config(2, 3), self, clock);
        table.merge(List.of(peer));
        var excess = member("Excess", 3, 1);
        var left = departed(member("Gone", 4, 1));
        var another = departed(member("Also-Gone", 5, 1));

        var result = table.merge(List.of(excess, left, another));

        assertEquals(new MembershipTable.MergeResult(1, 0, 2), result);
        assertEquals(left, view(table, left.key()).record());
        assertEquals(2, table.snapshot().activeMembers());
        assertEquals(3, table.snapshot().retainedIdentities());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void retainedIdentityRecoveryHasPriorityOverNewAdmissionsInEitherOrder(boolean recoveryFirst) {
        var table = new MembershipTable(config(2, 3), self, clock);
        table.merge(List.of(peer));
        clock.advance(60);
        table.maintain();
        var recovered = version(peer, 2);
        var excess = member("Excess", 3, 1);

        var result = table.merge(recoveryFirst ? List.of(recovered, excess) : List.of(excess, recovered));

        assertEquals(new MembershipTable.MergeResult(1, 0, 1), result);
        assertEquals(recovered, view(table, peer.key()).record());
        assertEquals(2, table.snapshot().activeMembers());
        assertEquals(2, table.snapshot().retainedIdentities());
        assertEquals(0, table.snapshot().tombstones());
    }

    @Test void admissionHintsAndSkipCountsDistinguishCapacityFromDuplicatesAndSuppression() {
        var table = new MembershipTable(config(2, 4), self, clock);
        assertEquals(MembershipTable.Admission.ADMITTED, table.mergeExchange(peer, List.of()).senderAdmission());
        assertEquals(MembershipTable.Admission.ADMITTED, table.mergeExchange(peer, List.of()).senderAdmission());
        assertEquals(0, table.snapshot().admissionsSkipped());
        var excess = member("Excess", 3, 1);
        assertEquals(MembershipTable.Admission.CAPACITY, table.mergeExchange(excess, List.of()).senderAdmission());
        assertEquals(1, table.snapshot().admissionsSkipped());
        assertEquals(MembershipTable.Admission.ADMITTED, table.mergeExchange(peer, List.of(excess)).senderAdmission());
        assertEquals(2, table.snapshot().admissionsSkipped());
        var before = table.snapshot();
        var forged = new MemberRecord(peer.key(), "forged.invalid", 9010, peer.heartbeat(), peer.state(), Map.of());
        assertReason(CONFLICT, () -> table.mergeExchange(excess, List.of(forged)));
        assertEquals(before, table.snapshot(), "A rejected batch must not publish tentative skip counts");
        table.merge(List.of(departed(peer)));
        clock.advance(60);
        table.maintain();
        assertEquals(MembershipTable.Admission.IGNORED, table.mergeExchange(peer, List.of()).senderAdmission());
        assertEquals(2, table.snapshot().admissionsSkipped(), "Terminal suppression is not a capacity skip");
        assertEquals(MembershipTable.Admission.ADMITTED, table.mergeExchange(excess, List.of()).senderAdmission());
    }

    @Test void retainedIdentityCapacityAndOutboundMergesAlsoContributeToSkipCounts() {
        var table = new MembershipTable(config(2, 2), self, clock);
        table.merge(List.of(departed(peer)));
        var excess = member("Excess", 3, 1);
        assertEquals(MembershipTable.Admission.CAPACITY, table.mergeExchange(excess, List.of()).senderAdmission());
        table.mergeDirect(excess, List.of());
        assertEquals(2, table.snapshot().admissionsSkipped());
        assertEquals(1, table.snapshot().activeMembers());
        assertEquals(2, table.snapshot().retainedIdentities());
    }

    private static void assertReason(MembershipException.Reason reason, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(reason, assertThrows(MembershipException.class, operation).reason());
    }

    private static final class Clock implements LongSupplier {
        private long nanos;
        @Override public long getAsLong() { return nanos; }
        void advance(long millis) { nanos += TimeUnit.MILLISECONDS.toNanos(millis); }
    }
}
