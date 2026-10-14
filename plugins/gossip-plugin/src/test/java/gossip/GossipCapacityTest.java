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
package gossip;

import dev.mars.client.CommandExecutionException;
import dev.mars.client.TextMessageClient;
import dev.mars.protocol.CommandError;
import dev.mars.server.GossipServerFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest @ValueSource(ints = {128, 256})
    void full128MemberHostAcceptsUpdatesButRejectsMixedExcessAdmissionsAtomically(int retainedCap) throws Exception {
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
            var advancedSender = version(peers.getFirst(), 2);
            var advancedOther = version(peers.get(1), 2);
            var excess = record("Excess", 500, 1);
            for (var batch : List.of(List.of(excess, advancedOther), List.of(advancedOther, excess))) {
                var error = assertThrows(CommandExecutionException.class, () -> client.execute("gossip", wire.parameters(advancedSender, batch)));
                assertEquals(CommandError.Code.INVALID_PARAMETERS, error.reply().error().code());
                assertTrue(error.reply().error().message().startsWith("CAPACITY:"));
                assertEquals(before, records(client, config), "Capacity rejection partially changed owner versions or metadata");
            }
            client.execute("gossip", wire.parameters(advancedSender, List.of(advancedOther)));
            var updated = records(client, config);
            assertEquals(128, updated.size());
            assertEquals(advancedSender, updated.get(advancedSender.key()));
            assertEquals(advancedOther, updated.get(advancedOther.key()));

            var retiring = peers.getLast();
            var left = new MemberRecord(retiring.key(), retiring.host(), retiring.port(), retiring.heartbeat(), MemberRecord.State.LEFT, retiring.metadata());
            client.execute("gossip", wire.parameters(advancedSender, List.of(left)));
            var beforeReplacement = records(client, config);
            var nextSender = version(advancedSender, 3);
            if (retainedCap == 128) {
                var error = assertThrows(CommandExecutionException.class, () -> client.execute("gossip", wire.parameters(nextSender, List.of(excess))));
                assertTrue(error.reply().error().message().startsWith("CAPACITY:"));
                assertEquals(beforeReplacement, records(client, config), "A free active slot must not bypass retained-identity capacity");
                client.execute("gossip", wire.parameters(nextSender, List.of()));
                assertEquals(nextSender, records(client, config).get(nextSender.key()));
            } else {
                client.execute("gossip", wire.parameters(nextSender, List.of(excess)));
                assertEquals(129, records(client, config).size());
            }
            var capacity = (Map<?, ?>) GossipSwarmTest.status(plugin).get("capacity");
            assertEquals(retainedCap == 128 ? 127 : 128, capacity.get("activeMembers"));
            assertTrue(((Number) capacity.get("retainedIdentities")).intValue() <= retainedCap);
        }
    }

    @Test void concurrentAdmissionsCompeteForTheLastSlotWithoutPartialUpdatesFromTheRejectedBatch() throws Exception {
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
            var first = workers.submit(() -> {
                gate.await();
                try { client.execute("gossip", wire.parameters(version(one, 2), List.of(record("New-One", 3, 1)))); return true; }
                catch (CommandExecutionException e) { assertTrue(e.reply().error().message().startsWith("CAPACITY:")); return false; }
            });
            var second = workers.submit(() -> {
                gate.await();
                try { client.execute("gossip", wire.parameters(version(two, 2), List.of(record("New-Two", 4, 1)))); return true; }
                catch (CommandExecutionException e) { assertTrue(e.reply().error().message().startsWith("CAPACITY:")); return false; }
            });
            gate.countDown();
            boolean firstWon = first.get(3, TimeUnit.SECONDS);
            boolean secondWon = second.get(3, TimeUnit.SECONDS);
            assertNotEquals(firstWon, secondWon);
            var records = records(client, config);
            assertEquals(4, records.size());
            assertEquals(firstWon ? version(one, 2) : one, records.get(one.key()));
            assertEquals(secondWon ? version(two, 2) : two, records.get(two.key()));
        }
    }
}
