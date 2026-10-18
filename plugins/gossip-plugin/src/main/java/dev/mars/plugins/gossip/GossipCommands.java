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
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static dev.mars.plugins.gossip.GossipFields.CAPACITY;
import static dev.mars.plugins.gossip.GossipFields.CURSOR;
import static dev.mars.plugins.gossip.GossipFields.EXCHANGE_OPERATION;
import static dev.mars.plugins.gossip.GossipFields.GOSSIP_VERSION;
import static dev.mars.plugins.gossip.GossipFields.LIMIT;
import static dev.mars.plugins.gossip.GossipFields.OPERATION;
import static dev.mars.plugins.gossip.GossipFields.SELF;
import static dev.mars.plugins.gossip.GossipFields.SWARM_ID;

/** Handlers validate, merge, inspect, or request asynchronous departure. Never dial peers here. */
final class GossipCommands {
    private static final Pattern CURSOR_CHARACTERS = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern OFFSET = Pattern.compile("\\d{1,10}");
    private static final int DEFAULT_PAGE_LIMIT = 32; // Rows in a members page when the caller sets no limit.
    // The operations besides an exchange.
    private static final String MEMBERS_OPERATION = "members";
    private static final String STATUS_OPERATION = "status";
    private static final String LEAVE_OPERATION = "leave";
    private final GossipConfig config;
    private final MembershipTable table;
    private final GossipWire wire;
    private final GossipMetrics metrics;
    private final GossipTransport transport;
    private final GossipRuntime runtime;
    private final GossipLog log;
    private final String serverId;
    private final GossipBatchSelector responses = new GossipBatchSelector();

    GossipCommands(GossipConfig config, MembershipTable table, GossipMetrics metrics, GossipTransport transport,
            GossipRuntime runtime) {
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
        String operation = PluginParameters.string(parameters, OPERATION);
        try {
            return switch (operation) {
                case EXCHANGE_OPERATION -> exchange(parameters);
                case MEMBERS_OPERATION -> members(parameters);
                case LEAVE_OPERATION -> operationOnly(parameters, () -> Map.of("departure", runtime.leave()));
                case STATUS_OPERATION -> operationOnly(parameters, this::status);
                default ->
                        throw new InvalidParametersException("Supported operations: exchange, members, status, leave");
            };
        } catch (MembershipException e) {
            if (operation.equals(EXCHANGE_OPERATION)) { metrics.rejectedExchanges.incrementAndGet(); }
            throw new InvalidParametersException(e.reason() + ": " + e.getMessage());
        }
    }

    /** For operations that take no parameters besides their name; exchange and members check their own replies. */
    private Object operationOnly(Map<String, Object> parameters, java.util.function.Supplier<Object> result)
            throws InvalidParametersException {
        PluginParameters.requireKeys(parameters, OPERATION);
        var reply = result.get();
        wire.requireReplyFits(serverId, reply);
        return reply;
    }

