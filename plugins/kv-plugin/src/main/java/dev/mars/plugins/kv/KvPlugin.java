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

import dev.mars.plugin.CommandPlugin;
import dev.mars.plugin.InvalidParametersException;
import dev.mars.plugin.PluginContext;
import dev.mars.plugin.PluginParameters;
import dev.mars.protocol.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An in-memory store of key-value pairs, one per server, behind the {@code kv} instruction. Its operations are
 * {@code put}, {@code get}, {@code delete} and {@code list}; a value may be any JSON value. The store accepts
 * commands only while the plugin is running, and is lost when the server stops.
 *
 * <p>With replication on, each local write is also pushed to the other servers its own gossip plugin rates alive, and
 * {@code apply} accepts the records they push. Every pair carries a version, and the newest write wins. {@code status}
 * reports what the store holds and what replication has done.
 */
public final class KvPlugin implements CommandPlugin {
    /** The shipped limits, read from the bundled defaults. A server's own configuration can change them. */
    static final int MAX_KEY_LENGTH = KvConfig.defaults().maxKeyLength();
    static final int MAX_VALUE_BYTES = KvConfig.defaults().maxValueBytes();
    static final int MAX_PAIRS = KvConfig.defaults().maxRecords();
    private static final String OPERATION = "operation";
    private static final String KEY = "key";
    private static final String VALUE = "value";
    private static final String SWARM_ID = "swarmId";
    private static final String FROM = "from";

    private enum State { NEW, RUNNING, STOPPED }

    /**
     * Sorted, so {@code list} returns keys in order. Each value is held as its JSON text, which gives its exact size
     * and is a copy that no caller can change afterwards. Each pair also carries the version that decides which of two
     * writes is newer.
     */
    private volatile VersionedStore store;
    private volatile SyncResponder responder;
    private volatile SwarmIdSource swarmIds;
    private final SyncLink syncLink;
    private volatile Thread syncThread;
    private final PeerSource peers;
    private final PeerLink link;
    private final Executor executor;
    private volatile ExecutorService ownExecutor;
    private volatile dev.mars.client.KeepAliveClient ownConnections;
    private volatile Replicator replicator;
    private final KvCounters counters = new KvCounters();
    private final SyncStats syncStats = new SyncStats(System::currentTimeMillis);
    private volatile State state = State.NEW;
    private volatile String serverId;

    private final Path configFile;
    private volatile KvConfig config = KvConfig.defaults();

    /** Reads its settings from the file named by the kv.config system property, if there is one. */
    public KvPlugin() { this(null); }

    KvPlugin(Path configFile) { this(configFile, null, null, null); }

    /** With a peer list and a link given, replication uses them. Tests give fakes; otherwise they are made when the plugin starts. */
    KvPlugin(Path configFile, PeerSource peers, PeerLink link, Executor executor) {
        this(configFile, peers, link, executor, null);
    }

    KvPlugin(Path configFile, PeerSource peers, PeerLink link, Executor executor, SwarmIdSource swarmIds) {
        this(configFile, peers, link, executor, swarmIds, null);
    }

    /** The comparison link is the one given, or the push link if that can compare too, or a real one made at start. */
    KvPlugin(Path configFile, PeerSource peers, PeerLink link, Executor executor, SwarmIdSource swarmIds, SyncLink syncLink) {
        this.syncLink = syncLink;
        this.swarmIds = swarmIds;
        this.configFile = configFile;
        this.peers = peers;
        this.link = link;
        this.executor = executor;
    }

    @Override public String instruction() { return "kv"; }

