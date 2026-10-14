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
import dev.mars.plugin.PluginParameters;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Initial provider scaffold: local identity and status; no background work or peer exchanges yet. */
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
        started = new Started(config, context, UUID.randomUUID());
    }

    /** Retain local identity for accepted handlers; this stage owns no sockets, executors, or workers. */
    @Override public synchronized void stop() { stopped = true; }

    @Override public Object execute(Map<String, Object> parameters) throws InvalidParametersException {
        PluginParameters.requireKeys(parameters, "operation");
        String operation = PluginParameters.string(parameters, "operation");
        if (!operation.equals("status")) {
            throw new InvalidParametersException("Gossip scaffold supports only operation 'status'; "
                    + "exchange, members, and leave are not implemented");
        }
        Started local = started;
        if (local == null) { throw new IllegalStateException("Gossip plugin has not started successfully"); }
        return Map.of("gossipVersion", 1, "swarmId", local.config().swarmId(),
                "self", Map.of("serverId", local.context().serverId(), "bootId", local.bootId().toString(),
                        "host", local.config().advertiseHost(), "port", local.context().port(),
                        "heartbeat", 0, "state", "ALIVE", "metadata", Map.of()),
                "workerState", stopped ? "STOPPED" : "NOT_SCHEDULED", "isolated", true,
                "implementationStage", "scaffold", "supportedOperations", List.of("status"));
    }

    private record Started(GossipConfig config, PluginContext context, UUID bootId) { }
}
