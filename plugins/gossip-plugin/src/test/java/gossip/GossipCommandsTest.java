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

import dev.mars.plugin.InvalidParametersException;
import dev.mars.plugin.PluginContext;
import dev.mars.protocol.CommandReply;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GossipCommandsTest {
    static GossipConfig config(int bytes, int batch, int members) {
        var properties = GossipConfigTest.minimal();
        properties.setProperty("max.exchange.bytes", Integer.toString(bytes));
        properties.setProperty("max.batch.members", Integer.toString(batch));
        properties.setProperty("max.members", Integer.toString(members));
        return GossipConfig.fromProperties(properties, "Self");
    }

    static MemberRecord member(String id, long boot) {
        return new MemberRecord(new MemberKey(id, new UUID(0, boot)), "127.0.0.1", 9000, 1,
                MemberRecord.State.ALIVE, Map.of());
    }

    private static GossipPlugin plugin(GossipConfig config) throws Exception {
        var plugin = new GossipPlugin(config, false);
        plugin.start(new PluginContext("Self", 9000, 9001));
        return plugin;
    }

    static Map<?, ?> execute(GossipPlugin plugin, Map<String, Object> parameters) throws Exception {
        return assertInstanceOf(Map.class, plugin.execute(parameters));
    }

    @Test void exchangeMergesOnlyValidatedOwnerRecordsAndReportsLocalDiagnostics() throws Exception {
        var config = config(32768, 32, 128);
        var plugin = plugin(config);
        try {
            var wire = new GossipWire(config);
            var peer = member("Peer", 2);
            var result = execute(plugin, wire.parameters(peer, List.of(member("Forwarded", 3))));
            assertEquals(Set.of("gossipVersion", "swarmId", "sender", "members"), result.keySet());
            assertEquals("Self", ((Map<?, ?>) result.get("sender")).get("serverId"));
            var status = execute(plugin, Map.of("operation", "status"));
            assertEquals(false, status.get("isolated"));
            assertEquals(3, ((Map<?, ?>) status.get("capacity")).get("activeMembers"));
            assertEquals(2, ((Map<?, ?>) status.get("assessments")).get("CANDIDATE"));
            assertEquals(1L, ((Map<?, ?>) status.get("exchanges")).get("inbound"));
            var page = execute(plugin, Map.of("operation", "members"));
            assertEquals(3, ((List<?>) page.get("members")).size());
            assertNull(page.get("nextCursor"));
            var remote = ((List<?>) page.get("members")).stream().map(Map.class::cast)
                    .filter(row -> "Peer".equals(row.get("serverId"))).findFirst().orElseThrow();
            assertEquals("CANDIDATE", remote.get("assessment"));
            assertNull(remote.get("directSuccessAgeMillis"));
            assertEquals(false, remote.get("endpointVerified"));
        } finally { plugin.stop(); }
    }

    @Test void malformedVersionsSwarmsRecordsAndDuplicateKeysCannotPartiallyMerge() throws Exception {
        var config = config(32768, 4, 4);
        var plugin = plugin(config);
        try {
            var wire = new GossipWire(config);
            var peer = member("Peer", 2);
            var valid = wire.parameters(peer, List.of());
            var cases = new ArrayList<Map<String, Object>>();
            for (var change : Map.<String, Object>of("gossipVersion", new BigDecimal("1.0"), "swarmId", "other",
                    "sender", "not-an-object", "members", "not-an-array", "unknown", true).entrySet()) {
                var parameters = new LinkedHashMap<>(valid);
                parameters.put(change.getKey(), change.getValue());
                cases.add(parameters);
            }
            cases.add(wire.parameters(peer, List.of(peer)));
            var malformed = new LinkedHashMap<>(member("Bad", 3).toWire());
            malformed.put("heartbeat", -1);
            var partial = new LinkedHashMap<>(valid);
            partial.put("members", List.of(malformed));
            cases.add(partial);
            for (var parameters : cases) {
                assertThrows(InvalidParametersException.class, () -> plugin.execute(parameters));
                var status = execute(plugin, Map.of("operation", "status"));
                assertEquals(1, ((Map<?, ?>) status.get("capacity")).get("retainedIdentities"));
                assertEquals(true, status.get("isolated"));
            }
        } finally { plugin.stop(); }
    }

    @Test void completeEnvelopeLimitRejectsARecordThatFitsTheRecordOnlyBudget() throws Exception {
        var config = config(1536, 32, 128);
        var plugin = plugin(config);
        try {
            var base = member("Peer", 2);
            var peer = new MemberRecord(base.key(), base.host(), base.port(), 1, base.state(), Map.of("text", "a".repeat(1250)));
            assertTrue(peer.serializedBytes() < config.maxExchangeBytes());
            var parameters = new GossipWire(config).parameters(peer, List.of());
            assertTrue(new GossipWire(config).requestBytes(parameters).length > config.maxExchangeBytes());
            assertThrows(InvalidParametersException.class, () -> plugin.execute(parameters));
            assertEquals(1, ((List<?>) execute(plugin, Map.of("operation", "members")).get("members")).size());
        } finally { plugin.stop(); }
    }

    @Test void pagesRespectTheCompleteReplyBudgetAndOpaqueCursorsCoverTheWholeTable() throws Exception {
        var config = config(2048, 32, 128);
        var plugin = plugin(config);
        try {
            var wire = new GossipWire(config);
            for (int i = 0; i < 10; i++) { plugin.execute(wire.parameters(member("Peer-" + i, 2 + i), List.of())); }
            var seen = new HashSet<String>();
            Object cursor = null;
            var cursors = new HashSet<Object>();
            do {
                var parameters = new LinkedHashMap<String, Object>(Map.of("operation", "members", "limit", 32));
                if (cursor != null) { parameters.put("cursor", cursor); }
                var page = execute(plugin, parameters);
                var reply = new CommandReply(1, UUID.randomUUID(), "Self", Long.MAX_VALUE, "OK", page, null);
                assertTrue(GossipWire.bytes(reply).length <= 2048);
                for (Object item : (List<?>) page.get("members")) {
                    assertTrue(seen.add((String) ((Map<?, ?>) item).get("serverId")));
                }
                cursor = page.get("nextCursor");
                if (cursor != null) { assertTrue(cursors.add(cursor)); }
            } while (cursor != null);
            assertEquals(11, seen.size());
        } finally { plugin.stop(); }
    }

    @Test void cursorsAreInstanceBoundAndLimitsAreExactIntegers() throws Exception {
        var config = config(32768, 2, 128);
        var first = plugin(config);
        var second = plugin(config);
        try {
            first.execute(new GossipWire(config).parameters(member("Peer", 2), List.of()));
            var firstPage = execute(first, Map.of("operation", "members", "limit", 1));
            Object cursor = firstPage.get("nextCursor");
            assertNotNull(cursor);
            assertEquals(1, ((List<?>) execute(first, Map.of("operation", "members", "cursor", cursor, "limit", 1)).get("members")).size());
            assertThrows(InvalidParametersException.class, () -> second.execute(Map.of("operation", "members", "cursor", cursor)));
            for (Object invalid : List.of(0, 3, new BigDecimal("1.0"), "1")) {
                assertThrows(InvalidParametersException.class, () -> first.execute(Map.of("operation", "members", "limit", invalid)));
            }
            for (Object invalid : List.of("!", "a".repeat(129), 1)) {
                assertThrows(InvalidParametersException.class, () -> first.execute(Map.of("operation", "members", "cursor", invalid)));
            }
            assertThrows(InvalidParametersException.class, () -> first.execute(Map.of("operation", "members", "unknown", 1)));
        } finally { first.stop(); second.stop(); }
    }

    @Test void boundedResponseBatchesRotateThroughAllRetainedMembers() throws Exception {
        var config = config(32768, 2, 128);
        var plugin = plugin(config);
        try {
            var wire = new GossipWire(config);
            for (int i = 0; i < 5; i++) { plugin.execute(wire.parameters(member("Peer-" + i, i + 2), List.of())); }
            var seen = new HashSet<MemberKey>();
            for (int i = 0; i < 5; i++) {
                var response = wire.decode(plugin.execute(wire.parameters(member("Peer-0", 2), List.of())), false);
                assertEquals(1, response.members().size());
                seen.add(response.members().getFirst().key());
            }
            assertEquals(5, seen.size());
        } finally { plugin.stop(); }
    }

    @Test void inboundConflictIsLoggedOnceWhileTheOriginalErrorAndAtomicRollbackArePreserved() throws Exception {
        var config = config(32768, 4, 8);
        var clock = new java.util.concurrent.atomic.AtomicLong();
        var table = new MembershipTable(config, member("Self", 1), clock::get);
        var lines = new ArrayList<String>();
        var metrics = new GossipMetrics(new GossipLog(config, table.snapshot().self(), clock::get, lines::add));
        var transport = new GossipTransport(config, table, metrics);
        try (var runtime = new GossipRuntime(config, table, transport, metrics)) {
            var commands = new GossipCommands(config, table, metrics, transport, runtime);
            var wire = new GossipWire(config);
            var peer = member("Peer", 2);
            commands.execute(wire.parameters(peer, List.of()));
            var before = table.snapshot();
            var changed = new MemberRecord(peer.key(), "changed.invalid", 9010, peer.heartbeat(), peer.state(), Map.of());
            for (int attempt = 0; attempt < 3; attempt++) {
                var failure = assertThrows(InvalidParametersException.class,
                        () -> commands.execute(wire.parameters(changed, List.of(member("Uncommitted", 3)))));
                assertTrue(failure.getMessage().startsWith("CONFLICT:"));
                assertEquals(before, table.snapshot());
            }
            assertEquals(3, metrics.rejectedExchanges.get());
            assertEquals(1, lines.size());
            assertTrue(lines.getFirst().contains("event=IDENTITY_CONFLICT"));
            assertTrue(lines.getFirst().contains("direction=inbound"));
            assertTrue(lines.getFirst().contains("peerServerId=Peer"));
        }
    }

    @Test void statusRemainsStoppingUntilOwnedResourceCleanupActuallyCompletes() throws Exception {
        var config = config(32768, 4, 8);
        var table = new MembershipTable(config, member("Self", 1));
        var metrics = new GossipMetrics();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var client = new GossipRuntime.ExchangeClient() {
            @Override public GossipWire.Exchange exchange(GossipConfig.Seed peer) { throw new AssertionError("No targets"); }
            @Override public void close() throws java.io.IOException {
                entered.countDown();
                try { release.await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.InterruptedIOException("Cleanup interrupted"); }
            }
        };
        try (var transport = new GossipTransport(config, table, metrics);
             var runtime = new GossipRuntime(config, table, client, metrics, System::nanoTime, new java.util.Random(1), new GossipDnsResolver(8, 2));
             var callers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var commands = new GossipCommands(config, table, metrics, transport, runtime);
                var stopping = callers.submit(() -> { runtime.close(); return null; });
                try {
                    assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
                    var status = (Map<?, ?>) commands.execute(Map.of("operation", "status"));
                    assertEquals("STOPPING", status.get("workerState"));
                    assertFalse(stopping.isDone());
                } finally { release.countDown(); }
                stopping.get(2, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals("STOPPED", ((Map<?, ?>) commands.execute(Map.of("operation", "status"))).get("workerState"));
            } finally { release.countDown(); }
        }
    }

    @Test void versionTwoAdmissionHintsKeepLegacyReplyShapeAndExposeCommittedSkipCounts() throws Exception {
        var config = config(32768, 4, 2);
        var plugin = plugin(config);
        try {
            var wire = new GossipWire(config);
            var peer = member("Peer", 2);
            var first = execute(plugin, wire.parameters(peer, List.of(), 2));
            assertEquals("ADMITTED", first.get("senderAdmission"));
            assertEquals(2, first.get("gossipVersion"));
            var full = execute(plugin, wire.parameters(member("Other", 3), List.of(), 2));
            assertEquals("CAPACITY", full.get("senderAdmission"));
            var known = execute(plugin, wire.parameters(peer, List.of(member("Forwarded", 4)), 2));
            assertEquals("ADMITTED", known.get("senderAdmission"));
            var legacy = execute(plugin, wire.parameters(member("Legacy", 5), List.of()));
            assertEquals(Set.of("gossipVersion", "swarmId", "sender", "members"), legacy.keySet());
            assertEquals(3L, ((Map<?, ?>) execute(plugin, Map.of("operation", "status")).get("capacity")).get("admissionsSkipped"));
            var before = execute(plugin, Map.of("operation", "status"));
            var invalid = new LinkedHashMap<>(wire.parameters(member("Bad", 6), List.of(), 2));
            invalid.put("senderAdmission", "ADMITTED");
            assertThrows(InvalidParametersException.class, () -> plugin.execute(invalid));
            assertEquals(((Map<?, ?>) before.get("capacity")).get("admissionsSkipped"),
                    ((Map<?, ?>) execute(plugin, Map.of("operation", "status")).get("capacity")).get("admissionsSkipped"));
        } finally { plugin.stop(); }
    }

    @Test void saturationRepliesSuccessfullyButVersionConflictsStillReturnTypedErrors() throws Exception {
        var config = config(32768, 4, 2);
        var plugin = plugin(config);
        try {
            var wire = new GossipWire(config);
            var peer = member("Peer", 2);
            plugin.execute(wire.parameters(peer, List.of()));
            assertNotNull(plugin.execute(wire.parameters(member("Other", 3), List.of())));
            var changed = new MemberRecord(peer.key(), "elsewhere.invalid", 9000, 1, peer.state(), Map.of());
            var conflict = assertThrows(InvalidParametersException.class, () -> plugin.execute(wire.parameters(changed, List.of())));
            assertTrue(conflict.getMessage().startsWith("CONFLICT:"));
            assertEquals(2, ((Map<?, ?>) execute(plugin, Map.of("operation", "status")).get("capacity")).get("retainedIdentities"));
        } finally { plugin.stop(); }
    }
}
