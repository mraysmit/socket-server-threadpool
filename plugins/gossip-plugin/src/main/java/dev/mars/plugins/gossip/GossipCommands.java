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

import dev.mars.plugin.InvalidParametersException;
import dev.mars.plugin.PluginParameters;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Handlers validate, merge, inspect, or request asynchronous departure. Never dial peers here. */
final class GossipCommands {
    private static final Pattern CURSOR = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern OFFSET = Pattern.compile("\\d{1,10}");
    private static final int DEFAULT_PAGE_LIMIT = 32; // Rows in a members page when the caller sets no limit.
    private final GossipConfig config;
    private final MembershipTable table;
    private final GossipWire wire;
    private final GossipMetrics metrics;
    private final GossipTransport transport;
    private final GossipRuntime runtime;
    private final GossipLog log;
    private final String serverId;
    private final GossipBatchSelector responses = new GossipBatchSelector();

    GossipCommands(GossipConfig config, MembershipTable table, GossipMetrics metrics, GossipTransport transport, GossipRuntime runtime) {
        this.config = config;
        this.table = table;
        this.metrics = metrics;
        this.transport = transport;
        this.runtime = runtime;
        wire = new GossipWire(config);
        var self = table.self();
        serverId = self.key().serverId();
        log = metrics.log(config, self);
        requireRepliesFit(self);
    }

    /**
     * Fails at start, rather than on the first request, when max.exchange.bytes cannot hold this process's replies.
     * A forwardable local record fits every exchange reply and members page, at their widest, so only status, whose
     * size it does not bound, is checked besides.
     */
    private void requireRepliesFit(MemberRecord self) {
        wire.requireForwardable(self, List.of(self));
        wire.requireReplyFits(serverId, status());
    }

    Object execute(Map<String, Object> parameters) throws InvalidParametersException {
        String operation = PluginParameters.string(parameters, "operation");
        try {
            return switch (operation) {
                case "exchange" -> exchange(parameters);
                case "members" -> members(parameters);
                case "leave" -> operationOnly(parameters, () -> Map.of("departure", runtime.leave()));
                case "status" -> operationOnly(parameters, this::status);
                default -> throw new InvalidParametersException("Supported operations: exchange, members, status, leave");
            };
        } catch (MembershipException e) {
            if (operation.equals("exchange")) { metrics.rejectedExchanges.incrementAndGet(); }
            throw new InvalidParametersException(e.reason() + ": " + e.getMessage());
        }
    }

    /** For operations that take no parameters besides their name; exchange and members check their own replies. */
    private Object operationOnly(Map<String, Object> parameters, java.util.function.Supplier<Object> result)
            throws InvalidParametersException {
        PluginParameters.requireKeys(parameters, "operation");
        var reply = result.get();
        wire.requireReplyFits(serverId, reply);
        return reply;
    }

    private Object exchange(Map<String, Object> parameters) {
        var incoming = wire.decode(parameters, true);
        var self = table.self();
        MembershipTable.ExchangeMerge merge;
        try {
            if (incoming.sender().key().equals(self.key())) {
                throw new MembershipException(MembershipException.Reason.CONFLICT, "Exchange sender must not be the local process");
            }
            wire.requireForwardable(self, incoming.records());
            merge = table.mergeExchange(incoming.sender(), incoming.members());
        } catch (MembershipException failure) {
            if (failure.reason() == MembershipException.Reason.CONFLICT) {
                var peer = incoming.sender();
                log.identity("inbound", peer.endpoint(), null, failure.getMessage());
            }
            throw failure;
        }
        self = table.self(); // A round may have advanced the local heartbeat since it was read above.
        var admission = merge.senderAdmission();
        // The batch stays within what the empty reply leaves, and sizes are additive, so the reply fits without
        // being encoded again just to check; the host encodes it once, to send it.
        var batch = responses.select(table.peers(), config.maxBatchMembers(),
                wire.replyMemberBudget(self, incoming.gossipVersion(), admission));
        var result = wire.result(self, batch, incoming.gossipVersion(), admission);
        metrics.success(false);
        return result;
    }

    private Object members(Map<String, Object> parameters) {
        if (!Set.of("operation", "limit", "cursor").containsAll(parameters.keySet())) {
            throw MembershipException.invalidRecord("Unknown members parameter");
        }
        int limit = parameters.containsKey("limit") ? GossipValidation.exactInteger(parameters.get("limit"), "limit", 1,
                config.maxBatchMembers()) : Math.min(DEFAULT_PAGE_LIMIT, config.maxBatchMembers());
        var snapshot = table.snapshot();
        int start = parameters.containsKey("cursor") ? decodeCursor(parameters.get("cursor"), snapshot.self().key()) : 0;
        return membersPage(snapshot, start, limit);
    }

    /**
     * Up to {@code limit} rows from {@code start}, as many as fit. Page sizes are additive (see GossipWire), so the
     * empty page is measured once and each row once, and the page is sized exactly; it is not encoded again to check.
     */
    private Map<String, Object> membersPage(MembershipTable.Snapshot snapshot, int start, int limit) {
        var members = snapshot.members();
        var self = snapshot.self().key();
        long size = wire.replyBytes(serverId, pageResult(snapshot, List.of(), null));
        var rows = new ArrayList<Map<String, Object>>();
        int position = start;
        while (position < members.size() && rows.size() < limit) {
            var row = GossipWire.memberView(members.get(position));
            int rowBytes = GossipWire.bytes(row).length + (rows.isEmpty() ? 0 : 1);
            if (size + rowBytes + cursorBytes(cursor(self, position + 1, members.size())) > config.maxExchangeBytes()) {
                // Cannot happen: every record was checked as a page row, at its widest, before it was merged.
                if (rows.isEmpty()) { throw MembershipException.invalidRecord("Single membership row exceeds the reply budget"); }
                break;
            }
            rows.add(row);
            size += rowBytes;
            position++;
        }
        return pageResult(snapshot, rows, cursor(self, position, members.size()));
    }

    /** The cursor to the page that starts at {@code next}, or none when no members are left. */
    private static String cursor(MemberKey self, int next, int end) { return next < end ? encodeCursor(self, next) : null; }

    /**
     * What a cursor adds to a page measured without one: its quoted string, in place of the four bytes of null. A
     * cursor is base64url, so it needs no JSON escaping.
     */
    private static int cursorBytes(String cursor) { return cursor == null ? 0 : cursor.length() + 2 - "null".length(); }

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
        result.put("workerState", runtime.state().name());
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
                "tombstones", snapshot.tombstones(), "admissionsSkipped", snapshot.admissionsSkipped());
    }

    private static String encodeCursor(MemberKey self, int next) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((self.bootId() + "/" + next).getBytes(StandardCharsets.UTF_8));
    }

    private int decodeCursor(Object value, MemberKey self) {
        if (!(value instanceof String text) || text.length() > 128 || !CURSOR.matcher(text).matches()) {
            throw MembershipException.invalidRecord("Invalid membership cursor");
        }
        String decoded;
        try { decoded = new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException _) { throw MembershipException.invalidRecord("Invalid membership cursor"); }
        String[] fields = decoded.split("/", -1);
        if (fields.length != 2 || !fields[0].equals(self.bootId().toString()) || !OFFSET.matcher(fields[1]).matches()) {
            throw MembershipException.invalidRecord("Membership cursor belongs to another instance or is malformed");
        }
        // At most ten digits, so parsing cannot overflow a long.
        return GossipValidation.exactInteger(Long.parseLong(fields[1]), "cursor offset", 0, config.maxRecords());
    }
}
