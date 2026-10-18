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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Size checks add up record sizes instead of encoding whole envelopes. Each test keeps the original encode-everything
 * algorithm as a reference and requires identical decisions, sweeping record sizes across the byte limit.
 */
class GossipSizeAccountingTest {
    // Characters JSON must escape or encode as several UTF-8 bytes; size arithmetic must match them exactly.
    private static final int[] TRICKY = {'"', '\\', '/', 0, 0x1f, 0x2028, 0xe9, 0x8282, 0x1F600};

    private static Map<String, Object> metadata(int length, int variant) {
        var text = new StringBuilder();
        for (int i = 0; i < length; i++) {
            if (i % 5 == 0) { text.appendCodePoint(TRICKY[(i / 5 + variant) % TRICKY.length]); }
            else { text.append((char) ('a' + i % 26)); }
        }
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("text", text.toString());
        if (variant % 2 == 0) {
            metadata.put("numbers", List.of(1, -2, new BigDecimal("3.25"), new BigInteger("123456789012345678901234567890")));
        }
        if (variant % 3 == 0) {
            var nested = new HashMap<String, Object>();
            nested.put("flag", true);
            nested.put("none", null);
            nested.put("é节", List.of("x", Map.of()));
            metadata.put("nested", nested);
        }
        return metadata;
    }

    /** One byte per step, so a sweep lands on every exact byte count, including each limit itself. */
    private static Map<String, Object> ascii(int length) { return Map.of("text", "x".repeat(length)); }

    private static MemberRecord record(String id, long boot, long heartbeat, Map<String, Object> metadata) {
        return new MemberRecord(new MemberKey(id, new UUID(7, boot)), "host-" + id.toLowerCase() + ".example", 9000 + (int) boot,
                heartbeat, MemberRecord.State.ALIVE, metadata);
    }

    private static String outcome(Runnable check) {
        try { check.run(); return "OK"; }
        catch (MembershipException e) { return e.getMessage(); }
    }

    @Test void requestSizeMatchesTheEncodedRequestExactly() {
        var config = GossipCommandsTest.config(65536, 8, 16);
        var wire = new GossipWire(config);
        for (int length = 0; length <= 600; length += 37) {
            for (int variant = 0; variant < 6; variant++) {
                var sender = record("Sender", 1, length * 1_000_003L, metadata(length, variant));
                var members = new ArrayList<MemberRecord>();
                for (int count = 0; count <= 4; count++) {
                    for (int version = 1; version <= 2; version++) {
                        assertEquals(wire.requestBytes(wire.toWire(new GossipWire.ExchangeRequest(sender, members))).length, wire.requestSize(sender, members),
                                "length=" + length + " variant=" + variant + " members=" + count + " version=" + version);
                    }
                    members.add(record("Member-" + count, 10 + count, count, metadata(length / (count + 1), variant + count)));
                }
                assertEquals(config.maxExchangeBytes() - wire.requestBytes(wire.toWire(new GossipWire.ExchangeRequest(sender, List.of()))).length,
                        wire.requestMemberBudget(sender));
            }
        }
    }

    @Test void replyBudgetMatchesTheEncodedEmptyReplyExactly() {
        var config = GossipCommandsTest.config(65536, 8, 16);
        // One wire for every local record: whatever it measures once must not depend on the record it first saw.
        var wire = new GossipWire(config);
        var admissions = MembershipTable.Admission.values();
        for (String id : List.of("S", "Self", "x".repeat(128))) {
            for (int length = 0; length <= 600; length += 37) {
                for (int variant = 0; variant < 6; variant++) {
                    for (var state : MemberRecord.State.values()) {
                        var self = new MemberRecord(new MemberKey(id, new UUID(7, 1)), "host.example", 9000, length * 1_000_003L,
                                state, metadata(length, variant));
                        for (var admission : admissions) {
                            int encoded = wire.replyBytes(id, wire.toWire(new GossipWire.ExchangeReply(self, List.of(), admission)));
                            assertEquals(config.maxExchangeBytes() - encoded, wire.replyMemberBudget(self, admission),
                                    "id=" + id.length() + " length=" + length + " variant=" + variant + " " + state + " admission=" + admission);
                        }
                    }
                }
            }
        }
    }

