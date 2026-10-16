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

import dev.mars.protocol.CommandReply;
import dev.mars.protocol.CommandRequest;
import dev.mars.protocol.Json;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static dev.mars.plugins.gossip.MembershipException.Reason.INVALID_RECORD;

/** Gossip versioning and complete canonical-envelope accounting, independent of host version 1. */
public final class GossipWire {
    private static final UUID BUDGET_ID = new UUID(0, 0);
    private final GossipConfig config;
    // Encoded sizes are additive: Json.write emits a record identically standalone or nested, so an envelope is a
    // fixed overhead plus its records plus one comma between neighbours. Each overhead is measured once.
    private volatile int requestOverhead = -1;
    private volatile int worstRowExtra = -1;
    private volatile ForwardingLimits forwarding;

    public record Exchange(MemberRecord sender, List<MemberRecord> members, int gossipVersion,
                           MembershipTable.Admission senderAdmission) {
        public Exchange(MemberRecord sender, List<MemberRecord> members) { this(sender, members, 1, null); }
        public Exchange { members = List.copyOf(members); }
        public List<MemberRecord> records() { return Stream.concat(Stream.of(sender), members.stream()).toList(); }
    }

    public GossipWire(GossipConfig config) { this.config = config; }

    public Exchange decode(Object value, boolean request) {
        Set<String> fields = request ? Set.of("operation", "gossipVersion", "swarmId", "sender", "members")
                : Set.of("gossipVersion", "swarmId", "sender", "members");
        if (!(value instanceof Map<?, ?> map)) { throw invalid("Invalid exchange fields"); }
        int version = exactInteger(map.get("gossipVersion"), "gossipVersion", 1, 2);
        if (!request && version == 2) { fields = Set.of("gossipVersion", "swarmId", "sender", "members", "senderAdmission"); }
        if (!map.keySet().equals(fields)) { throw invalid("Invalid exchange fields"); }
        if (request && !"exchange".equals(map.get("operation"))) { throw invalid("operation must be exchange"); }
        if (!config.swarmId().equals(map.get("swarmId"))) { throw invalid("swarmId does not match this swarm"); }
        if (!(map.get("members") instanceof List<?> members) || members.size() >= config.maxBatchMembers()) {
            throw invalid("Sender and members exceed max.batch.members");
        }
        MemberRecord sender = MemberRecord.fromWire(map.get("sender"));
        var keys = new HashSet<MemberKey>();
        keys.add(sender.key());
        var decoded = new ArrayList<MemberRecord>();
        for (Object member : members) {
            MemberRecord record = MemberRecord.fromWire(member);
            if (!keys.add(record.key())) { throw invalid("Duplicate member key in exchange"); }
            decoded.add(record);
        }
        MembershipTable.Admission admission = null;
        if (!request && version == 2) {
            if (!(map.get("senderAdmission") instanceof String text)) { throw invalid("Invalid senderAdmission"); }
            try { admission = MembershipTable.Admission.valueOf(text); }
            catch (IllegalArgumentException e) { throw invalid("Invalid senderAdmission"); }
        }
        var exchange = new Exchange(sender, decoded, version, admission);
        if (request && requestSize(sender, decoded) > config.maxExchangeBytes()) {
            throw invalid("Complete canonical request exceeds max.exchange.bytes");
        }
        return exchange;
    }

    public Map<String, Object> parameters(MemberRecord self, List<MemberRecord> members) {
        return parameters(self, members, 1);
    }

    public Map<String, Object> parameters(MemberRecord self, List<MemberRecord> members, int version) {
        var result = new LinkedHashMap<>(result(self, members));
        result.put("gossipVersion", exactInteger(version, "gossipVersion", 1, 2));
        result.put("operation", "exchange");
        return Map.copyOf(result);
    }

    public Map<String, Object> result(MemberRecord self, List<MemberRecord> members) {
        return Map.of("gossipVersion", 1, "swarmId", config.swarmId(), "sender", self.toWire(),
                "members", members.stream().map(MemberRecord::toWire).toList());
    }

