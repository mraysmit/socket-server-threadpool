package dev.mars.plugin;

/** Resolved settings for one server instance; ports are bound TCP ports, never zero. */
public record PluginContext(String serverId, int port, int adminPort) {
    public PluginContext {
        if (serverId == null || !serverId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Invalid server ID");
        }
        if (port < 1 || port > 65535 || adminPort < 1 || adminPort > 65535 || port == adminPort) {
            throw new IllegalArgumentException("Plugin context requires distinct bound ports");
        }
    }
}