    @Test void requireForwardableDecidesExactlyAsEncodingTheEnvelopesWould() {
        var outcomes = new HashSet<String>();
        for (int batch : List.of(1, 4)) {
            var config = GossipCommandsTest.config(1024, batch, 8);
            var wire = new GossipWire(config);
            var reference = new GossipWire(config);
            // Sweep a peer's size across every limit, beside a small local record.
            var self = record("Self", 1, 3, metadata(4, 1));
            for (int length = 0; length <= 900; length++) {
                for (var peer : List.of(record("Peer", 2, length, metadata(Math.min(length, 700), length % 6)),
                        record("Peer", 2, 7, ascii(length)))) {
                    for (var records : List.of(List.of(peer), List.of(self, peer))) {
                        decidesAsReference(wire, reference, config, self, records, outcomes, "batch=" + batch + " peer=" + peer);
                    }
                }
            }
            // Sweep the local record itself; its limits must follow every change except the heartbeat. Beside the smallest
            // peer, the local record's own page row decides, so it must be budgeted at its widest heartbeat.
            for (int length = 0; length <= 900; length++) {
                for (var changed : List.of(record("Self", 1, length, metadata(Math.min(length, 700), length % 6)),
                        record("Self", 1, length, ascii(length)))) {
                    for (var peer : List.of(record("Peer", 2, 1, metadata(2, 0)), record("Peer", 2, 1, ascii(0)))) {
                        decidesAsReference(wire, reference, config, changed, List.of(changed, peer), outcomes, "batch=" + batch + " self=" + changed);
                    }
                }
            }
        }
        assertEquals(Set.of("OK", SINGLE_SENDER, LOCAL_ALONE, PAGE_ROW), outcomes, "The sweep must cross each limit, and each says which it is");
    }

    @Test void forwardingLimitsFollowTheLocalRecordsStateHostAndPort() {
        var config = GossipCommandsTest.config(1024, 4, 8);
        var wire = new GossipWire(config);
        var reference = new GossipWire(config);
        var peer = record("Peer", 2, 1, ascii(0));
        var outcomes = new HashSet<String>();
        for (int length = 0; length <= 300; length++) {
            var self = record("Self", 1, 3, ascii(length));
            // Each record differs from the one before it in one field besides the heartbeat, so nothing may carry over.
            var left = moved(self, self.host(), self.port(), MemberRecord.State.LEFT);
            var renamed = moved(left, "host-self-renamed.example", left.port(), left.state());
            var renumbered = moved(renamed, renamed.host(), 65535, renamed.state());
            for (var changed : List.of(self, left, renamed, renumbered)) {
                decidesAsReference(wire, reference, config, changed, List.of(changed, peer), outcomes, "self=" + changed);
            }
        }
        assertTrue(outcomes.contains("OK") && outcomes.size() > 1, "The sweep must cross the limit: " + outcomes);
    }

    private static MemberRecord moved(MemberRecord original, String host, int port, MemberRecord.State state) {
        return new MemberRecord(original.key(), host, port, original.heartbeat(), state, original.metadata());
    }

    /** Asserts that the wire decides these records exactly as the reference does, and collects the outcome. */
    private static void decidesAsReference(GossipWire wire, GossipWire reference, GossipConfig config, MemberRecord self,
                                           List<MemberRecord> records, Set<String> outcomes, String context) {
        String expected = outcome(() -> referenceRequireForwardable(reference, config, self, records));
        assertEquals(expected, outcome(() -> wire.requireForwardable(self, records)), context);
        outcomes.add(expected);
    }

    @Test void commandsRefuseToStartExactlyWhenTheLocalRecordOrStatusCannotBeSent() throws Exception {
        var roomy = GossipCommandsTest.config(65536, 4, 8);
        var outcomes = new HashSet<String>();
        for (int bytes : List.of(1024, 2048)) {
            var config = GossipCommandsTest.config(bytes, 4, 8);
            try (var host = new PageHost(config)) {
                for (int length = 0; length <= 900; length++) {
                    var self = record("Self", 1, 3, ascii(length));
                    if (self.serializedBytes() > config.maxExchangeBytes()) { break; } // The table refuses it before any commands exist.
                    var table = new MembershipTable(config, self);
                    // The status this process would report, from commands given room to start.
                    Object status = new GossipCommands(roomy, table, host.metrics, host.transport, host.runtime).execute(Map.of("operation", "status"));
                    String expected = outcome(() -> {
                        host.wire.requireForwardable(self, List.of(self));
                        host.wire.requireReplyFits(self.key().serverId(), status);
                    });
                    assertEquals(expected, outcome(() -> new GossipCommands(config, table, host.metrics, host.transport, host.runtime)),
                            "bytes=" + bytes + " length=" + length);
                    outcomes.add(expected);
                }
            }
        }
        // At the smallest limit status fails first for a small local record; at a larger one small records start.
        assertEquals(Set.of("OK", STATUS_TOO_LARGE, LOCAL_ALONE, PAGE_ROW), outcomes);
    }

