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
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class GossipLogTest {
    private final GossipConfig config = GossipConfig.fromProperties(GossipConfigTest.minimal(), "Server-001");
    private final MemberRecord self = PeerSelectorTest.record("Server-001", 1, 9000);
    private final GossipConfig.Seed peer = new GossipConfig.Seed("Server-002", "127.0.0.1", 9010);
    private final AtomicLong clock = new AtomicLong();
    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final GossipLog log = new GossipLog(config, self, clock::get, lines::add);

    @Test void conflictIncludesTimeInstancePeerDirectionAndCorrelation() {
        var messageId = UUID.randomUUID();
        log.identity("outbound", peer, messageId, "Gossip server ID mismatch");
        var line = lines.getFirst();
        assertDoesNotThrow(() -> Instant.parse(line.substring(1, line.indexOf(']'))));
        for (String field : List.of("WARN", "plugin=gossip", "event=IDENTITY_CONFLICT", "serverId=Server-001",
                "bootId=" + self.key().bootId(), "swarmId=demo-swarm", "direction=outbound", "peerServerId=Server-002",
                "peerHost=127.0.0.1", "peerPort=9010", "messageId=" + messageId, "suppressed=0")) {
            assertTrue(line.contains(field), line);
        }
    }

    @Test void duplicateServerIdsAreErrorsInAWindowOrdinaryIdentityFailuresCannotUse() {
        for (int i = 0; i < 5; i++) { log.identity("outbound", peer, null, "Gossip server ID mismatch"); }
        log.duplicateServerIds("outbound", peer, null, "Reachable duplicate server IDs: [Server-002]");
        log.duplicateServerIds("outbound", peer, null, "Reachable duplicate server IDs: [Server-002]");
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("] WARN | plugin=gossip | event=IDENTITY_CONFLICT"), lines.get(0));
        assertTrue(lines.get(1).contains("] ERROR | plugin=gossip | event=IDENTITY_CONFLICT"), lines.get(1));
        assertTrue(lines.get(1).contains("reason=Reachable duplicate server IDs: [Server-002] | suppressed=0"), lines.get(1));
    }

    @Test void repeatedFailuresUseIndependentWindowsAndReportSuppressionOnTheNextEntry() {
        log.identity("inbound", peer, null, "conflict");
        for (int i = 0; i < 100; i++) { log.identity("inbound", peer, null, "conflict"); }
        log.roundFailure(new IllegalStateException("heartbeat exhausted"));
        log.roundFailure(new IllegalStateException("heartbeat exhausted"));
        assertEquals(2, lines.size());
        clock.set(TimeUnit.SECONDS.toNanos(30) - 1);
        log.identity("outbound", peer, null, "conflict");
        assertEquals(2, lines.size());
        clock.incrementAndGet();
        log.identity("outbound", peer, null, "conflict");
        log.roundFailure(new IllegalStateException("heartbeat exhausted"));
        assertEquals(4, lines.size());
        assertTrue(lines.get(2).contains("suppressed=101"));
        assertTrue(lines.get(3).contains("suppressed=1"));
        clock.addAndGet(TimeUnit.SECONDS.toNanos(30));
        log.identity("inbound", peer, null, "conflict");
        assertTrue(lines.getLast().contains("suppressed=0"));
    }

    @Test void concurrentPeersShareOneBoundedInstanceWindow() throws Exception {
        var gate = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var calls = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 100; i++) {
                var target = new GossipConfig.Seed("Peer-" + i, "127.0.0.1", 9010 + i);
                calls.add(workers.submit(() -> { gate.await(); log.identity("inbound", target, null, "conflict"); return null; }));
            }
            gate.countDown();
            for (var call : calls) { call.get(3, TimeUnit.SECONDS); }
        }
        assertEquals(1, lines.size());
        clock.set(TimeUnit.SECONDS.toNanos(30));
        log.identity("inbound", peer, null, "conflict");
        assertTrue(lines.getLast().contains("suppressed=99"));
    }

    @Test void instancesWithTheSameLogicalIdDoNotShareSuppressionState() {
        var restarted = PeerSelectorTest.record("Server-001", 2, 9000);
        var other = new GossipLog(config, restarted, clock::get, lines::add);
        log.identity("inbound", peer, null, "conflict");
        other.identity("inbound", peer, null, "conflict");
        assertEquals(2, lines.size());
        assertTrue(lines.getFirst().contains("bootId=" + self.key().bootId()));
        assertTrue(lines.getLast().contains("bootId=" + restarted.key().bootId()));
    }

    @Test void limiterUsesElapsedTimeAcrossSignedNanoTimeWraparound() {
        clock.set(Long.MAX_VALUE - TimeUnit.SECONDS.toNanos(5));
        log.identity("inbound", peer, null, "conflict");
        clock.addAndGet(TimeUnit.SECONDS.toNanos(10));
        log.identity("inbound", peer, null, "conflict");
        assertEquals(1, lines.size());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(20));
        log.identity("inbound", peer, null, "conflict");
        assertEquals(2, lines.size());
        assertTrue(lines.getLast().contains("suppressed=1"));
    }

    @Test void untrustedFailureTextIsBoundedAndCannotCreateNewLinesOrFields() {
        log.identity("inbound", peer, null, "bad\n\r\t\u001b|forged\\\u2028\u2029" + "x".repeat(10000));
        var line = lines.getFirst();
        assertEquals(1, line.lines().count());
        assertFalse(line.contains("\r"));
        assertFalse(line.contains("\u001b"));
        assertTrue(line.contains("\\u000a\\u000d\\u0009\\u001b\\u007cforged\\\\\\u2028\\u2029"));
        assertTrue(line.contains("..."));
        assertTrue(line.length() < 1500, line);
    }

    @ParameterizedTest @CsvSource({"COMPLETED, 0, INFO", "COMPLETED, 1, WARN", "EXHAUSTED, 0, WARN", "CANCELLED, 0, INFO", "FAILED, 0, WARN"})
    void departureReportsTheOutcomeAndAttemptCounts(String state, int failures, String level) {
        log.departure(Map.of("state", state, "targets", 3, "attempts", 2, "successes", 1,
                "failures", failures, "skipped", 0, "cancelled", 1));
        var line = lines.getFirst();
        for (var field : List.of(level, "event=DEPARTURE_FINISHED", "state=" + state, "targets=3", "attempts=2",
                "successes=1", "failures=" + failures, "skipped=0", "cancelled=1")) { assertTrue(line.contains(field), line); }
    }

    @Test void aBrokenDiagnosticSinkDoesNotThrowIntoApplicationCode() {
        var broken = new GossipLog(config, self, clock::get, line -> { throw new IllegalStateException("stderr unavailable"); });
        assertDoesNotThrow(() -> broken.identity("inbound", peer, null, "conflict"));
        assertDoesNotThrow(() -> broken.roundFailure(new IllegalStateException("round failure")));
        assertDoesNotThrow(() -> broken.cleanupFailure("transport", new IOException("close failure")));
    }
}
