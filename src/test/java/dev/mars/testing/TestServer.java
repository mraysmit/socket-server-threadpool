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
package dev.mars.testing;

import dev.mars.plugin.CommandPlugin;
import dev.mars.plugin.PluginRegistry;
import dev.mars.plugin.builtin.EchoPlugin;
import dev.mars.server.ServerInstance;
import dev.mars.server.ServerOptions;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Starts an in-process host server with caller-supplied plugin instances plus the built-in echo command. */
public final class TestServer {
    private TestServer() { }

    /** Uses automatic main and admin ports. */
    public static ServerInstance start(String serverId, CommandPlugin... plugins) throws IOException {
        return start(serverId, 0, plugins);
    }

    /** The returned instance owns the plugins: closing it stops them. */
    public static ServerInstance start(String serverId, int port, CommandPlugin... plugins) throws IOException {
        var providers = new ArrayList<CommandPlugin>(List.of(plugins));
        providers.add(new EchoPlugin());
        return ServerInstance.start(new ServerOptions(serverId, port, 0), PluginRegistry.of(providers));
    }
}
