package dev.mars.server;

import java.io.IOException;
import java.net.BindException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import dev.mars.plugin.PluginRegistry;
import java.io.UncheckedIOException;

/** Owns both listeners and starts them only after both ports have been bound. */
public final class ServerInstance implements AutoCloseable {
    private final ThreadPooledServer server;
    private final ShutdownMonitor monitor;
    private final ExecutorService listeners;
    private Future<?> serverTask;
    private Future<?> monitorTask;
    private final PluginRegistry plugins;

    private ServerInstance(ServerOptions options, PluginRegistry plugins) {
        this.plugins = plugins;
        server = new ThreadPooledServer(options.port(), options.serverId(), plugins);
        monitor = new ShutdownMonitor(options.adminPort(), server);
        listeners = Executors.newFixedThreadPool(2,
                Thread.ofPlatform().name("listener-" + options.serverId() + "-", 0).factory());
    }

    public static ServerInstance start(ServerOptions options) throws IOException {
        var plugins = PluginRegistry.load(options.pluginsDir());
        ServerInstance instance;
        try {
            instance = new ServerInstance(options, plugins);
        } catch (RuntimeException e) {
            try { plugins.close(); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
        try {
            // Reserve an explicit port before asking the OS to allocate the other one.
            if (options.port() == 0 && options.adminPort() != 0) {
                instance.bindAdmin(options.adminPort());
                instance.bindMain(options.port());
            } else {
                instance.bindMain(options.port());
                instance.bindAdmin(options.adminPort());
            }
            instance.serverTask = instance.listeners.submit(instance.server);
            instance.monitorTask = instance.listeners.submit(instance.monitor);
            System.out.println("Server ready | serverId=" + instance.getServerId()
                    + " | main=localhost:" + instance.getPort() + " | admin=localhost:" + instance.getAdminPort()
                    + " | instructions=" + plugins.instructions());
            return instance;
        } catch (IOException | RuntimeException e) {
            try { instance.close(); }
            catch (RuntimeException cleanupFailure) { e.addSuppressed(cleanupFailure); }
            throw e;
        }
    }

    private void bindMain(int port) throws IOException {
        try { server.bind(); }
        catch (IOException e) { throw startupFailure("Main", port, e); }
    }

    private void bindAdmin(int port) throws IOException {
        try { monitor.bind(); }
        catch (IOException e) { throw startupFailure("Admin", port, e); }
    }

    private static IOException startupFailure(String role, int port, IOException cause) {
        String message = cause instanceof BindException
                ? role + " port " + port + " is already in use or unavailable. Choose another port, or use 0 for automatic allocation."
                : "Cannot bind " + role.toLowerCase() + " port " + port + ": " + cause.getMessage();
        return new IOException(message, cause);
    }

    public String getServerId() { return server.getServerId(); }
    public int getPort() { return server.getPort(); }
    public int getAdminPort() { return monitor.getPort(); }
    ThreadPooledServer server() { return server; }
    Future<?> serverTask() { return serverTask; }
    Future<?> monitorTask() { return monitorTask; }

    @Override
    public void close() {
        try { monitor.stop(); }
        finally {
            try { server.stop(); }
            finally {
                try { listeners.close(); }
                finally {
                    try { server.requestExecutor.close(); }
                    finally {
                        try { plugins.close(); }
                        catch (IOException e) { throw new UncheckedIOException("Cannot close plugin loader", e); }
                    }
                }
            }
        }
    }
}