    @Override public void start(PluginContext context) throws IOException {
        serverId = context.serverId();
        config = KvConfig.load(configFile != null ? configFile : fileFromProperty(), context.serverId());
        store = new VersionedStore(context.serverId(), System::currentTimeMillis, config);
        responder = new SyncResponder(store, config.syncMaxBytes());
        // One set of kept-open connections, to the peers and to this server's own gossip, closed when the plugin stops.
        var connections = TcpPeerLink.newClient();
        ownConnections = connections;
        if (swarmIds == null) { swarmIds = new GossipSwarmId(new LoopbackGossip(context.port(), connections)); }
        // Running before the sync thread starts, which runs only while the plugin is.
        state = State.RUNNING;
        if (config.replicate()) {
            // Anything not given (tests give fakes) is the real thing: peers from this server's own gossip, over sockets.
            var pushes = executor;
            if (pushes == null) {
                ownExecutor = Executors.newVirtualThreadPerTaskExecutor();
                pushes = ownExecutor;
            }
            var found = peers != null ? peers : new GossipPeers(new LoopbackGossip(context.port(), connections), context.serverId());
            var tcp = new TcpPeerLink(swarmIds, connections, context.serverId());
            replicator = new Replicator(found, link != null ? link : tcp, pushes, counters, config.pushFanout(), new Random());
            SyncLink compare = syncLink != null ? syncLink : link instanceof SyncLink capable ? capable : tcp;
            var rounds = new SyncRounds(found, new Syncer(store, compare, config.syncMaxBytes()), store, syncStats, config.syncFanout(), new Random());
            syncThread = Thread.ofVirtual().name("kv-sync-" + context.serverId()).start(() -> syncLoop(rounds, config.syncRoundMillis()));
        }
    }

    /** Pulls from a peer as soon as one can be found, then compares with a few random peers every round, until the plugin stops. */
    private void syncLoop(SyncRounds rounds, long everyMillis) {
        boolean pulled = false;
        while (state == State.RUNNING) {
            try {
                // Each round also keeps the push mesh in step with the peers alive, as gossipsub's heartbeat does.
                var current = replicator;
                if (current != null) { current.maintain(); }
                if (!pulled) { pulled = rounds.bootstrap() > 0; } else { rounds.round(); }
            } catch (RuntimeException e) {
                // A comparison that goes wrong is counted where it can be, and the next round tries again.
                syncStats.failed();
            }
            try { Thread.sleep(everyMillis); }
            catch (InterruptedException stopped) { return; }
        }
    }

    private static Path fileFromProperty() {
        String path = System.getProperty("kv.config");
        return path == null ? null : Path.of(path);
    }

    @Override public void stop() {
        state = State.STOPPED;
        var loop = syncThread;
        if (loop != null) { loop.interrupt(); }
        var own = ownExecutor;
        if (own != null) { own.shutdown(); }
        var connections = ownConnections;
        if (connections != null) {
            try { connections.close(); }
            catch (IOException e) { System.err.println("Key-value connections did not all close: " + e.getMessage()); }
        }
    }

    @Override public Map<String, Object> execute(Map<String, Object> parameters) throws InvalidParametersException {
        switch (state) {
            case NEW -> throw new IllegalStateException("The key-value store has not started");
            case STOPPED -> throw new IllegalStateException("The key-value store has stopped");
            case RUNNING -> { }
        }
        return switch (PluginParameters.string(parameters, OPERATION)) {
            case "put" -> {
                PluginParameters.requireKeys(parameters, OPERATION, KEY, VALUE);
                yield put(key(parameters), encode(parameters.get(VALUE)));
            }
            case "get" -> {
                PluginParameters.requireKeys(parameters, OPERATION, KEY);
                yield get(key(parameters));
            }
            case "delete" -> {
                PluginParameters.requireKeys(parameters, OPERATION, KEY);
                yield delete(key(parameters));
            }
            case "list" -> {
                PluginParameters.requireKeys(parameters, OPERATION);
                yield list();
            }
            case "apply" -> {
                // The swarm and the sending server are optional: a server from before either was named sends neither.
                var expected = new ArrayList<>(List.of(OPERATION, "records"));
                if (parameters.containsKey(SWARM_ID)) { expected.add(SWARM_ID); }
                if (parameters.containsKey(FROM)) { expected.add(FROM); }
                PluginParameters.requireKeys(parameters, expected.toArray(String[]::new));
                yield apply(parameters.get(SWARM_ID), parameters.get(FROM), parameters.get("records"));
            }
            case "sync-root" -> {
                PluginParameters.requireKeys(parameters, OPERATION, SWARM_ID, "root");
                yield syncRoot(parameters.get(SWARM_ID), parameters.get("root"));
            }
            case "sync-digest" -> {
                PluginParameters.requireKeys(parameters, OPERATION, SWARM_ID, "after", "upTo", "rows");
                yield syncDigest(parameters.get(SWARM_ID), parameters.get("after"), parameters.get("upTo"), parameters.get("rows"));
            }
            case "status" -> {
                PluginParameters.requireKeys(parameters, OPERATION);
                yield status();
            }
            default -> throw new InvalidParametersException("Supported operations: put, get, delete, list, apply, status");
        };
    }

