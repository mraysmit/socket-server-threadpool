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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(40)
class GossipPropagationTest {
    @Test void actualRequestAndResponseBatchesTransmitAll128RecordsDespiteContinuousChangesAndDeparture() throws Exception {
        var config = GossipTestNetwork.config(128, 256, 8, 3072, "127.0.0.1", "");
        try (var network = new GossipTestNetwork()) {
            var receiver = network.start("Receiver", config, false);
            var self = new MemberRecord(new MemberKey("Source", UUID.randomUUID()), "127.0.0.1", 9000, 1, MemberRecord.State.ALIVE, Map.of());
            var source = new MembershipTable(config, self);
            source.merge(List.of(receiver.self()));
            var peers = new ArrayList<MemberRecord>();
            for (int i = 0; i < 126; i++) { peers.add(GossipCapacityTest.record("Peer-%03d".formatted(i), i + 1, 1)); }
            for (int i = 0; i < peers.size(); i += 8) { source.merge(peers.subList(i, Math.min(peers.size(), i + 8))); }
            var wire = new GossipWire(config);
            try (var transport = new GossipTransport(config, source)) {
                long began = System.nanoTime();
                for (int i = 0; i < 254 && receiver.members().size() < 128; i++) { transport.exchange(receiver.endpoint()); }
                assertEquals(128, receiver.members().size(), "Multiple batches did not fill the receiver to capacity");
                long convergence = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
                receiver.relay().clearSamples();
                var requested = new HashSet<MemberKey>();
                var replied = new HashSet<MemberKey>();
                var terminal = peers.getLast();
                var left = new MemberRecord(terminal.key(), terminal.host(), terminal.port(), terminal.heartbeat(), MemberRecord.State.LEFT, terminal.metadata());
                int leftRequest = -1;
                int leftReply = -1;
                boolean bytesLimited = false;
                // Even with only one free record per frame, alternate scan slots suffice in 2*(members-1) exchanges.
                for (int round = 0; round < 254; round++) {
                    var changed = new ArrayList<MemberRecord>();
                    for (int hot = 0; hot < 4; hot++) { changed.add(GossipCapacityTest.version(peers.get(hot), round + 2)); }
                    if (round == 20) { changed.add(left); }
                    source.merge(changed);
                    transport.exchange(receiver.endpoint());
                    var samples = receiver.relay().samples();
                    var sample = samples.getLast();
                    assertTrue(sample.requestBytes() <= config.maxExchangeBytes());
                    assertTrue(sample.replyBytes() <= config.maxExchangeBytes());
                    assertEquals(sample.request().messageId(), sample.reply().messageId());
                    var request = wire.decode(sample.request().parameters(), true);
                    var response = wire.decode(sample.reply().result(), false);
                    assertTrue(request.records().size() <= config.maxBatchMembers());
                    assertTrue(response.records().size() <= config.maxBatchMembers());
                    assertEquals(request.records().size(), request.records().stream().map(MemberRecord::key).distinct().count());
                    assertEquals(response.records().size(), response.records().stream().map(MemberRecord::key).distinct().count());
                    request.records().forEach(record -> requested.add(record.key()));
                    response.records().forEach(record -> replied.add(record.key()));
                    if (request.records().contains(left) && leftRequest < 0) { leftRequest = round; }
                    if (response.records().contains(left) && leftReply < 0) { leftReply = round; }
                    if (request.records().size() < config.maxBatchMembers() && response.records().size() < config.maxBatchMembers()) { bytesLimited = true; }
                    assertEquals(0, transport.inflight());
                }
                var expected = new HashSet<MemberKey>();
                source.snapshot().members().forEach(member -> expected.add(member.record().key()));
                assertEquals(128, expected.size());
                assertEquals(expected, requested, "Request priority changes starved retained records");
                assertEquals(expected, replied, "Response priority changes starved retained records");
                assertTrue(bytesLimited, "Test did not exercise truncation by bytes below the record cap");
                assertTrue(leftRequest >= 20 && leftRequest <= 22, "Departure did not receive prompt request priority");
                assertTrue(leftReply >= leftRequest, "Departure was never returned on the response wire");
                var finalRecords = GossipCapacityTest.records(receiver.client(), config);
                assertEquals(left, finalRecords.get(left.key()));
                System.out.printf("GOSSIP_MEASURE wire-cap members=128 batch=8 bytes=3072 convergenceMs=%d fairnessExchanges=254 requestCoverage=%d responseCoverage=%d leftRequestRound=%d leftReplyRound=%d%n",
                        convergence, requested.size(), replied.size(), leftRequest, leftReply);
            }
        }
    }

    @Test void droppedRequestsAndRepliesRecoverAndAnOlderDelayedReplyCannotRollBackANewerOwnerVersion() throws Exception {
        var config = GossipTestNetwork.config(8, 16, 3, 4096, "127.0.0.1", "");
        try (var network = new GossipTestNetwork(); var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var peer = network.start("Peer", config, true);
            var table = new MembershipTable(config, new MemberRecord(new MemberKey("Source", UUID.randomUUID()), "127.0.0.1", 9000, 1, MemberRecord.State.ALIVE, Map.of()));
            try (var transport = new GossipTransport(config, table, Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(5))) {
                peer.relay().dropRequests.set(1);
                assertThrows(IOException.class, () -> transport.exchange(peer.endpoint()));
                assertEquals(1, table.snapshot().retainedIdentities());
                assertEquals(0, transport.inflight());
                peer.relay().dropReplies.set(1);
                assertThrows(IOException.class, () -> transport.exchange(peer.endpoint()));
                assertEquals(1, table.snapshot().retainedIdentities(), "A dropped reply must not commit the receiver's membership");
                assertEquals(0, transport.inflight());
                var delay = new GossipTestNetwork.Delay();
                peer.relay().delayNextReply.set(delay);
                var old = callers.submit(() -> transport.exchange(peer.endpoint()));
                try {
                    var captured = delay.captured.get(3, TimeUnit.SECONDS);
                    var oldSender = new GossipWire(config).decode(captured.result(), false).sender();
                    GossipTestNetwork.await(Duration.ofSeconds(3), () -> peer.self().heartbeat() > oldSender.heartbeat());
                    var current = transport.exchange(peer.endpoint()).sender();
                    assertTrue(current.heartbeat() > oldSender.heartbeat());
                    delay.release.countDown();
                    assertEquals(oldSender, old.get(3, TimeUnit.SECONDS).sender());
                    var retained = table.snapshot().members().stream().filter(member -> member.record().key().equals(current.key())).findFirst().orElseThrow();
                    assertEquals(current, retained.record(), "An older completed exchange rolled back a newer heartbeat");
                    assertEquals(MembershipTable.Assessment.ALIVE, retained.assessment());
                    assertEquals(2, network.dropped.get());
                    assertEquals(0, transport.inflight());
                    assertEquals(2, table.snapshot().retainedIdentities());
                } finally { delay.release.countDown(); }
            }
        }
    }
}
