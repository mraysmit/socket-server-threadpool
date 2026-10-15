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
package dev.mars.plugins.gossip.fixture;

import dev.mars.plugin.CommandPlugin;
import dev.mars.plugin.PluginRegistry;
import dev.mars.plugin.builtin.EchoPlugin;
import dev.mars.server.ServerInstance;
import dev.mars.server.ServerOptions;

import java.io.IOException;
import java.util.List;

/** Starts a host server with caller-supplied plugins plus the built-in echo plugin. */
public final class GossipServerFixture {
    private GossipServerFixture() { }
    public static ServerInstance start(String id, CommandPlugin plugin, CommandPlugin... additional) throws IOException {
        return start(id, 0, plugin, additional);
    }
    public static ServerInstance start(String id, int port, CommandPlugin plugin, CommandPlugin... additional) throws IOException {
        var providers = new java.util.ArrayList<CommandPlugin>(List.of(plugin, new EchoPlugin()));
        providers.addAll(List.of(additional));
        return ServerInstance.start(new ServerOptions(id, port, 0), PluginRegistry.of(providers));
    }
}