    private Object exchange(Map<String, Object> parameters) {
        var incoming = wire.decodeRequest(parameters);
        var self = table.self();
        MembershipTable.ExchangeMerge merge;
        try {
            if (incoming.sender().key().equals(self.key())) {
                throw new MembershipException(MembershipException.Reason.CONFLICT,
                        "Exchange sender must not be the local process");
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
                wire.replyMemberBudget(self, admission));
        var result = wire.toWire(new GossipWire.ExchangeReply(self, batch, admission));
        metrics.success(false);
        return result;
    }

    private Object members(Map<String, Object> parameters) {
        if (!Set.of(OPERATION, LIMIT, CURSOR).containsAll(parameters.keySet())) {
            throw MembershipException.invalidRecord("Unknown members parameter");
        }
        int limit = parameters.containsKey(LIMIT) ? GossipValidation.exactInteger(parameters.get(LIMIT), LIMIT, 1,
                config.maxBatchMembers()) : Math.min(DEFAULT_PAGE_LIMIT, config.maxBatchMembers());
        var snapshot = table.snapshot();
        int start = parameters.containsKey(CURSOR) ? decodeCursor(parameters.get(CURSOR), snapshot.self().key()) : 0;
        return wire.toWire(membersPage(snapshot, start, limit));
    }

    /**
     * Up to {@code limit} rows from {@code start}, as many as fit. Page sizes are additive (see GossipWire), so the
     * empty page is measured once and each row once, and the page is sized exactly; it is not encoded again to check.
     */
    private GossipWire.MembersPage membersPage(MembershipTable.Snapshot snapshot, int start, int limit) {
        var members = snapshot.members();
        var self = snapshot.self().key();
        var capacity = GossipWire.Capacity.of(snapshot);
        var empty = new GossipWire.MembersPage(snapshot.self(), List.of(), Optional.empty(), capacity);
        long size = wire.replyBytes(serverId, wire.toWire(empty));
        var rows = new ArrayList<MembershipTable.MemberView>();
        int position = start;
        while (position < members.size() && rows.size() < limit) {
            var row = members.get(position);
            int rowBytes = GossipWire.rowBytes(row) + (rows.isEmpty() ? 0 : 1);
            if (size + rowBytes + cursorBytes(cursor(self, position + 1, members.size())) > config.maxExchangeBytes()) {
                // Cannot happen: every record was checked as a page row, at its widest, before it was merged.
                if (rows.isEmpty()) {
                    throw MembershipException.invalidRecord("Single membership row exceeds the reply budget");
                }
                break;
            }
            rows.add(row);
            size += rowBytes;
            position++;
        }
        return new GossipWire.MembersPage(snapshot.self(), rows, cursor(self, position, members.size()), capacity);
    }

    /** The cursor to the page that starts at {@code next}, or none when no members are left. */
    private static Optional<String> cursor(MemberKey self, int next, int end) {
        return next < end ? Optional.of(encodeCursor(self, next)) : Optional.empty();
    }

    /**
     * What a cursor adds to a page measured without one: its quoted string, in place of the four bytes of null. A
     * cursor is base64url, so it needs no JSON escaping.
     */
    private static int cursorBytes(Optional<String> cursor) {
        return cursor.map(text -> text.length() + 2 - "null".length()).orElse(0);
    }

    private Map<String, Object> status() {
        var snapshot = table.snapshot();
        var assessments = new LinkedHashMap<String, Integer>();
        for (var assessment : MembershipTable.Assessment.values()) { assessments.put(assessment.name(), 0); }
        for (var member : snapshot.members()) { assessments.merge(member.assessment().name(), 1, Integer::sum); }
        var result = new LinkedHashMap<String, Object>();
        result.put(GOSSIP_VERSION, GossipWire.VERSION);
        result.put(SWARM_ID, config.swarmId());
        result.put(SELF, snapshot.self().toWire());
        result.put("workerState", runtime.state().name());
        result.put("isolated", metrics.isolated());
        result.put("implementationStage", "departure-runtime");
        result.put("supportedOperations",
                List.of(EXCHANGE_OPERATION, MEMBERS_OPERATION, STATUS_OPERATION, LEAVE_OPERATION));
        result.put("departure", runtime.departure());
        result.put("exchanges", metrics.counts(transport.inflight()));
        result.put("lastSuccessfulExchangeAgeMillis", GossipWire.orNull(metrics.lastSuccessAgeMillis()));
        result.put(CAPACITY, GossipWire.toWire(GossipWire.Capacity.of(snapshot)));
        result.put("assessments", assessments);
        result.put("identityConflictCount", snapshot.identityConflicts().size());
        result.put("runtime", metrics.runtime());
        return result;
    }

    private static String encodeCursor(MemberKey self, int next) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((self.bootId() + "/" + next).getBytes(StandardCharsets.UTF_8));
    }

    private int decodeCursor(Object value, MemberKey self) {
        if (!(value instanceof String text) || text.length() > 128 || !CURSOR_CHARACTERS.matcher(text).matches()) {
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
