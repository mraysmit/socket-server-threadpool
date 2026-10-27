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
package dev.mars.plugins.kv;

import dev.mars.client.KeepAliveClient;
import dev.mars.plugin.InvalidParametersException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Talks to a peer over the socket protocol: hands it records, and takes part in comparing stores. Each call is one command
 * over a connection kept open to that peer and reused by the next call, so steady writes do not open a connection each.
 * Every call is safe to send twice, as a pooled connection the peer closed makes the client do: applying a record again
 * changes nothing, and comparing is read-only. Given a swarm source, every message names this server's swarm, and a peer
 * in another swarm refuses it. Without one, only plain pushes can be sent, and they name no swarm.
 */
final class TcpPeerLink implements PeerLink, SyncLink {
    /** Far above any reply a peer sends: a sync page is bounded by sync.max.bytes, plus at most one oversized record. */
    private static final int MAX_REPLY_BYTES = 1 << 20;
    private final SwarmIdSource swarmIds;
    private final KeepAliveClient connections;
    /** This server's ID, which pushes name so the receiver passes them on to others and takes this server into its mesh. */
    private final String serverId;

    TcpPeerLink() { this(null); }

    TcpPeerLink(SwarmIdSource swarmIds) { this(swarmIds, newClient()); }

    TcpPeerLink(SwarmIdSource swarmIds, KeepAliveClient connections) { this(swarmIds, connections, null); }

    TcpPeerLink(SwarmIdSource swarmIds, KeepAliveClient connections, String serverId) {
        this.swarmIds = swarmIds;
        this.connections = java.util.Objects.requireNonNull(connections);
        this.serverId = serverId;
    }

    /** The client the plugin shares between its peer link and its calls to its own gossip, and closes when it stops. */
    static KeepAliveClient newClient() {
        return new KeepAliveClient(KeepAliveClient.Settings.of("Key-value", MAX_REPLY_BYTES, Duration.ofSeconds(5),
                Duration.ofSeconds(10), Duration.ofSeconds(15)));
    }

    /** A push names this server, so the receiver leaves it out when passing the records on. */
    @Override public void send(Peer peer, List<KvRecord> records) throws IOException { apply(peer, records, serverId); }

    /** An apply with no records, naming this server: the peer takes it into its push mesh. */
    @Override public void graft(Peer peer) throws IOException {
        if (serverId != null) { apply(peer, List.of(), serverId); }
    }

    /** The records a comparison found the peer wants. It names no sender, as before pushes did. */
    @Override public void apply(Peer peer, List<KvRecord> records) throws IOException { apply(peer, records, null); }

    private void apply(Peer peer, List<KvRecord> records, String from) throws IOException {
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("operation", "apply");
        var swarm = swarmIfKnown();
        if (swarm != null) { parameters.put("swarmId", swarm); }
        if (from != null) { parameters.put("from", from); }
        var wire = new ArrayList<Map<String, Object>>();
        for (var record : records) { wire.add(KvWire.toWire(record)); }
        parameters.put("records", wire);
        call(peer, parameters);
    }

    @Override public SyncResponder.RootReply syncRoot(Peer peer, String root) throws IOException {
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("operation", "sync-root");
        parameters.put("swarmId", swarm());
        parameters.put("root", root);
        var reply = call(peer, parameters);
        if (!(reply.get("equal") instanceof Boolean equal) || !(reply.get("root") instanceof String theirs)) {
            throw new IOException("The peer's sync-root reply is not understood");
        }
        return new SyncResponder.RootReply(equal, theirs);
    }

    @Override public SyncResponder.DigestReply syncDigest(Peer peer, SyncResponder.DigestRequest request) throws IOException {
        var rows = new ArrayList<Map<String, Object>>();
        for (var row : request.rows()) {
            var wire = new LinkedHashMap<String, Object>();
            wire.put("key", row.key());
            var version = new LinkedHashMap<String, Object>();
            version.put("w", row.version().w());
            version.put("c", row.version().c());
            version.put("n", row.version().n());
            wire.put("version", version);
            wire.put("deleted", row.deleted());
            rows.add(wire);
        }
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("operation", "sync-digest");
        parameters.put("swarmId", swarm());
        parameters.put("after", request.after());
        parameters.put("upTo", request.upTo());
        parameters.put("rows", rows);
        var reply = call(peer, parameters);
        if (!(reply.get("want") instanceof List<?> want) || !want.stream().allMatch(key -> key instanceof String)) {
            throw new IOException("The peer's sync-digest reply is not understood");
        }
        List<KvRecord> records;
        try { records = KvWire.parseRecords(reply.get("records")); }
        catch (InvalidParametersException e) { throw new IOException("The peer's sync-digest reply is not understood: " + e.getMessage(), e); }
        String continueAfter = reply.get("continueAfter") instanceof String key ? key : null;
        return new SyncResponder.DigestReply(records, want.stream().map(String.class::cast).toList(), continueAfter);
    }

    /** A push names this server's swarm when it can be found out; when it cannot, the push goes without, as it did before swarms were named. */
    private String swarmIfKnown() {
        if (swarmIds == null) { return null; }
        try { return swarmIds.swarmId(); }
        catch (IOException | RuntimeException e) { return null; }
    }

    private String swarm() throws IOException {
        if (swarmIds == null) { throw new IOException("This link does not know which swarm it is in, so it cannot compare stores"); }
        return swarmIds.swarmId();
    }

    /** One kv command to one peer. An error reply, such as a refusal from another swarm, is thrown as an IOException. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> call(Peer peer, Map<String, Object> parameters) throws IOException {
        var reply = connections.execute(new InetSocketAddress(peer.host(), peer.port()), "kv", parameters);
        if (!(reply.result() instanceof Map<?, ?> result)) { throw new IOException("The peer's reply is not an object"); }
        return (Map<String, Object>) result;
    }
}
