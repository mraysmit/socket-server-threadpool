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

import dev.mars.plugin.CommandPlugin;
import dev.mars.plugin.InvalidParametersException;
import dev.mars.plugin.PluginContext;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** One membership table, bounded peer transport, and scheduled seed-based runtime per provider instance. */
public final class GossipPlugin implements CommandPlugin {
    private final GossipConfig suppliedConfig;
    private final boolean schedule;
    private volatile Started started;
    private boolean startupAttempted;
    private volatile boolean stopped;

    /** ServiceLoader constructor; configuration is read when this instance starts. */
    public GossipPlugin() { suppliedConfig = null; schedule = true; }

    /** Explicit per-instance configuration for embedded applications and tests. */
    public GossipPlugin(GossipConfig config) { this(config, true); }

    /** Command/transport tests can control exchanges without introducing timer races. */
    GossipPlugin(GossipConfig config, boolean schedule) {
        suppliedConfig = Objects.requireNonNull(config, "config");
        this.schedule = schedule;
    }

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
        GossipRuntime runtime = null;
        try {
            runtime = new GossipRuntime(config, table, transport, metrics);
            var commands = new GossipCommands(config, table, metrics, transport, runtime);
            started = new Started(transport, commands, runtime);
            if (schedule) { runtime.start(); }
            else { runtime.manual(); }
        } catch (IOException | RuntimeException | Error e) {
            try { if (runtime == null) { transport.close(); } else { runtime.close(); } }
            catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    /** Cancel owned rounds, departure, DNS/TCP work, and retain the table for accepted handlers. */
    @Override public synchronized void stop() throws IOException {
        stopped = true;
        if (started != null) { started.runtime().close(); }
    }

    /** For embedded callers. Never invoked by an incoming command handler. */
    public GossipWire.Exchange exchangeWith(GossipConfig.Seed peer) throws IOException {
        Started local = started;
        if (local == null) { throw new IOException("Gossip plugin has not started successfully"); }
        local.runtime().requireActiveExchange();
        return local.transport().exchange(peer);
    }

    @Override public Object execute(Map<String, Object> parameters) throws InvalidParametersException {
        Started local = started;
        if (local == null) { throw new IllegalStateException("Gossip plugin has not started successfully"); }
        return local.commands().execute(parameters);
    }

    private record Started(GossipTransport transport, GossipCommands commands, GossipRuntime runtime) { }
}
