package dev.mars.server;

import java.util.HashSet;
import java.util.UUID;

/** Validated startup options for one server instance. */
public record ServerOptions(String serverId, int port, int adminPort) {
    public static final int DEFAULT_PORT = 9000;
    public static final int DEFAULT_ADMIN_PORT = 9001;
    public static final String USAGE = "Usage: StartStop [serverId] [--port <0-65535>] [--admin-port <0-65535>] [--server-id <id>]";

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
            if (!(option.equals("--port") || option.equals("--admin-port") || option.equals("--server-id"))) {
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
                default -> throw new AssertionError(option);
            }
        }
        return new ServerOptions(id, port, adminPort);
    }

    private static int parsePort(String value, String option) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid port for " + option + ": " + value, e);
        }
    }
}