    /** Creates the pair, or replaces its value; a new key is refused once the store is full. */
    private synchronized Map<String, Object> put(String key, String value) throws InvalidParametersException {
        boolean exists = store.get(key).isPresent();
        if (!exists && store.liveCount() >= config.maxRecords()) {
            throw new InvalidParametersException("The store is full: it holds at most " + config.maxRecords() + " pairs");
        }
        var write = store.put(key, value);
        push(write.record());
        return reply(KEY, key, "created", !write.hadLive());
    }

    private Map<String, Object> get(String key) {
        var record = store.get(key);
        if (record.isEmpty()) { return reply(KEY, key, "found", false); }
        // A JSON null value is stored as the text "null", so a stored null is still found.
        return reply(KEY, key, "found", true, VALUE, decode(record.get().valueJson()));
    }

    private synchronized Map<String, Object> delete(String key) {
        var write = store.delete(key);
        push(write.record());
        return reply(KEY, key, "deleted", write.hadLive());
    }

    /** Hands a local write to the replicator, if replication is on. Never throws, and never waits for a peer. */
    private void push(KvRecord record) {
        var current = replicator;
        if (current != null) { current.push(record); }
    }

    private synchronized Map<String, Object> list() {
        var keys = store.keys();
        return reply("keys", keys, "count", keys.size());
    }

    /**
     * Merges records another server sends. The whole batch is checked first, so a bad record stores nothing. Records that
     * were new here are passed on to this server's push mesh; one already held stops, so a write stops spreading once all
     * have it. A sender that names itself is left out of that, and taken into the mesh, so pushes flow both ways.
     */
    private Map<String, Object> apply(Object swarm, Object from, Object records) throws InvalidParametersException {
        requireReplication();
        if (swarm != null) { requireSameSwarm(swarm); }
        if (from != null && (!(from instanceof String sender) || sender.isBlank())) {
            throw new InvalidParametersException("Parameter 'from' must be the sending server's ID");
        }
        var parsed = KvWire.parseRecords(records);
        var taken = new ArrayList<KvRecord>();
        var result = store.apply(parsed, taken);
        var current = replicator;
        if (current != null) {
            String sender = (String) from;
            if (sender != null && !sender.equals(serverId)) { current.grafted(sender); }
            current.forward(taken, sender);
        }
        counters.applied.addAndGet(result.applied());
        counters.ignored.addAndGet(result.ignored());
        counters.rejected.addAndGet(result.rejected());
        return reply("applied", result.applied(), "ignored", result.ignored(), "rejected", result.rejected());
    }

    private void requireReplication() throws InvalidParametersException {
        if (!config.replicate()) { throw new InvalidParametersException("Replication is off on this server"); }
    }

