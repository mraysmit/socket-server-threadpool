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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.concurrent.TimeUnit;

import static dev.mars.plugins.gossip.MembershipException.invalidRecord;

/** Gossip versioning and complete canonical-envelope accounting, independent of host version 1. */
public final class GossipWire {
    private static final UUID BUDGET_ID = new UUID(0, 0);
    private static final Set<String> REQUEST_FIELDS = Set.of("operation", "gossipVersion", "swarmId", "sender", "members");
    private static final Set<String> REPLY_FIELDS = Set.of("gossipVersion", "swarmId", "sender", "members");
    private static final Set<String> REPLY_FIELDS_V2 = Set.of("gossipVersion", "swarmId", "sender", "members", "senderAdmission");
    private static final String REPLY_TOO_LARGE = "Complete reply exceeds max.exchange.bytes";
    private final GossipConfig config;
    // Encoded sizes are additive: Json.write emits a record identically standalone or nested, so an envelope is a
    // fixed overhead plus its records plus one comma between neighbours. Every overhead depends only on the config,
    // so each is measured once, around a sample record whose own bytes are then taken away.
    /** A request besides its sender, with an empty members array; gossipVersion 1 and 2 are equally wide. */
    private final int requestOverhead;
    /** What a members-page row adds to its record: the local assessment fields at their widest values. */
    private final int rowExtra;
    /** An empty version-1 reply besides its sender and the server ID. */
    private final int replyOverheadV1;
    /** An empty version-2 reply besides its sender and the server ID, by the admission it reports. */
    private final Map<MembershipTable.Admission, Integer> replyOverheadsV2;
    /** An empty members page, with the widest cursor and capacity, besides its local record and the server ID. */
    private final int pageOverhead;

    public record Exchange(MemberRecord sender, List<MemberRecord> members, int gossipVersion,
                           MembershipTable.Admission senderAdmission) {
        public Exchange { members = List.copyOf(members); }
        public List<MemberRecord> records() { return Stream.concat(Stream.of(sender), members.stream()).toList(); }
    }

    public GossipWire(GossipConfig config) {
        this.config = config;
        var sample = new MemberRecord(new MemberKey("Sample", new UUID(0, 0)), "127.0.0.1", 1, 0, MemberRecord.State.ALIVE, Map.of());
        String serverId = sample.key().serverId();
        int besideSample = sample.serializedBytes() + serverId.length();
        requestOverhead = requestBytes(parameters(sample, List.of())).length - sample.serializedBytes();
        rowExtra = bytes(memberView(widestView(sample))).length - sample.serializedBytes();
        replyOverheadV1 = replyBytes(serverId, result(sample, List.of())) - besideSample;
        var overheads = new EnumMap<MembershipTable.Admission, Integer>(MembershipTable.Admission.class);
        for (var admission : MembershipTable.Admission.values()) {
            overheads.put(admission, replyBytes(serverId, result(sample, List.of(), 2, admission)) - besideSample);
        }
        replyOverheadsV2 = overheads;
        pageOverhead = replyBytes(serverId, page(sample, List.of(), widestCursor(sample), widestCapacity())) - besideSample;
    }

    public Exchange decode(Object value, boolean request) {
        if (!(value instanceof Map<?, ?> map)) { throw invalidRecord("Invalid exchange fields"); }
        int version = gossipVersion(map.get("gossipVersion"));
        var fields = request ? REQUEST_FIELDS : version == 2 ? REPLY_FIELDS_V2 : REPLY_FIELDS;
        if (!map.keySet().equals(fields)) { throw invalidRecord("Invalid exchange fields"); }
        if (request && !"exchange".equals(map.get("operation"))) { throw invalidRecord("operation must be exchange"); }
        if (!config.swarmId().equals(map.get("swarmId"))) { throw invalidRecord(GossipFailure.SWARM, "swarmId does not match this swarm"); }
        if (!(map.get("members") instanceof List<?> members) || members.size() >= config.maxBatchMembers()) {
            throw invalidRecord("Sender and members exceed max.batch.members");
        }
        MemberRecord sender = MemberRecord.fromWire(map.get("sender"));
        // Duplicate keys are rejected once, by the table, which checks every batch it is given before merging any.
        var decoded = new ArrayList<MemberRecord>(members.size());
        for (Object member : members) { decoded.add(MemberRecord.fromWire(member)); }
        MembershipTable.Admission admission = null;
        if (!request && version == 2) {
            if (!(map.get("senderAdmission") instanceof String text)) { throw invalidRecord("Invalid senderAdmission"); }
            try { admission = MembershipTable.Admission.valueOf(text); }
            catch (IllegalArgumentException _) { throw invalidRecord("Invalid senderAdmission"); }
        }
        var exchange = new Exchange(sender, decoded, version, admission);
        if (request && requestSize(sender, decoded) > config.maxExchangeBytes()) {
            throw invalidRecord("Complete canonical request exceeds max.exchange.bytes");
        }
        return exchange;
    }

    public Map<String, Object> parameters(MemberRecord self, List<MemberRecord> members) {
        return parameters(self, members, 1);
    }

    public Map<String, Object> parameters(MemberRecord self, List<MemberRecord> members, int version) {
        return Map.of("operation", "exchange", "gossipVersion", gossipVersion(version),
                "swarmId", config.swarmId(), "sender", self.toWire(), "members", wireRecords(members));
    }

