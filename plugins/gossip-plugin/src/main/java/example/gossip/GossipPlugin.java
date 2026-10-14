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

import dev.mars.plugin.CommandPlugin;
import dev.mars.plugin.InvalidParametersException;
import dev.mars.plugin.PluginContext;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Local command handlers and explicitly initiated peer exchanges; scheduling is not implemented yet. */
public final class GossipPlugin implements CommandPlugin {
    private final GossipConfig suppliedConfig;
    private volatile Started started;
    private boolean startupAttempted;
    private volatile boolean stopped;

    /** ServiceLoader constructor; configuration is read when this instance starts. */
    public GossipPlugin() { suppliedConfig = null; }

    /** Explicit per-instance configuration for embedded applications and tests. */
    public GossipPlugin(GossipConfig config) { suppliedConfig = Objects.requireNonNull(config, "config"); }

    @Override public String instruction() { return "gossip"; }

    @Override public synchronized void start(PluginContext context) throws Exception {
        if (startupAttempted || stopped) { throw new IllegalStateException("Gossip plugin cannot be restarted"); }
        startupAttempted = true;
        Objects.requireNonNull(context, "context");
        GossipConfig config = suppliedConfig == null
                ? GossipConfig.fromSystemProperty(context.serverId()) : suppliedConfig;
        var self = new MemberRecord(new MemberKey(context.serverId(), UUID.randomUUID()),
                config.advertiseHost(), context.port(), 0, MemberRecord.State.ALIVE, Map.of());
        var table = new MembershipTable(config, self);
        var metrics = new GossipMetrics();
        var transport = new GossipTransport(config, table, metrics);
        try { started = new Started(transport, new GossipCommands(config, table, metrics, transport)); }
        catch (RuntimeException e) { transport.close(); throw e; }
    }

    /** Cancel outbound I/O and retain the membership table for accepted handlers. */
    @Override public synchronized void stop() throws IOException {
        stopped = true;
        if (started != null) { started.transport().close(); }
    }

    /** For embedded callers and the future scheduler. Never invoked by an incoming command handler. */
    public GossipWire.Exchange exchangeWith(GossipConfig.Seed peer) throws IOException {
        Started local = started;
        if (local == null) { throw new IOException("Gossip plugin has not started successfully"); }
        return local.transport().exchange(peer);
    }

    @Override public Object execute(Map<String, Object> parameters) throws InvalidParametersException {
        Started local = started;
        if (local == null) { throw new IllegalStateException("Gossip plugin has not started successfully"); }
        return local.commands().execute(parameters, stopped);
    }

    private record Started(GossipTransport transport, GossipCommands commands) { }
}