    /** A message that names a swarm is for this server only if it is in that swarm, so servers of two swarms never mix their pairs. */
    private void requireSameSwarm(Object given) throws InvalidParametersException {
        if (!(given instanceof String named) || named.isEmpty()) { throw new InvalidParametersException("Parameter 'swarmId' must be the name of a swarm"); }
        String own;
        try { own = swarmIds.swarmId(); }
        catch (IOException | RuntimeException e) {
            throw new InvalidParametersException("Cannot confirm which swarm this server is in: " + e.getMessage());
        }
        if (!own.equals(named)) { throw new InvalidParametersException("This server is in another swarm than '" + named + "'"); }
    }

    private Map<String, Object> syncRoot(Object swarm, Object root) throws InvalidParametersException {
        requireReplication();
        requireSameSwarm(swarm);
        if (!(root instanceof String theirs)) { throw new InvalidParametersException("Parameter 'root' must be text"); }
        var answer = responder.root(theirs);
        return reply("equal", answer.equal(), "root", answer.root());
    }

    private Map<String, Object> syncDigest(Object swarm, Object after, Object upTo, Object rows) throws InvalidParametersException {
        requireReplication();
        requireSameSwarm(swarm);
        if (after != null && !(after instanceof String)) { throw new InvalidParametersException("Parameter 'after' must be a key or null"); }
        if (upTo != null && !(upTo instanceof String)) { throw new InvalidParametersException("Parameter 'upTo' must be a key or null"); }
        if (!(rows instanceof List<?> items)) { throw new InvalidParametersException("Parameter 'rows' must be an array"); }
        var parsed = new ArrayList<VersionedStore.DigestRow>();
        int position = 0;
        for (var item : items) {
            var row = KvWire.parseRow(item, ++position);
            if (!parsed.isEmpty() && row.key().compareTo(parsed.getLast().key()) <= 0) {
                throw new InvalidParametersException("Rows must be in key order with no key twice, but row " + position + " (" + row.key() + ") is not");
            }
            parsed.add(row);
        }
        var answer = responder.digest(new SyncResponder.DigestRequest((String) after, (String) upTo, parsed));
        var wire = new ArrayList<Map<String, Object>>();
        try { for (var record : answer.records()) { wire.add(KvWire.toWire(record)); } }
        catch (IOException e) { throw new IllegalStateException("A stored value is not JSON", e); }
        return reply("records", wire, "want", answer.want(), "continueAfter", answer.continueAfter());
    }

    /** Whether replication is on, what the store holds, and what replication has done so far. */
    private Map<String, Object> status() {
        return reply("replication", config.replicate() ? "on" : "off", "pairs", store.liveCount(),
                "deleted", store.deletedCount(), "counters", counters.toMap(), "root", store.root(), "sync", syncStats.toMap());
    }

    /** A reply whose fields stay in the order given, which is the order a reader sees them in. */
    private static Map<String, Object> reply(Object... namesAndValues) {
        var reply = new LinkedHashMap<String, Object>();
        for (int i = 0; i < namesAndValues.length; i += 2) { reply.put((String) namesAndValues[i], namesAndValues[i + 1]); }
        return reply;
    }

    private String key(Map<String, Object> parameters) throws InvalidParametersException {
        String key = PluginParameters.string(parameters, KEY);
        if (key.isEmpty() || key.length() > config.maxKeyLength()) {
            throw new InvalidParametersException("Parameter 'key' must have 1 to " + config.maxKeyLength() + " characters");
        }
        return key;
    }

    private String encode(Object value) throws InvalidParametersException {
        String json;
        try { json = Json.write(value); }
        catch (IOException e) { throw new InvalidParametersException("Parameter 'value' must be a JSON value"); }
        if (json.getBytes(StandardCharsets.UTF_8).length > config.maxValueBytes()) {
            throw new InvalidParametersException("Parameter 'value' exceeds " + config.maxValueBytes() + " bytes of JSON");
        }
        return json;
    }

    /** Reads back JSON this store wrote itself, so it cannot fail. */
    private static Object decode(String json) {
        try { return Json.value(Json.read(json)); }
        catch (IOException e) { throw new UncheckedIOException("Stored value is not JSON", e); }
    }
}
