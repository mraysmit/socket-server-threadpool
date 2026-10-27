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
package dev.mars.plugin;

/**
 * Resolved settings for one server instance; ports are bound TCP ports, never zero. {@code gate} says whether the server
 * is running or frozen; a plugin with work of its own waits on it before each piece of that work.
 */
public record PluginContext(String serverId, int port, int adminPort, RunGate gate) {
    public PluginContext {
        if (serverId == null || !serverId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Invalid server ID");
        }
        if (port < 1 || port > 65535 || adminPort < 1 || adminPort > 65535 || port == adminPort) {
            throw new IllegalArgumentException("Plugin context requires distinct bound ports");
        }
        if (gate == null) { throw new IllegalArgumentException("A run gate is required"); }
    }

    /** A server that is never frozen. */
    public PluginContext(String serverId, int port, int adminPort) { this(serverId, port, adminPort, new RunGate()); }
}