    public Map<String, Object> result(MemberRecord self, List<MemberRecord> members, int version, MembershipTable.Admission admission) {
        exactInteger(version, "gossipVersion", 1, 2);
        if (version == 1) { return result(self, members); }
        var result = new LinkedHashMap<>(result(self, members));
        result.put("gossipVersion", version);
        result.put("senderAdmission", java.util.Objects.requireNonNull(admission).name());
        return Map.copyOf(result);
    }

    public byte[] requestBytes(Map<String, Object> parameters) {
        return bytes(new CommandRequest(1, BUDGET_ID, "gossip", parameters));
    }

    /** Bytes of the canonical request for this sender and members, computed without encoding the envelope. */
    int requestSize(MemberRecord sender, List<MemberRecord> members) {
        long size = requestOverhead(sender) + (long) sender.serializedBytes();
        for (int i = 0; i < members.size(); i++) { size += members.get(i).serializedBytes() + (i == 0 ? 0 : 1); }
        return (int) Math.min(Integer.MAX_VALUE, size);
    }

    /** Request bytes besides the sender, with an empty members array; gossipVersion 1 and 2 are equally wide. */
    private int requestOverhead(MemberRecord sample) {
        int overhead = requestOverhead;
        if (overhead < 0) {
            overhead = requestBytes(parameters(sample, List.of())).length - sample.serializedBytes();
            requestOverhead = overhead;
        }
        return overhead;
    }

    /** Exact available UTF-8 bytes; the empty members array already accounts for brackets. */
    int requestMemberBudget(MemberRecord self) {
        return config.maxExchangeBytes() - requestSize(self, List.of());
    }

    /**
     * Bytes left for members beside the complete empty reply with this admission. Reply sizes are additive, so a
     * batch within this budget always fits. The largest legal connection ID is reserved because the handler cannot
     * see the actual one.
     */
    int replyMemberBudget(MemberRecord self, int version, MembershipTable.Admission admission) {
        return config.maxExchangeBytes() - replyBytes(self.key().serverId(), result(self, List.of(), version, admission));
    }

    /** UUIDs have fixed serialized width; Long.MAX_VALUE reserves the largest legal connection ID. */
    int replyBytes(String serverId, Object result) {
        return bytes(new CommandReply(1, BUDGET_ID, serverId, Long.MAX_VALUE, "OK", result, null)).length;
    }

    public boolean replyFits(String serverId, Object result) { return replyBytes(serverId, result) <= config.maxExchangeBytes(); }

    public void requireReplyFits(String serverId, Object result) {
        if (!replyFits(serverId, result)) { throw invalid("Complete reply exceeds max.exchange.bytes"); }
    }

    /**
     * Check new records before merging, so a single unforwardable record cannot break later replies. Each record
     * must fit as a request sender, beside the local sender in a reply, and as a worst-case members-page row.
     */
    public void requireForwardable(MemberRecord self, List<MemberRecord> records) {
        int limit = config.maxExchangeBytes();
        var limits = forwardingLimits(self);
        if (limits.emptyReply() > limit) { throw invalid("Complete reply exceeds max.exchange.bytes"); }
        for (MemberRecord record : records) {
            boolean local = record.key().equals(self.key());
            if (!local) {
                if (requestSize(record, List.of()) > limit) { throw invalid("Single sender exceeds the complete request budget"); }
                if (config.maxBatchMembers() > 1 && limits.emptyReply() + record.serializedBytes() > limit) {
                    throw invalid("Complete reply exceeds max.exchange.bytes");
                }
            }
            int row = (local ? limits.budgetSelfBytes() : record.serializedBytes()) + worstRowExtra(record);
            if (limits.emptyPage() + row > limit) { throw invalid("Complete reply exceeds max.exchange.bytes"); }
        }
    }

