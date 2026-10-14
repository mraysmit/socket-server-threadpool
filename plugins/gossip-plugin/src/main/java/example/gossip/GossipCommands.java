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
package example.gossip;

import dev.mars.plugin.InvalidParametersException;
import dev.mars.plugin.PluginParameters;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Socket command handlers only validate, merge, and build local bounded replies. Never dial peers here. */
final class GossipCommands {
    private final GossipConfig config;
    private final MembershipTable table;
    private final GossipWire wire;
    private final GossipMetrics metrics;
    private final GossipTransport transport;
    private final GossipBatchSelector responses = new GossipBatchSelector();

    GossipCommands(GossipConfig config, MembershipTable table, GossipMetrics metrics, GossipTransport transport) {
        this.config = config;
        this.table = table;
        this.metrics = metrics;
        this.transport = transport;
        wire = new GossipWire(config);
        var snapshot = table.snapshot();
        wire.requireReplyFits(snapshot.self().key().serverId(), wire.result(snapshot.self(), List.of()));
        wire.requireReplyFits(snapshot.self().key().serverId(), status(false));
        wire.requireReplyFits(snapshot.self().key().serverId(), pageResult(snapshot,
                List.of(memberView(snapshot.members().getFirst())), null));
    }

    Object execute(Map<String, Object> parameters, boolean stopped) throws InvalidParametersException {
        String operation = PluginParameters.string(parameters, "operation");
        try {
            return switch (operation) {
                case "exchange" -> exchange(parameters);
                case "members" -> members(parameters);
                case "status" -> {
                    PluginParameters.requireKeys(parameters, "operation");
                    var result = status(stopped);
                    wire.requireReplyFits(table.snapshot().self().key().serverId(), result);
                    yield result;
                }
                default -> throw new InvalidParametersException("Supported operations: exchange, members, status; leave is not implemented");
            };
        } catch (MembershipException e) {
            if (operation.equals("exchange")) { metrics.rejectedExchanges.incrementAndGet(); }
            throw new InvalidParametersException(e.reason() + ": " + e.getMessage());
        }
    }

    private Object exchange(Map<String, Object> parameters) {
        var incoming = wire.decode(parameters, true);
        wire.requireForwardable(table.snapshot().self(), incoming.records());
        table.merge(incoming.records());
        var snapshot = table.snapshot();
        var batch = responses.select(snapshot, config.maxBatchMembers(), members ->
                wire.replyFits(snapshot.self().key().serverId(), wire.result(snapshot.self(), members)));
        var result = wire.result(snapshot.self(), batch);
        metrics.success(false);
        return result;
    }

    private Object members(Map<String, Object> parameters) {
        if (!Set.of("operation", "limit", "cursor").containsAll(parameters.keySet())) {
            throw GossipWire.invalid("Unknown members parameter");
        }
        int limit = parameters.containsKey("limit") ? GossipWire.exactInteger(parameters.get("limit"), "limit", 1,
                config.maxBatchMembers()) : Math.min(32, config.maxBatchMembers());
        var snapshot = table.snapshot();
        int position = parameters.containsKey("cursor") ? decodeCursor(parameters.get("cursor"), snapshot.self().key()) : 0;
        var selected = new ArrayList<Map<String, Object>>();
        while (position < snapshot.members().size() && selected.size() < limit) {
            selected.add(memberView(snapshot.members().get(position)));
            int next = position + 1;
            String cursor = next < snapshot.members().size() ? encodeCursor(snapshot.self().key(), next) : null;
            if (!wire.replyFits(snapshot.self().key().serverId(), pageResult(snapshot, selected, cursor))) {
                selected.removeLast();
                if (selected.isEmpty()) { throw GossipWire.invalid("Single membership row exceeds the reply budget"); }
                break;
            }
            position = next;
        }
        String next = position < snapshot.members().size() ? encodeCursor(snapshot.self().key(), position) : null;
        var result = pageResult(snapshot, selected, next);
        wire.requireReplyFits(snapshot.self().key().serverId(), result);
        return result;
    }

    private Map<String, Object> pageResult(MembershipTable.Snapshot snapshot, List<Map<String, Object>> selected, String cursor) {
        var result = new LinkedHashMap<String, Object>();
        result.put("swarmId", config.swarmId());
        result.put("self", snapshot.self().toWire());
        result.put("members", List.copyOf(selected));
        result.put("nextCursor", cursor);
        result.put("capacity", capacity(snapshot));
        return result;
    }

    private Map<String, Object> status(boolean stopped) {
        var snapshot = table.snapshot();
        var assessments = new LinkedHashMap<String, Integer>();
        for (var assessment : MembershipTable.Assessment.values()) { assessments.put(assessment.name(), 0); }
        for (var member : snapshot.members()) { assessments.merge(member.assessment().name(), 1, Integer::sum); }
        var result = new LinkedHashMap<String, Object>();
        result.put("gossipVersion", 1);
        result.put("swarmId", config.swarmId());
        result.put("self", snapshot.self().toWire());
        result.put("workerState", stopped ? "STOPPED" : "NOT_SCHEDULED");
        result.put("isolated", metrics.isolated());
        result.put("implementationStage", "command-transport");
        result.put("supportedOperations", List.of("exchange", "members", "status"));
        result.put("exchanges", metrics.counts(transport.inflight()));
        result.put("lastSuccessfulExchangeAgeMillis", metrics.lastSuccessAgeMillis());
        result.put("capacity", capacity(snapshot));
        result.put("assessments", assessments);
        result.put("identityConflictCount", snapshot.identityConflicts().size());
        return result;
    }

    private static Map<String, Object> capacity(MembershipTable.Snapshot snapshot) {
        return Map.of("activeMembers", snapshot.activeMembers(), "retainedIdentities", snapshot.retainedIdentities(),
                "tombstones", snapshot.tombstones());
    }

    private static Map<String, Object> memberView(MembershipTable.MemberView member) {
        var result = new LinkedHashMap<>(member.record().toWire());
        result.put("assessment", member.assessment().name());
        result.put("advanceAgeMillis", member.advanceAgeNanos() / 1_000_000);
        result.put("directSuccessAgeMillis", member.directSuccessAgeNanos() == null ? null : member.directSuccessAgeNanos() / 1_000_000);
        result.put("endpointVerified", member.endpointVerified());
        result.put("identityConflict", member.identityConflict());
        return result;
    }

    private static String encodeCursor(MemberKey self, int next) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((self.bootId() + "/" + next).getBytes(StandardCharsets.UTF_8));
    }

    private int decodeCursor(Object value, MemberKey self) {
        if (!(value instanceof String text) || text.length() > 128 || !text.matches("[A-Za-z0-9_-]+")) {
            throw GossipWire.invalid("Invalid membership cursor");
        }
        String decoded;
        try { decoded = new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException e) { throw GossipWire.invalid("Invalid membership cursor"); }
        String[] fields = decoded.split("/", -1);
        if (fields.length != 2 || !fields[0].equals(self.bootId().toString()) || !fields[1].matches("[0-9]{1,10}")) {
            throw GossipWire.invalid("Membership cursor belongs to another instance or is malformed");
        }
        try { return GossipWire.exactInteger(new java.math.BigInteger(fields[1]), "cursor offset", 0, config.maxRecords()); }
        catch (NumberFormatException e) { throw GossipWire.invalid("Invalid membership cursor offset"); }
    }
}
