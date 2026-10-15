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

import dev.mars.client.TextMessageClient;
import dev.mars.plugins.gossip.fixture.GossipServerFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class GossipCapacityTest {
    static MemberRecord record(String id, int boot, long heartbeat) {
        return new MemberRecord(new MemberKey(id, new UUID(0, boot)), "127.0.0.1", 9000, heartbeat,
                MemberRecord.State.ALIVE, Map.of("payload", "雪".repeat(120)));
    }

    static MemberRecord version(MemberRecord record, long heartbeat) {
        return new MemberRecord(record.key(), record.host(), record.port(), heartbeat, record.state(),
                Map.of("payload", "雪".repeat(120), "revision", heartbeat));
    }

    static void fill(TextMessageClient client, GossipConfig config, List<MemberRecord> records) throws Exception {
        var wire = new GossipWire(config);
        int cursor = 0;
        while (cursor < records.size()) {
            var sender = records.get(cursor++);
            var batch = new ArrayList<MemberRecord>();
            while (cursor < records.size() && batch.size() < config.maxBatchMembers() - 1) {
                batch.add(records.get(cursor));
                if (wire.requestBytes(wire.parameters(sender, batch)).length > config.maxExchangeBytes()) { batch.removeLast(); break; }
                cursor++;
            }
            var reply = client.execute("gossip", wire.parameters(sender, batch));
            assertTrue(GossipWire.bytes(reply).length <= config.maxExchangeBytes());
        }
    }

    static MemberRecord owner(Map<?, ?> row) {
        var fields = new LinkedHashMap<String, Object>();
        for (String key : List.of("serverId", "bootId", "host", "port", "heartbeat", "state", "metadata")) { fields.put(key, row.get(key)); }
        return MemberRecord.fromWire(fields);
    }

    static Map<MemberKey, MemberRecord> records(TextMessageClient client, GossipConfig config) throws Exception {
        var records = new LinkedHashMap<MemberKey, MemberRecord>();
        Object cursor = null;
        do {
            var parameters = new LinkedHashMap<String, Object>(Map.of("operation", "members"));
            if (cursor != null) { parameters.put("cursor", cursor); }
            var reply = client.execute("gossip", parameters);
            assertTrue(GossipWire.bytes(reply).length <= config.maxExchangeBytes());
            var page = (Map<?, ?>) reply.result();
            for (Object value : (List<?>) page.get("members")) {
                var record = owner((Map<?, ?>) value);
                assertNull(records.put(record.key(), record), "Inspection cursor repeated a record");
            }
            cursor = page.get("nextCursor");
        } while (cursor != null);
        return records;
    }

    @ParameterizedTest @CsvSource({"128, false", "128, true", "256, false", "256, true"})
    void full128MemberHostKeepsUpdatingKnownMembersAndSkipsOnlyExcessAdmissions(int retainedCap, boolean departureFirst) throws Exception {
        var config = GossipTestNetwork.config(128, retainedCap, 8, 3072, "127.0.0.1", "");
        var plugin = new GossipPlugin(config, false);
        try (var server = GossipServerFixture.start("Receiver", plugin)) {
            var client = new TextMessageClient("127.0.0.1", server.getPort(), "Receiver");
            var peers = new ArrayList<MemberRecord>();
            for (int i = 0; i < 127; i++) { peers.add(record("Peer-%03d".formatted(i), i + 1, 1)); }
            fill(client, config, peers);
            var before = records(client, config);
            assertEquals(128, before.size());
            assertEquals(128, ((Map<?, ?>) GossipSwarmTest.status(plugin).get("capacity")).get("activeMembers"));
            var wire = new GossipWire(config);
            var excess = record("Excess", 500, 1);
            for (int heartbeat = 2; heartbeat <= 3; heartbeat++) {
                var advancedSender = version(peers.getFirst(), heartbeat);
                var advancedOther = version(peers.get(1), heartbeat);
                var batch = heartbeat == 2 ? List.of(excess, advancedOther) : List.of(advancedOther, excess);
                assertEquals("OK", client.execute("gossip", wire.parameters(advancedSender, batch)).status());
                var updated = records(client, config);
                assertEquals(128, updated.size());
                assertEquals(advancedSender, updated.get(advancedSender.key()));
                assertEquals(advancedOther, updated.get(advancedOther.key()));
                assertFalse(updated.containsKey(excess.key()));
            }
            var retiring = peers.getLast();
            var left = new MemberRecord(retiring.key(), retiring.host(), retiring.port(), retiring.heartbeat(), MemberRecord.State.LEFT, retiring.metadata());
            var nextSender = version(peers.getFirst(), 4);
            client.execute("gossip", wire.parameters(nextSender, departureFirst ? List.of(left, excess) : List.of(excess, left)));
            var updated = records(client, config);
            assertEquals(nextSender, updated.get(nextSender.key()));
            assertEquals(left, updated.get(left.key()));
            assertEquals(retainedCap != 128, updated.containsKey(excess.key()), "A freed active slot must still respect retained-identity capacity");
            assertEquals(retainedCap == 128 ? 128 : 129, updated.size());
            var capacity = (Map<?, ?>) GossipSwarmTest.status(plugin).get("capacity");
            assertEquals(retainedCap == 128 ? 127 : 128, capacity.get("activeMembers"));
            assertTrue(((Number) capacity.get("retainedIdentities")).intValue() <= retainedCap);
            assertEquals(0L, ((Number) ((Map<?, ?>) GossipSwarmTest.status(plugin).get("exchanges")).get("rejected")).longValue());
            System.out.printf("Capacity test PASSED | members=128 | retainedCap=%d | departureFirst=%s | known updates preserved%n", retainedCap, departureFirst);
        }
    }

    @Test void concurrentAdmissionsCompeteForTheLastSlotWhileBothKnownUpdatesCommit() throws Exception {
        var config = GossipTestNetwork.config(4, 8, 4, 4096, "127.0.0.1", "");
        var plugin = new GossipPlugin(config, false);
        try (var server = GossipServerFixture.start("Receiver", plugin);
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var client = new TextMessageClient("127.0.0.1", server.getPort(), "Receiver");
            var one = record("One", 1, 1);
            var two = record("Two", 2, 1);
            fill(client, config, List.of(one, two));
            var gate = new CountDownLatch(1);
            var wire = new GossipWire(config);
            var newOne = record("New-One", 3, 1);
            var newTwo = record("New-Two", 4, 1);
            var first = workers.submit(() -> {
                gate.await();
                return client.execute("gossip", wire.parameters(version(one, 2), List.of(newOne)));
            });
            var second = workers.submit(() -> {
                gate.await();
                return client.execute("gossip", wire.parameters(version(two, 2), List.of(newTwo)));
            });
            gate.countDown();
            assertEquals("OK", first.get(3, TimeUnit.SECONDS).status());
            assertEquals("OK", second.get(3, TimeUnit.SECONDS).status());
            var records = records(client, config);
            assertEquals(4, records.size());
            assertNotEquals(records.containsKey(newOne.key()), records.containsKey(newTwo.key()));
            assertEquals(version(one, 2), records.get(one.key()));
            assertEquals(version(two, 2), records.get(two.key()));
        }
    }
}