    @Test void inboundRequestSizeCheckDecidesExactlyAsEncodingTheRequestWould() {
        var config = GossipCommandsTest.config(1024, 4, 8);
        var wire = new GossipWire(config);
        var decisions = new HashSet<Boolean>();
        for (int length = 0; length <= 800; length++) {
            boolean exact = length % 2 == 0;
            var sender = record("Sender", 1, length, exact ? ascii(length / 2) : metadata(Math.min(length / 2, 300), length % 6));
            var members = List.of(record("Member", 2, 1, exact ? ascii(length / 2) : metadata(Math.min(length / 2, 300), (length + 1) % 6)));
            for (int version = 1; version <= 2; version++) {
                var parameters = wire.toWire(new GossipWire.ExchangeRequest(sender, members));
                boolean tooLarge = wire.requestBytes(parameters).length > config.maxExchangeBytes();
                String result = outcome(() -> wire.decodeRequest(parameters));
                assertEquals(tooLarge ? "Complete canonical request exceeds max.exchange.bytes" : "OK", result, "length=" + length);
                decisions.add(tooLarge);
            }
        }
        assertEquals(Set.of(true, false), decisions, "The sweep must cross the request limit");
    }

    @Test void membersPagesMatchTheRowByRowEncodedPagesExactly() throws Exception {
        var config = GossipCommandsTest.config(4096, 32, 64);
        var records = new ArrayList<MemberRecord>();
        for (int i = 1; i <= 60; i++) { records.add(record("Node-" + i, i, i, metadata((i * 97) % 700, i % 6))); }
        var pages = new PageStats();
        try (var host = new PageHost(config)) {
            for (int limit = 1; limit <= 32; limit++) { host.assertPagesMatch(record("Self", 0, 1, metadata(3, 0)), records, limit, pages); }
        }
        assertTrue(pages.truncatedByBytes, "Some pages must be cut short by the byte limit, not the row limit");
    }

    @Test void membersPagesMatchAtEveryExactByteBoundary() throws Exception {
        // Growing the local record one byte at a time shifts every page by one byte, so each page boundary,
        // with and without a cursor, is landed on exactly at some step.
        var config = GossipCommandsTest.config(4096, 32, 64);
        var records = new ArrayList<MemberRecord>();
        for (int i = 1; i <= 24; i++) { records.add(record("Node-" + i, i, i, ascii(150 + i * 7))); }
        var pages = new PageStats();
        try (var host = new PageHost(config)) {
            for (int length = 0; length <= 600; length++) { host.assertPagesMatch(record("Self", 0, 1, ascii(length)), records, 32, pages); }
        }
        assertTrue(pages.exactFitWithCursor, "Some page must fill the reply budget exactly while carrying a cursor");
    }

    @Test void inboundRepliesAlwaysFitAndSomeFillTheByteLimitExactly() throws Exception {
        // Growing the local record one byte at a time shifts the reply by one byte, so the limit is hit exactly.
        var config = GossipCommandsTest.config(4096, 32, 64);
        var wire = new GossipWire(config);
        var records = new ArrayList<MemberRecord>();
        for (int i = 1; i <= 24; i++) { records.add(record("Node-" + i, i, i, ascii(150 + i * 7))); }
        var sender = record("Sender", 99, 1, ascii(0));
        int exactFits = 0;
        try (var host = new PageHost(config)) {
            for (int length = 0; length <= 600; length++) {
                var self = record("Self", 0, 1, ascii(length));
                var reply = host.exchange(self, records, sender);
                int encoded = wire.replyBytes("Self", reply);
                assertTrue(encoded <= config.maxExchangeBytes(), "length=" + length + " bytes=" + encoded);
                // The reply is exactly its empty envelope plus its members and their commas.
                var members = (List<?>) reply.get("members");
                var admission = MembershipTable.Admission.valueOf((String) reply.get("senderAdmission"));
                int expected = wire.replyBytes("Self", wire.toWire(new GossipWire.ExchangeReply(self, List.of(), admission))) + Math.max(0, members.size() - 1);
                for (var member : members) { expected += GossipWire.bytes(member).length; }
                assertEquals(expected, encoded);
                if (encoded == config.maxExchangeBytes()) { exactFits++; }
            }
        }
        assertTrue(exactFits > 0, "Some reply must fill the byte limit exactly");
    }