    /** Bytes a members-page row adds to its record: the local assessment fields at their widest values. */
    private int worstRowExtra(MemberRecord sample) {
        int extra = worstRowExtra;
        if (extra < 0) {
            extra = bytes(memberView(worstView(sample))).length - sample.serializedBytes();
            worstRowExtra = extra;
        }
        return extra;
    }

    private static MembershipTable.MemberView worstView(MemberRecord record) {
        return new MembershipTable.MemberView(record, MembershipTable.Assessment.CANDIDATE, Long.MAX_VALUE, Long.MAX_VALUE, false, false);
    }

    /** Empty reply and members-page sizes for the local record, recomputed only when more than its heartbeat changes. */
    private ForwardingLimits forwardingLimits(MemberRecord self) {
        var limits = forwarding;
        if (limits != null && limits.matches(self)) { return limits; }
        // The widest heartbeat stands in for every future local heartbeat.
        var budgetSelf = self.with(Long.MAX_VALUE, self.state());
        String serverId = self.key().serverId();
        String cursor = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                (self.key().bootId() + "/" + config.maxRecords()).getBytes(StandardCharsets.UTF_8));
        Map<String, Object> capacity = Map.of("activeMembers", config.maxMembers(), "retainedIdentities", config.maxRecords(),
                "tombstones", config.maxRecords(), "admissionsSkipped", Long.MAX_VALUE);
        limits = new ForwardingLimits(self, budgetSelf.serializedBytes(),
                replyBytes(serverId, result(budgetSelf, List.of(), 2, MembershipTable.Admission.CAPACITY)),
                replyBytes(serverId, page(budgetSelf, List.of(), cursor, capacity)));
        forwarding = limits;
        return limits;
    }

    private record ForwardingLimits(MemberRecord self, int budgetSelfBytes, int emptyReply, int emptyPage) {
        boolean matches(MemberRecord other) {
            return self.key().equals(other.key()) && self.host().equals(other.host()) && self.port() == other.port()
                    && self.state() == other.state() && self.metadata().equals(other.metadata());
        }
    }

    Map<String, Object> page(MemberRecord self, List<Map<String, Object>> members, String cursor, Map<String, Object> capacity) {
        var result = new LinkedHashMap<String, Object>();
        result.put("swarmId", config.swarmId());
        result.put("self", self.toWire());
        result.put("members", List.copyOf(members));
        result.put("nextCursor", cursor);
        result.put("capacity", capacity);
        return result;
    }

    static Map<String, Object> memberView(MembershipTable.MemberView member) {
        var result = new LinkedHashMap<>(member.record().toWire());
        result.put("assessment", member.assessment().name());
        result.put("advanceAgeMillis", member.advanceAgeNanos() / 1_000_000);
        result.put("directSuccessAgeMillis", member.directSuccessAgeNanos() == null ? null : member.directSuccessAgeNanos() / 1_000_000);
        result.put("endpointVerified", member.endpointVerified());
        result.put("identityConflict", member.identityConflict());
        return result;
    }

    static int exactInteger(Object value, String field, int min, int max) { return (int) exactLong(value, field, min, max); }

    /**
     * An exact JSON integer in [min, max]: fractions, strings and other coercions are rejected. The range message is
     * also what deployed version-1 peers send for an unsupported gossipVersion, so its wording must not change.
     */
    static long exactLong(Object value, String field, long min, long max) {
        long number;
        if (value instanceof BigInteger integer) {
            try { number = integer.longValueExact(); }
            catch (ArithmeticException e) { throw invalid(field + " is outside the supported integer range"); }
        } else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            number = ((Number) value).longValue();
        } else { throw invalid(field + " must be an integer"); }
        if (number < min || number > max) { throw invalid(field + " must be between " + min + " and " + max); }
        return number;
    }

    static byte[] bytes(Object value) {
        try { return Json.write(value).getBytes(StandardCharsets.UTF_8); }
        catch (IOException e) { throw invalid("Gossip data cannot be serialized"); }
    }

    static MembershipException invalid(String message) { return new MembershipException(INVALID_RECORD, message); }
}
