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
package dev.mars.server;

import java.util.HashSet;
import java.util.UUID;
import java.nio.file.Path;

/** Validated startup options for one server instance. */
public record ServerOptions(String serverId, int port, int adminPort, Path pluginsDir) {
    public static final int DEFAULT_PORT = 9000;
    public static final int DEFAULT_ADMIN_PORT = 9001;
    public static final String USAGE = "Usage: StartStop [serverId] [--port <0-65535>] [--admin-port <0-65535>] [--server-id <id>] [--plugins-dir <directory>]";

    public ServerOptions(String serverId, int port, int adminPort) { this(serverId, port, adminPort, null); }

    public ServerOptions {
        serverId = resolveServerId(serverId);
        validatePort(port, "Main port");
        validatePort(adminPort, "Admin port");
        if (port != 0 && port == adminPort) {
            throw new IllegalArgumentException("Main and admin ports must be different (or use 0 for automatic allocation)");
        }
    }

    public static String resolveServerId(String id) {
        String selected = id == null || id.isBlank() ? UUID.randomUUID().toString() : id.strip();
        if (!selected.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Server ID must contain only letters, digits, dots, underscores, or hyphens");
        }
        return selected;
    }

    public static void validatePort(int port, String name) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException(name + " must be between 0 and 65535");
        }
    }

    public static ServerOptions parse(String... args) {
        String id = null;
        boolean hasId = false;
        int port = DEFAULT_PORT;
        int adminPort = DEFAULT_ADMIN_PORT;
        Path pluginsDir = null;
        var seen = new HashSet<String>();
        for (int i = 0; i < args.length; i++) {
            String option = args[i];
            if (!option.startsWith("--")) {
                if (hasId) {
                    throw new IllegalArgumentException("Server ID supplied more than once. " + USAGE);
                }
                id = option;
                hasId = true;
                continue;
            }
            if (!(option.equals("--port") || option.equals("--admin-port") || option.equals("--server-id") || option.equals("--plugins-dir"))) {
                throw new IllegalArgumentException("Unknown option: " + option + ". " + USAGE);
            }
            if (!seen.add(option)) {
                throw new IllegalArgumentException("Duplicate option: " + option);
            }
            if (++i == args.length || args[i].startsWith("--")) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            switch (option) {
                case "--server-id" -> {
                    if (hasId) {
                        throw new IllegalArgumentException("Server ID supplied more than once");
                    }
                    id = args[i];
                    hasId = true;
                }
                case "--port" -> port = parsePort(args[i], option);
                case "--admin-port" -> adminPort = parsePort(args[i], option);
                case "--plugins-dir" -> {
                    if (args[i].isBlank()) { throw new IllegalArgumentException("Plugin directory must not be blank"); }
                    pluginsDir = Path.of(args[i]);
                }
                default -> throw new AssertionError(option);
            }
        }
        return new ServerOptions(id, port, adminPort, pluginsDir);
    }

    private static int parsePort(String value, String option) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid port for " + option + ": " + value, e);
        }
    }
}
