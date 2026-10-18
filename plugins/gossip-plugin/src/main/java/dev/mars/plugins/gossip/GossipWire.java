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
import dev.mars.protocol.CommandProtocol;
import dev.mars.protocol.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.concurrent.TimeUnit;

import static dev.mars.plugins.gossip.GossipFields.ACTIVE_MEMBERS;
import static dev.mars.plugins.gossip.GossipFields.ADMISSIONS_SKIPPED;
import static dev.mars.plugins.gossip.GossipFields.CAPACITY;
import static dev.mars.plugins.gossip.GossipFields.EXCHANGE_OPERATION;
import static dev.mars.plugins.gossip.GossipFields.GOSSIP_VERSION;
import static dev.mars.plugins.gossip.GossipFields.MEMBERS;
import static dev.mars.plugins.gossip.GossipFields.NEXT_CURSOR;
import static dev.mars.plugins.gossip.GossipFields.OPERATION;
import static dev.mars.plugins.gossip.GossipFields.RETAINED_IDENTITIES;
import static dev.mars.plugins.gossip.GossipFields.SELF;
import static dev.mars.plugins.gossip.GossipFields.SENDER;
import static dev.mars.plugins.gossip.GossipFields.SENDER_ADMISSION;
import static dev.mars.plugins.gossip.GossipFields.SWARM_ID;
import static dev.mars.plugins.gossip.GossipFields.TOMBSTONES;
import static dev.mars.plugins.gossip.MembershipException.invalidRecord;

/**
 * The gossip messages, their JSON and the exact size of each encoded envelope. Messages are typed records inside the
 * plugin; they become maps only here, where they are handed to the host or arrive from it.
 */
public final class GossipWire {
    /** The gossip protocol version. Every exchange carries it, and one in any other version is rejected. */
    static final int VERSION = 2;
    private static final UUID BUDGET_ID = new UUID(0, 0);
    private static final Set<String> REQUEST_FIELDS = Set.of(OPERATION, GOSSIP_VERSION, SWARM_ID, SENDER, MEMBERS);
    private static final Set<String> REPLY_FIELDS = Set.of(GOSSIP_VERSION, SWARM_ID, SENDER, MEMBERS,
            SENDER_ADMISSION);
    private static final String REPLY_TOO_LARGE = "Complete reply exceeds max.exchange.bytes";
    private final GossipConfig config;
    // Encoded sizes are additive: Json.write emits a record identically standalone or nested, so an envelope is a
    // fixed overhead plus its records plus one comma between neighbours. Every overhead depends only on the config,
    // so each is measured once, around a sample record whose own bytes are then taken away.
    /** A request besides its sender, with an empty members array. */
    private final int requestOverhead;
    /** What a members-page row adds to its record: the local assessment fields at their widest values. */
    private final int rowExtra;
    /** An empty reply besides its sender and the server ID, by the admission it reports. */
    private final Map<MembershipTable.Admission, Integer> replyOverheads;
    /** An empty members page, with the widest cursor and capacity, besides its local record and the server ID. */
    private final int pageOverhead;

    /** An exchange request: the sender's own record and the members it forwards. */
    public record ExchangeRequest(MemberRecord sender, List<MemberRecord> members) {
        public ExchangeRequest { members = List.copyOf(members); }
        public List<MemberRecord> records() { return Stream.concat(Stream.of(sender), members.stream()).toList(); }
    }

    /** An exchange reply, which also says how the responder admitted the requesting process's record. */
    public record ExchangeReply(MemberRecord sender, List<MemberRecord> members,
                                MembershipTable.Admission senderAdmission) {
        public ExchangeReply {
            members = List.copyOf(members);
            Objects.requireNonNull(senderAdmission);
        }
        public List<MemberRecord> records() { return Stream.concat(Stream.of(sender), members.stream()).toList(); }
    }

    /** One page of the members inspection: its rows, the cursor to the next page if there is one, and capacity. */
    public record MembersPage(MemberRecord self, List<MembershipTable.MemberView> members, Optional<String> nextCursor,
                              Capacity capacity) {
        public MembersPage { members = List.copyOf(members); }
    }

    /** How full the table is, as the members page and status report it. */
    public record Capacity(int activeMembers, int retainedIdentities, int tombstones, long admissionsSkipped) {
        static Capacity of(MembershipTable.Snapshot snapshot) {
            return new Capacity(snapshot.activeMembers(), snapshot.retainedIdentities(), snapshot.tombstones(),
                    snapshot.admissionsSkipped());
        }
    }

