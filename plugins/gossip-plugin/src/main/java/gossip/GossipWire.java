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

import static gossip.MembershipException.Reason.INVALID_RECORD;

/** Gossip versioning and complete canonical-envelope accounting, independent of host version 1. */
public final class GossipWire {
    private static final UUID BUDGET_ID = new UUID(0, 0);
    private final GossipConfig config;

    public record Exchange(MemberRecord sender, List<MemberRecord> members) {
        public Exchange { members = List.copyOf(members); }
        public List<MemberRecord> records() {
            var records = new ArrayList<MemberRecord>();
            records.add(sender);
            records.addAll(members);
            return List.copyOf(records);
        }
    }

    public GossipWire(GossipConfig config) { this.config = config; }

    public Exchange decode(Object value, boolean request) {
        Set<String> fields = request ? Set.of("operation", "gossipVersion", "swarmId", "sender", "members")
                : Set.of("gossipVersion", "swarmId", "sender", "members");
        if (!(value instanceof Map<?, ?> map) || !map.keySet().equals(fields)) { throw invalid("Invalid exchange fields"); }
        if (request && !"exchange".equals(map.get("operation"))) { throw invalid("operation must be exchange"); }
        if (exactInteger(map.get("gossipVersion"), "gossipVersion", 1, 1) != 1) { throw invalid("Unsupported gossipVersion"); }
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
        var exchange = new Exchange(sender, decoded);
        if (request && requestBytes(parameters(sender, decoded)).length > config.maxExchangeBytes()) {
            throw invalid("Complete canonical request exceeds max.exchange.bytes");
        }
        return exchange;
    }

    public Map<String, Object> parameters(MemberRecord self, List<MemberRecord> members) {
        var result = new LinkedHashMap<>(result(self, members));
        result.put("operation", "exchange");
        return Map.copyOf(result);
    }

    public Map<String, Object> result(MemberRecord self, List<MemberRecord> members) {
        return Map.of("gossipVersion", 1, "swarmId", config.swarmId(), "sender", self.toWire(),
                "members", members.stream().map(MemberRecord::toWire).toList());
    }

    public byte[] requestBytes(Map<String, Object> parameters) {
        return bytes(new CommandRequest(1, BUDGET_ID, "gossip", parameters));
    }

    /** UUIDs have fixed serialized width; Long.MAX_VALUE reserves the largest legal connection ID. */
    public boolean replyFits(String serverId, Object result) {
        return bytes(new CommandReply(1, BUDGET_ID, serverId, Long.MAX_VALUE, "OK", result, null)).length
                <= config.maxExchangeBytes();
    }

    public void requireReplyFits(String serverId, Object result) {
        if (!replyFits(serverId, result)) { throw invalid("Complete reply exceeds max.exchange.bytes"); }
    }

    /** Check new records before merging, so a single unforwardable record cannot break later replies. */
    public void requireForwardable(MemberRecord self, List<MemberRecord> records) {
        var budgetSelf = new MemberRecord(self.key(), self.host(), self.port(), Long.MAX_VALUE, self.state(), self.metadata());
        requireReplyFits(self.key().serverId(), result(budgetSelf, List.of()));
        for (MemberRecord record : records) {
            if (!record.key().equals(self.key())) {
                if (requestBytes(parameters(record, List.of())).length > config.maxExchangeBytes()) {
                    throw invalid("Single sender exceeds the complete request budget");
                }
                if (config.maxBatchMembers() > 1) {
                    requireReplyFits(self.key().serverId(), result(budgetSelf, List.of(record)));
                }
            }
            var worst = new MembershipTable.MemberView(record.key().equals(self.key()) ? budgetSelf : record, MembershipTable.Assessment.CANDIDATE,
                    Long.MAX_VALUE, Long.MAX_VALUE, false, false);
            String cursor = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (self.key().bootId() + "/" + config.maxRecords()).getBytes(StandardCharsets.UTF_8));
            requireReplyFits(self.key().serverId(), page(budgetSelf, List.of(memberView(worst)), cursor,
                    Map.of("activeMembers", config.maxMembers(), "retainedIdentities", config.maxRecords(),
                            "tombstones", config.maxRecords())));
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

    static int exactInteger(Object value, String field, int min, int max) {
        long number;
        if (value instanceof BigInteger integer) {
            try { number = integer.longValueExact(); }
            catch (ArithmeticException e) { throw invalid(field + " is outside the supported integer range"); }
        } else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            number = ((Number) value).longValue();
        } else { throw invalid(field + " must be an integer"); }
        if (number < min || number > max) { throw invalid(field + " must be between " + min + " and " + max); }
        return (int) number;
    }

    static byte[] bytes(Object value) {
        try { return Json.write(value).getBytes(StandardCharsets.UTF_8); }
        catch (IOException e) { throw invalid("Gossip data cannot be serialized"); }
    }

    static MembershipException invalid(String message) { return new MembershipException(INVALID_RECORD, message); }
}
