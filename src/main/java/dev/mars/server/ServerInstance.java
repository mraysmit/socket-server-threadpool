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

import java.io.IOException;
import java.net.BindException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import dev.mars.plugin.PluginRegistry;
import dev.mars.plugin.PluginContext;
import java.io.UncheckedIOException;

/** Owns both listeners and starts them only after both ports have been bound. */
public final class ServerInstance implements AutoCloseable {
    private final ThreadPooledServer server;
    private final ShutdownMonitor monitor;
    private final ExecutorService listeners;
    private Future<?> serverTask;
    private Future<?> monitorTask;
    private final PluginRegistry plugins;
    private final Object closeLock = new Object();
    private boolean stopRequested;
    private boolean closed;
    private RuntimeException stopFailure;

    private ServerInstance(ServerOptions options, PluginRegistry plugins) {
        this.plugins = plugins;
        server = new ThreadPooledServer(options.port(), options.serverId(), plugins);
        monitor = new ShutdownMonitor(options.adminPort(), server, this::requestStop);
        listeners = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("listener-" + options.serverId() + "-", 0).factory());
    }

    public static ServerInstance start(ServerOptions options) throws IOException {
        var plugins = PluginRegistry.loadDirectories(options.pluginDirectories());
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
            plugins.start(new PluginContext(instance.getServerId(), instance.getPort(), instance.getAdminPort()));
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
    ShutdownMonitor monitor() { return monitor; }
    Future<?> serverTask() { return serverTask; }
    Future<?> monitorTask() { return monitorTask; }

    /** Quiesce plugins and close listeners without joining the calling listener task. */
    public synchronized void requestStop() {
        if (stopRequested) {
            if (stopFailure != null) { throw stopFailure; }
            return;
        }
        stopRequested = true;
        RuntimeException failure = null;
        try { plugins.stop(); }
        catch (IOException e) { failure = new UncheckedIOException("Cannot stop plugins", e); }
        finally {
            try { failure = attempt(failure, monitor::stop); }
            finally {
                failure = attempt(failure, server::stop);
                stopFailure = failure;
            }
        }
        if (failure != null) { throw failure; }
    }

    @Override
    public void close() {
        // Never hold the instance monitor while joining: admin shutdown also calls requestStop().
        synchronized (closeLock) {
            if (closed) { return; }
            closed = true;
            RuntimeException failure = null;
            try { failure = attempt(failure, this::requestStop); }
            finally {
                try { failure = attempt(failure, listeners::close); }
                finally {
                    try { failure = attempt(failure, server.requestExecutor::close); }
                    finally {
                        failure = attempt(failure, () -> {
                            try { plugins.close(); }
                            catch (IOException e) { throw new UncheckedIOException("Cannot close plugin loader", e); }
                        });
                    }
                }
            }
            if (failure != null) { throw failure; }
        }
    }

    private static RuntimeException attempt(RuntimeException failure, Runnable cleanup) {
        try { cleanup.run(); }
        catch (RuntimeException e) {
            if (failure == null) { return e; }
            if (failure != e) { failure.addSuppressed(e); }
        }
        return failure;
    }
}