    public GossipWire(GossipConfig config) {
        this.config = config;
        var sample = new MemberRecord(new MemberKey("Sample", new UUID(0, 0)), "127.0.0.1", 1, 0,
                MemberRecord.State.ALIVE, Map.of());
        String serverId = sample.key().serverId();
        int besideSample = sample.serializedBytes() + serverId.length();
        requestOverhead = requestBytes(toWire(new ExchangeRequest(sample, List.of()))).length
                - sample.serializedBytes();
        rowExtra = bytes(memberView(widestView(sample))).length - sample.serializedBytes();
        var overheads = new EnumMap<MembershipTable.Admission, Integer>(MembershipTable.Admission.class);
        for (var admission : MembershipTable.Admission.values()) {
            var reply = new ExchangeReply(sample, List.of(), admission);
            overheads.put(admission, replyBytes(serverId, toWire(reply)) - besideSample);
        }
        replyOverheads = overheads;
        var page = new MembersPage(sample, List.of(), Optional.of(widestCursor(sample)), widestCapacity());
        pageOverhead = replyBytes(serverId, toWire(page)) - besideSample;
    }

    /** Checks a request completely before returning it, so nothing from a bad one is ever merged. */
    public ExchangeRequest decodeRequest(Object value) {
        var map = fields(value, REQUEST_FIELDS);
        if (!EXCHANGE_OPERATION.equals(map.get(OPERATION))) { throw invalidRecord("operation must be exchange"); }
        var request = senderAndMembers(map);
        if (requestSize(request.sender(), request.members()) > config.maxExchangeBytes()) {
            throw invalidRecord("Complete canonical request exceeds max.exchange.bytes");
        }
        return request;
    }

    /** Checks a reply completely before returning it, so nothing from a bad one is ever merged. */
    public ExchangeReply decodeReply(Object value) {
        var map = fields(value, REPLY_FIELDS);
        var records = senderAndMembers(map);
        return new ExchangeReply(records.sender(), records.members(), senderAdmission(map.get(SENDER_ADMISSION)));
    }

    /** An exchange in this version, with exactly the fields expected of it. */
    private static Map<?, ?> fields(Object value, Set<String> expected) {
        if (!(value instanceof Map<?, ?> map)) { throw invalidRecord("Invalid exchange fields"); }
        requireVersion(map.get(GOSSIP_VERSION));
        if (!map.keySet().equals(expected)) { throw invalidRecord("Invalid exchange fields"); }
        return map;
    }

    /** The sender and members that a request and a reply both carry, once they are from this swarm and valid. */
    private ExchangeRequest senderAndMembers(Map<?, ?> map) {
        if (!config.swarmId().equals(map.get(SWARM_ID))) {
            throw invalidRecord(GossipFailure.SWARM, "swarmId does not match this swarm");
        }
        var members = withinBatch(map.get(MEMBERS));
        MemberRecord sender = MemberRecord.fromWire(map.get(SENDER));
        // Duplicate keys are rejected once, by the table, which checks every batch it is given before merging any.
        return new ExchangeRequest(sender, members.stream().map(MemberRecord::fromWire).toList());
    }

    /**
     * The members, refused before any is decoded if they and the sender would pass the batch limit; this bounds the
     * work.
     */
    private List<?> withinBatch(Object value) {
        if (!(value instanceof List<?> members) || members.size() >= config.maxBatchMembers()) {
            throw invalidRecord("Sender and members exceed max.batch.members");
        }
        return members;
    }

    private static MembershipTable.Admission senderAdmission(Object value) {
        if (!(value instanceof String text)) { throw invalidRecord("Invalid senderAdmission"); }
        try { return MembershipTable.Admission.valueOf(text); }
        catch (IllegalArgumentException _) { throw invalidRecord("Invalid senderAdmission"); }
    }

    /** The request's parameters, as the host sends them. */
    public Map<String, Object> toWire(ExchangeRequest request) {
        return Map.of(OPERATION, EXCHANGE_OPERATION, GOSSIP_VERSION, VERSION, SWARM_ID, config.swarmId(),
                SENDER, request.sender().toWire(), MEMBERS, wireRecords(request.members()));
    }

    /** The reply's result, as the host sends it back. */
    public Map<String, Object> toWire(ExchangeReply reply) {
        return Map.of(GOSSIP_VERSION, VERSION, SWARM_ID, config.swarmId(), SENDER, reply.sender().toWire(),
                MEMBERS, wireRecords(reply.members()), SENDER_ADMISSION, reply.senderAdmission().name());
    }

    /** The page's result, as the host sends it back. A last page has a null cursor, as the page has always had. */
    public Map<String, Object> toWire(MembersPage page) {
        var result = new LinkedHashMap<String, Object>();
        result.put(SWARM_ID, config.swarmId());
        result.put(SELF, page.self().toWire());
        result.put(MEMBERS, page.members().stream().map(GossipWire::memberView).toList());
        result.put(NEXT_CURSOR, page.nextCursor().orElse(null));
        result.put(CAPACITY, toWire(page.capacity()));
        return result;
    }

    static Map<String, Object> toWire(Capacity capacity) {
        return Map.of(ACTIVE_MEMBERS, capacity.activeMembers(), RETAINED_IDENTITIES, capacity.retainedIdentities(),
                TOMBSTONES, capacity.tombstones(), ADMISSIONS_SKIPPED, capacity.admissionsSkipped());
    }

