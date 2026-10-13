package dev.mars.client;

import java.util.ArrayList;
import java.util.HashSet;

public record ClientOptions(String host, int port, String expectedServerId, String message) {
    public static final String USAGE = "Usage: TextMessageClient [--host <host>] [--port <1-65535>] [--expected-server-id <id>] [--] <message>";

    public ClientOptions {
        host = normalizeHost(host);
        validatePort(port);
        expectedServerId = normalizeExpectedServerId(expectedServerId);
        if (message == null || message.isBlank() || message.indexOf('\n') >= 0 || message.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Message must be a nonblank single line");
        }
    }

    static String normalizeHost(String host) {
        if (host == null || host.isBlank()) { throw new IllegalArgumentException("Host must not be blank"); }
        return host.strip();
    }

    static void validatePort(int port) {
        if (port < 1 || port > 65535) { throw new IllegalArgumentException("Client port must be between 1 and 65535"); }
    }

    static String normalizeExpectedServerId(String id) {
        if (id == null || id.isBlank()) { return null; }
        String selected = id.strip();
        if (!selected.matches("[A-Za-z0-9._-]+")) { throw new IllegalArgumentException("Invalid expected server ID"); }
        return selected;
    }

    public static ClientOptions parse(String... args) {
        String host = "localhost";
        int port = 9000;
        String expected = null;
        boolean textOnly = false;
        var words = new ArrayList<String>();
        var seen = new HashSet<String>();
        for (int i = 0; i < args.length; i++) {
            String option = args[i];
            if (!textOnly && option.equals("--")) { textOnly = true; continue; }
            if (textOnly || !option.startsWith("--")) { words.add(option); continue; }
            if (!(option.equals("--host") || option.equals("--port") || option.equals("--expected-server-id"))) {
                throw new IllegalArgumentException("Unknown option: " + option + ". " + USAGE);
            }
            if (!seen.add(option)) { throw new IllegalArgumentException("Duplicate option: " + option); }
            if (++i == args.length || args[i].startsWith("--")) { throw new IllegalArgumentException("Missing value for " + option); }
            switch (option) {
                case "--host" -> host = args[i];
                case "--expected-server-id" -> expected = args[i];
                case "--port" -> {
                    try { port = Integer.parseInt(args[i]); }
                    catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid client port: " + args[i], e); }
                }
                default -> throw new AssertionError(option);
            }
        }
        return new ClientOptions(host, port, expected, String.join(" ", words));
    }
}