    @Test void aRecordTooLargeForTheReplyIsAlsoTooLargeForTheMembersPage() {
        // requireForwardable checks only the page row: it is the stricter, so a record that fits there fits the reply too.
        for (int bytes : List.of(1024, 4096, 32768, 65536)) {
            var config = GossipCommandsTest.config(bytes, 4, 8);
            var wire = new GossipWire(config);
            var self = record("Self", 1, 3, ascii(0));
            var peer = record("Peer", 2, 1, ascii(0));
            var budgetSelf = new MemberRecord(self.key(), self.host(), self.port(), Long.MAX_VALUE, self.state(), self.metadata());
            String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (self.key().bootId() + "/" + config.maxRecords()).getBytes(StandardCharsets.UTF_8));
            var capacity = new GossipWire.Capacity(config.maxMembers(), config.maxRecords(), config.maxRecords(), Long.MAX_VALUE);
            int reply = wire.replyBytes("Self", wire.toWire(new GossipWire.ExchangeReply(budgetSelf, List.of(peer), MembershipTable.Admission.CAPACITY)));
            var row = new MembershipTable.MemberView(peer, MembershipTable.Assessment.CANDIDATE,
                    Long.MAX_VALUE, OptionalLong.of(Long.MAX_VALUE), false, false);
            int page = wire.replyBytes("Self", wire.toWire(new GossipWire.MembersPage(budgetSelf, List.of(row), Optional.of(cursor), capacity)));
            assertTrue(page > reply, "bytes=" + bytes + " page=" + page + " reply=" + reply);
        }
    }

    private static final class PageStats {
        boolean truncatedByBytes;
        boolean exactFitWithCursor;
    }

    /** Commands over a fresh table per call; the transport and runtime are never used by members(). */
    private static final class PageHost implements AutoCloseable {
        private final GossipConfig config;
        private final GossipWire wire;
        private final GossipMetrics metrics = new GossipMetrics();
        private final GossipTransport transport;
        private final GossipRuntime runtime;

        PageHost(GossipConfig config) throws Exception {
            this.config = config;
            wire = new GossipWire(config);
            var unused = new MembershipTable(config, record("Self", 0, 1, ascii(0)));
            transport = new GossipTransport(config, unused, metrics);
            runtime = new GossipRuntime(config, unused, transport, metrics);
        }

        private MembershipTable table(MemberRecord self, List<MemberRecord> records) {
            var table = new MembershipTable(config, self, new AtomicLong(1_000_000_000L)::get);
            for (int start = 0; start < records.size(); start += 31) { table.merge(records.subList(start, Math.min(start + 31, records.size()))); }
            return table;
        }

        /** One inbound exchange from {@code sender}, carrying no members, answered by a fresh table. */
        Map<?, ?> exchange(MemberRecord self, List<MemberRecord> records, MemberRecord sender) throws Exception {
            var commands = new GossipCommands(config, table(self, records), metrics, transport, runtime);
            return (Map<?, ?>) commands.execute(wire.toWire(new GossipWire.ExchangeRequest(sender, List.of())));
        }

        void assertPagesMatch(MemberRecord self, List<MemberRecord> records, int limit, PageStats pages) throws Exception {
            var table = table(self, records);
            var commands = new GossipCommands(config, table, metrics, transport, runtime);
            var snapshot = table.snapshot();
            int position = 0;
            String cursor = null;
            do {
                var parameters = new HashMap<String, Object>(Map.of("operation", "members", "limit", limit));
                if (cursor != null) { parameters.put("cursor", cursor); }
                var actual = (Map<?, ?>) commands.execute(parameters);
                assertEquals(referencePage(wire, snapshot, limit, position), actual, "self=" + self + " limit=" + limit + " position=" + position);
                int rows = ((List<?>) actual.get("members")).size();
                cursor = (String) actual.get("nextCursor");
                pages.truncatedByBytes |= rows < limit && cursor != null;
                pages.exactFitWithCursor |= cursor != null && wire.replyBytes(self.key().serverId(), actual) == config.maxExchangeBytes();
                position += rows;
            } while (cursor != null);
            assertEquals(snapshot.members().size(), position);
        }

        @Override public void close() throws Exception { runtime.close(); }
    }

    private static final String SINGLE_SENDER = "Single sender exceeds the complete request budget";
    private static final String STATUS_TOO_LARGE = "Complete reply exceeds max.exchange.bytes";
    private static final String LOCAL_ALONE = "Complete reply exceeds max.exchange.bytes: the local record alone does not fit";
    private static final String PAGE_ROW = "Complete reply exceeds max.exchange.bytes: a record does not fit as a members-page row";

    /**
     * The original algorithm: encode the complete reply envelopes for every record. A record beside the local sender
     * in a reply is not checked: it costs less than the same record as a members-page row, which
     * {@link #aRecordTooLargeForTheReplyIsAlsoTooLargeForTheMembersPage} shows.
     */
    private static void referenceRequireForwardable(GossipWire wire, GossipConfig config, MemberRecord self, List<MemberRecord> records) {
        var budgetSelf = new MemberRecord(self.key(), self.host(), self.port(), Long.MAX_VALUE, self.state(), self.metadata());
        fits(wire, self, wire.toWire(new GossipWire.ExchangeReply(budgetSelf, List.of(), MembershipTable.Admission.CAPACITY)), LOCAL_ALONE);
        for (MemberRecord record : records) {
            if (!record.key().equals(self.key()) && wire.requestBytes(wire.toWire(new GossipWire.ExchangeRequest(record, List.of()))).length > config.maxExchangeBytes()) {
                throw new MembershipException(MembershipException.Reason.INVALID_RECORD, SINGLE_SENDER);
            }
            var worst = new MembershipTable.MemberView(record.key().equals(self.key()) ? budgetSelf : record,
                    MembershipTable.Assessment.CANDIDATE, Long.MAX_VALUE, OptionalLong.of(Long.MAX_VALUE), false, false);
            String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (self.key().bootId() + "/" + config.maxRecords()).getBytes(StandardCharsets.UTF_8));
            fits(wire, self, wire.toWire(new GossipWire.MembersPage(budgetSelf, List.of(worst), Optional.of(cursor),
                    new GossipWire.Capacity(config.maxMembers(), config.maxRecords(), config.maxRecords(), Long.MAX_VALUE))), PAGE_ROW);
        }
    }

    private static void fits(GossipWire wire, MemberRecord self, Object result, String failure) {
        if (!wire.replyFits(self.key().serverId(), result)) { throw new MembershipException(MembershipException.Reason.INVALID_RECORD, failure); }
    }

    /** The original members() loop: re-encode the growing page for every row. */
    private static Map<String, Object> referencePage(GossipWire wire, MembershipTable.Snapshot snapshot, int limit, int position) {
        var capacity = GossipWire.Capacity.of(snapshot);
        int size = snapshot.members().size();
        var selected = new ArrayList<MembershipTable.MemberView>();
        while (position < size && selected.size() < limit) {
            selected.add(snapshot.members().get(position));
            int next = position + 1;
            var cursor = next < size ? Optional.of(cursor(snapshot, next)) : Optional.<String>empty();
            if (!wire.replyFits(snapshot.self().key().serverId(),
                    wire.toWire(new GossipWire.MembersPage(snapshot.self(), selected, cursor, capacity)))) {
                selected.removeLast();
                assertFalse(selected.isEmpty(), "Every row in this table fits on its own");
                break;
            }
            position = next;
        }
        var cursor = position < size ? Optional.of(cursor(snapshot, position)) : Optional.<String>empty();
        return wire.toWire(new GossipWire.MembersPage(snapshot.self(), selected, cursor, capacity));
    }

    private static String cursor(MembershipTable.Snapshot snapshot, int next) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((snapshot.self().key().bootId() + "/" + next).getBytes(StandardCharsets.UTF_8));
    }
}