    public Map<String, Object> result(MemberRecord self, List<MemberRecord> members) {
        return Map.of("gossipVersion", 1, "swarmId", config.swarmId(), "sender", self.toWire(), "members", wireRecords(members));
    }

    public Map<String, Object> result(MemberRecord self, List<MemberRecord> members, int version, MembershipTable.Admission admission) {
        if (gossipVersion(version) == 1) { return result(self, members); }
        return Map.of("gossipVersion", 2, "swarmId", config.swarmId(), "sender", self.toWire(), "members", wireRecords(members),
                "senderAdmission", java.util.Objects.requireNonNull(admission).name());
    }

    private static List<Map<String, Object>> wireRecords(List<MemberRecord> records) {
        return records.stream().map(MemberRecord::toWire).toList();
    }

    public byte[] requestBytes(Map<String, Object> parameters) {
        return bytes(new CommandRequest(1, BUDGET_ID, "gossip", parameters));
    }

    /** Bytes of the canonical request for this sender and members, computed without encoding the envelope. */
    int requestSize(MemberRecord sender, List<MemberRecord> members) {
        long size = requestOverhead + (long) sender.serializedBytes();
        for (int i = 0; i < members.size(); i++) { size += members.get(i).serializedBytes() + (i == 0 ? 0 : 1); }
        return (int) Math.min(Integer.MAX_VALUE, size);
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
        int overhead = gossipVersion(version) == 1 ? replyOverheadV1 : replyOverheadsV2.get(admission);
        return config.maxExchangeBytes() - overhead - self.serializedBytes() - self.key().serverId().length();
    }

    /** UUIDs have fixed serialized width; Long.MAX_VALUE reserves the largest legal connection ID. */
    int replyBytes(String serverId, Object result) {
        return bytes(new CommandReply(1, BUDGET_ID, serverId, Long.MAX_VALUE, "OK", result, null)).length;
    }

    public boolean replyFits(String serverId, Object result) { return replyBytes(serverId, result) <= config.maxExchangeBytes(); }

    public void requireReplyFits(String serverId, Object result) {
        if (!replyFits(serverId, result)) { throw invalidRecord(REPLY_TOO_LARGE); }
    }

    /**
     * Check new records before merging, so a single unforwardable record cannot break later replies. Each record
     * must fit as a request sender, beside the local sender in a reply, and as a worst-case members-page row. A row
     * costs more than the same record beside the sender, so the row check covers the reply too.
     */
    public void requireForwardable(MemberRecord self, List<MemberRecord> records) {
        int limit = config.maxExchangeBytes();
        // The local heartbeat only grows, so the local record is budgeted at its widest.
        int widestSelf = self.with(Long.MAX_VALUE, self.state()).serializedBytes();
        int besideSelf = widestSelf + self.key().serverId().length();
        // No admission is wider than CAPACITY, so this is the largest empty reply the local process can send.
        if (replyOverheadsV2.get(MembershipTable.Admission.CAPACITY) + besideSelf > limit) {
            throw invalidRecord(REPLY_TOO_LARGE + ": the local record alone does not fit");
        }
        int emptyPage = pageOverhead + besideSelf;
        for (MemberRecord member : records) {
            boolean local = member.key().equals(self.key());
            if (!local && requestSize(member, List.of()) > limit) {
                throw invalidRecord("Single sender exceeds the complete request budget");
            }
            int row = (local ? widestSelf : member.serializedBytes()) + rowExtra;
            if (emptyPage + row > limit) { throw invalidRecord(REPLY_TOO_LARGE + ": a record does not fit as a members-page row"); }
        }
    }

    private static MembershipTable.MemberView widestView(MemberRecord member) {
        return new MembershipTable.MemberView(member, MembershipTable.Assessment.CANDIDATE, Long.MAX_VALUE, OptionalLong.of(Long.MAX_VALUE), false, false);
    }

    /** A members cursor at its widest: a boot ID is fixed-width, and the offset is at most the record limit. */
    private String widestCursor(MemberRecord self) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (self.key().bootId() + "/" + config.maxRecords()).getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, Object> widestCapacity() {
        return Map.of("activeMembers", config.maxMembers(), "retainedIdentities", config.maxRecords(),
                "tombstones", config.maxRecords(), "admissionsSkipped", Long.MAX_VALUE);
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
        result.put("advanceAgeMillis", TimeUnit.NANOSECONDS.toMillis(member.advanceAgeNanos()));
        var directSuccessAge = member.directSuccessAgeNanos();
        // None yet is written as null, which the members page has always reported.
        result.put("directSuccessAgeMillis", directSuccessAge.isPresent() ? TimeUnit.NANOSECONDS.toMillis(directSuccessAge.getAsLong()) : null);
        result.put("endpointVerified", member.endpointVerified());
        result.put("identityConflict", member.identityConflict());
        return result;
    }

    static byte[] bytes(Object value) {
        try { return Json.write(value).getBytes(StandardCharsets.UTF_8); }
        catch (IOException _) { throw invalidRecord("Gossip data cannot be serialized"); }
    }

    /** A gossipVersion of 1 or 2. Anything else is a version failure, in the wording deployed version-1 peers send. */
    private static int gossipVersion(Object value) {
        try { return GossipValidation.exactInteger(value, "gossipVersion", 1, 2); }
        catch (MembershipException e) { throw invalidRecord(GossipFailure.VERSION, e.getMessage()); }
    }
}
