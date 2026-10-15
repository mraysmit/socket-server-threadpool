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
import dev.mars.plugin.PluginParameters;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Handlers validate, merge, inspect, or request asynchronous departure. Never dial peers here. */
final class GossipCommands {
    private final GossipConfig config;
    private final MembershipTable table;
    private final GossipWire wire;
    private final GossipMetrics metrics;
    private final GossipTransport transport;
    private final GossipRuntime runtime;
    private final GossipLog log;
    private final GossipBatchSelector responses = new GossipBatchSelector();

    GossipCommands(GossipConfig config, MembershipTable table, GossipMetrics metrics, GossipTransport transport, GossipRuntime runtime) {
        this.config = config;
        this.table = table;
        this.metrics = metrics;
        this.transport = transport;
        this.runtime = runtime;
        wire = new GossipWire(config);
        var snapshot = table.snapshot();
        log = metrics.log(config, snapshot.self());
        wire.requireForwardable(snapshot.self(), List.of(snapshot.self()));
        wire.requireReplyFits(snapshot.self().key().serverId(), wire.result(snapshot.self(), List.of()));
        wire.requireReplyFits(snapshot.self().key().serverId(), status());
        wire.requireReplyFits(snapshot.self().key().serverId(), pageResult(snapshot,
                List.of(GossipWire.memberView(snapshot.members().getFirst())), null));
    }

    Object execute(Map<String, Object> parameters) throws InvalidParametersException {
        String operation = PluginParameters.string(parameters, "operation");
        try {
            return switch (operation) {
                case "exchange" -> exchange(parameters);
                case "members" -> members(parameters);
                case "leave" -> {
                    PluginParameters.requireKeys(parameters, "operation");
                    var result = Map.of("departure", runtime.leave());
                    wire.requireReplyFits(table.snapshot().self().key().serverId(), result);
                    yield result;
                }
                case "status" -> {
                    PluginParameters.requireKeys(parameters, "operation");
                    var result = status();
                    wire.requireReplyFits(table.snapshot().self().key().serverId(), result);
                    yield result;
                }
                default -> throw new InvalidParametersException("Supported operations: exchange, members, status, leave");
            };
        } catch (MembershipException e) {
            if (operation.equals("exchange")) { metrics.rejectedExchanges.incrementAndGet(); }
            throw new InvalidParametersException(e.reason() + ": " + e.getMessage());
        }
    }

    private Object exchange(Map<String, Object> parameters) {
        var incoming = wire.decode(parameters, true);
        var self = table.snapshot().self();
        try {
            if (incoming.sender().key().equals(self.key())) {
                throw new MembershipException(MembershipException.Reason.CONFLICT, "Exchange sender must not be the local process");
            }
            wire.requireForwardable(self, incoming.records());
            table.merge(incoming.records());
        } catch (MembershipException failure) {
            if (failure.reason() == MembershipException.Reason.CONFLICT) {
                var peer = incoming.sender();
                log.identity("inbound", new GossipConfig.Seed(peer.key().serverId(), peer.host(), peer.port()), null, failure.getMessage());
            }
            throw failure;
        }
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
            selected.add(GossipWire.memberView(snapshot.members().get(position)));
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
        return wire.page(snapshot.self(), selected, cursor, capacity(snapshot));
    }

    private Map<String, Object> status() {
        var snapshot = table.snapshot();
        var assessments = new LinkedHashMap<String, Integer>();
        for (var assessment : MembershipTable.Assessment.values()) { assessments.put(assessment.name(), 0); }
        for (var member : snapshot.members()) { assessments.merge(member.assessment().name(), 1, Integer::sum); }
        var result = new LinkedHashMap<String, Object>();
        result.put("gossipVersion", 1);
        result.put("swarmId", config.swarmId());
        result.put("self", snapshot.self().toWire());
        result.put("workerState", metrics.workerState);
        result.put("isolated", metrics.isolated());
        result.put("implementationStage", "departure-runtime");
        result.put("supportedOperations", List.of("exchange", "members", "status", "leave"));
        result.put("departure", runtime.departure());
        result.put("exchanges", metrics.counts(transport.inflight()));
        result.put("lastSuccessfulExchangeAgeMillis", metrics.lastSuccessAgeMillis());
        result.put("capacity", capacity(snapshot));
        result.put("assessments", assessments);
        result.put("identityConflictCount", snapshot.identityConflicts().size());
        result.put("runtime", metrics.runtime());
        return result;
    }

    private static Map<String, Object> capacity(MembershipTable.Snapshot snapshot) {
        return Map.of("activeMembers", snapshot.activeMembers(), "retainedIdentities", snapshot.retainedIdentities(),
                "tombstones", snapshot.tombstones());
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