    private static List<Map<String, Object>> wireRecords(List<MemberRecord> records) {
        return records.stream().map(MemberRecord::toWire).toList();
    }

    /**
     * The request envelope. Its {@code connection} field asks the host to keep the connection open after replying, so
     * the transport can send the next exchange to the same peer over it instead of opening a new connection.
     */
    Map<String, Object> envelope(UUID messageId, Map<String, Object> parameters) {
        return envelope(messageId, parameters, true);
    }

    /** Without the field, the host closes the connection after its reply, as an older host always does. */
    Map<String, Object> envelope(UUID messageId, Map<String, Object> parameters, boolean keepAlive) {
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("version", 1);
        envelope.put("messageId", messageId);
        envelope.put("instruction", "gossip");
        envelope.put("parameters", parameters);
        if (keepAlive) { envelope.put(CommandProtocol.CONNECTION_FIELD, CommandProtocol.KEEP_ALIVE); }
        return envelope;
    }

    public byte[] requestBytes(Map<String, Object> parameters) {
        return bytes(envelope(BUDGET_ID, parameters));
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
    int replyMemberBudget(MemberRecord self, MembershipTable.Admission admission) {
        return config.maxExchangeBytes() - replyOverheads.get(admission) - self.serializedBytes()
                - self.key().serverId().length();
    }

    /** UUIDs have fixed serialized width; Long.MAX_VALUE reserves the largest legal connection ID. */
    int replyBytes(String serverId, Object result) {
        return bytes(new CommandReply(1, BUDGET_ID, serverId, Long.MAX_VALUE, "OK", result, null)).length;
    }

    public boolean replyFits(String serverId, Object result) {
        return replyBytes(serverId, result) <= config.maxExchangeBytes();
    }

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
        if (replyOverheads.get(MembershipTable.Admission.CAPACITY) + besideSelf > limit) {
            throw invalidRecord(REPLY_TOO_LARGE + ": the local record alone does not fit");
        }
        int emptyPage = pageOverhead + besideSelf;
        for (MemberRecord member : records) {
            boolean local = member.key().equals(self.key());
            if (!local && requestSize(member, List.of()) > limit) {
                throw invalidRecord("Single sender exceeds the complete request budget");
            }
            int row = (local ? widestSelf : member.serializedBytes()) + rowExtra;
            if (emptyPage + row > limit) {
                throw invalidRecord(REPLY_TOO_LARGE + ": a record does not fit as a members-page row");
            }
        }
    }

    private static MembershipTable.MemberView widestView(MemberRecord member) {
        return new MembershipTable.MemberView(member, MembershipTable.Assessment.CANDIDATE, Long.MAX_VALUE,
                OptionalLong.of(Long.MAX_VALUE), false, false);
    }

    /** A members cursor at its widest: a boot ID is fixed-width, and the offset is at most the record limit. */
    private String widestCursor(MemberRecord self) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (self.key().bootId() + "/" + config.maxRecords()).getBytes(StandardCharsets.UTF_8));
    }

    private Capacity widestCapacity() {
        return new Capacity(config.maxMembers(), config.maxRecords(), config.maxRecords(), Long.MAX_VALUE);
    }

    /** The encoded size of one members-page row, so a page can be filled to its budget without encoding it whole. */
    static int rowBytes(MembershipTable.MemberView member) { return bytes(memberView(member)).length; }

    static Map<String, Object> memberView(MembershipTable.MemberView member) {
        var result = new LinkedHashMap<>(member.record().toWire());
        result.put("assessment", member.assessment().name());
        result.put("advanceAgeMillis", TimeUnit.NANOSECONDS.toMillis(member.advanceAgeNanos()));
        var directSuccessAge = member.directSuccessAgeNanos();
        // None yet is written as null, which the members page has always reported.
        result.put("directSuccessAgeMillis", directSuccessAge.isPresent()
                ? TimeUnit.NANOSECONDS.toMillis(directSuccessAge.getAsLong()) : null);
        result.put("endpointVerified", member.endpointVerified());
        result.put("identityConflict", member.identityConflict());
        return result;
    }

    /**
     * A value that may be missing, as the wire writes it: the number, or null, which replies have always used for none.
     */
    static Long orNull(OptionalLong value) { return value.isPresent() ? value.getAsLong() : null; }

    static byte[] bytes(Object value) {
        try { return Json.write(value).getBytes(StandardCharsets.UTF_8); }
        catch (IOException _) { throw invalidRecord("Gossip data cannot be serialized"); }
    }

    /** Anything but this version, or a version that is not an exact integer, is a version failure. */
    private static void requireVersion(Object value) {
        long version;
        try { version = GossipValidation.exactLong(value, GOSSIP_VERSION, Long.MIN_VALUE, Long.MAX_VALUE); }
        catch (MembershipException e) { throw invalidRecord(GossipFailure.VERSION, e.getMessage()); }
        if (version != VERSION) { throw invalidRecord(GossipFailure.VERSION, GOSSIP_VERSION + " must be " + VERSION); }
    }
}
