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

import dev.mars.worker.WorkerRunnable;
import dev.mars.plugin.PluginRegistry;

import java.net.ServerSocket;
import java.net.Socket;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ThreadPooledServer implements Runnable{
    private static final int CONNECTION_BACKLOG = 256;
    static final int DEFAULT_MAX_CONNECTIONS = 1024;
    static final Duration DEFAULT_DRAIN_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration ACCEPT_RETRY_DELAY = Duration.ofMillis(100);
    private final String serverId;
    private final PluginRegistry plugins;
    protected int          serverPort   = 8080;
    protected volatile ServerSocket serverSocket = null;
    protected final AtomicBoolean isStopped = new AtomicBoolean(false);
    protected volatile Thread runningThread = null;
    protected final ExecutorService requestExecutor;
    private volatile Duration drainTimeout = DEFAULT_DRAIN_TIMEOUT;
    private volatile Duration keepAliveIdle = WorkerRunnable.DEFAULT_KEEP_ALIVE_IDLE;
    /** Connections being served, so that stopping the server can close the ones waiting between requests. */
    private final java.util.Set<WorkerRunnable> workers = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // Virtual threads are cheap; sockets and memory are not. Excess clients wait in the kernel backlog.
    private volatile Semaphore connectionPermits = new Semaphore(DEFAULT_MAX_CONNECTIONS);
    final protected String SERVER_NAME = "ThreadPooledServer";

    public ThreadPooledServer(int port) {
        this(port, null);
    }

    public ThreadPooledServer(int port, String serverId) {
        this(port, serverId, null);
    }

    public ThreadPooledServer(int port, String serverId, PluginRegistry plugins) {
        this.serverId = ServerOptions.resolveServerId(serverId);
        ServerOptions.validatePort(port, "Main port");
        this.serverPort = port;
        try { this.plugins = plugins == null ? PluginRegistry.load(null) : plugins; }
        catch (IOException e) { throw new UncheckedIOException("Cannot load command plugins", e); }
        requestExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public void run(){
        try {
            synchronized(this){
                this.runningThread = Thread.currentThread();
                if (isStopped.get()) {
                    return;
                }
                bind();
            }
            System.out.println(SERVER_NAME + " started on port: " + getPort() + " | serverId=" + serverId);
            while(! isStopped()){
                Semaphore permits = connectionPermits;
                if (!acquireConnectionPermit(permits)) {
                    break;
                }
                Socket clientSocket;
                try {
                    clientSocket = this.serverSocket.accept();
                } catch (IOException e) {
                    permits.release();
                    if(isStopped()) {
                        System.out.println(SERVER_NAME + " Stopped.") ;
                        break;
                    }
                    if (this.serverSocket.isClosed()) {
                        throw new RuntimeException("Error accepting client connection", e);
                    }
                    // Transient failures such as descriptor exhaustion must not take the listener down.
                    System.err.println(SERVER_NAME + " accept failed; retrying | serverId=" + serverId
                            + " | reason=" + e.getMessage());
                    Thread.sleep(ACCEPT_RETRY_DELAY.toMillis());
                    continue;
                }
                var worker = new WorkerRunnable(clientSocket, SERVER_NAME, serverId, plugins, this::isStopped, keepAliveIdle);
                workers.add(worker);
                // A connection accepted just as the server stopped must not be missed by the sweep in stop().
                if (isStopped()) { worker.closeIfIdle(); }
                this.requestExecutor.execute(() -> {
                    try { worker.run(); }
                    finally { workers.remove(worker); permits.release(); }
                });
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot start " + SERVER_NAME, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                stop();
            } finally {
                // Wait for accepted requests before exiting, including after a bind failure.
                drain();
            }
            System.out.println(SERVER_NAME + " Closed.") ;
        }
    }

    /** Polls so that stop(), which only closes the listening socket, still ends the accept loop. */
    private boolean acquireConnectionPermit(Semaphore permits) throws InterruptedException {
        while (!isStopped()) {
            if (permits.tryAcquire(100, TimeUnit.MILLISECONDS)) {
                return true;
            }
        }
        return false;
    }

    /** Maximum concurrently serviced connections; applies to connections accepted from now on. */
    void maxConnections(int limit) {
        if (limit < 1) { throw new IllegalArgumentException("Connection limit must be positive"); }
        this.connectionPermits = new Semaphore(limit);
    }

    /** Bound on how long shutdown waits for accepted requests before interrupting their workers. */
    void drainTimeout(Duration timeout) {
        this.drainTimeout = Objects.requireNonNull(timeout, "timeout");
    }

    /** How long a connection that the client asked to keep open may wait for its next request; applies to later connections. */
    void keepAliveIdle(Duration idle) {
        this.keepAliveIdle = Objects.requireNonNull(idle, "idle");
    }

    /** Stop accepting work, wait up to the drain timeout, then interrupt whatever is still running. */
    void drain() {
        requestExecutor.shutdown();
        boolean interrupted = false;
        try {
            if (!requestExecutor.awaitTermination(drainTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
                System.err.println(SERVER_NAME + " drain timed out after " + drainTimeout.toMillis()
                        + " ms; interrupting remaining requests | serverId=" + serverId);
                // Interrupting a virtual thread closes the socket it is blocked on.
                requestExecutor.shutdownNow();
                if (!requestExecutor.awaitTermination(drainTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
                    System.err.println(SERVER_NAME + " requests ignored interruption | serverId=" + serverId);
                }
            }
        } catch (InterruptedException e) {
            requestExecutor.shutdownNow();
            interrupted = true;
        } finally {
            if (interrupted) { Thread.currentThread().interrupt(); }
        }
    }

    public boolean isStopped() {
        return this.isStopped.get();
    }

    public String getServerId() {
        return serverId;
    }

    public synchronized void stop(){
        this.isStopped.getAndSet(true);
        // Connections kept open between requests are idle most of the time; close them now so shutdown does not wait.
        // The flag is set first, so a worker that goes idle after this sweep sees it and closes itself.
        workers.forEach(WorkerRunnable::closeIfIdle);
        try {
            if (this.serverSocket != null) {
                this.serverSocket.close();
            }
        } catch (IOException e) {
            throw new RuntimeException("Error closing " + SERVER_NAME , e);
        }
    }

    public synchronized void bind() throws IOException {
        if (isStopped.get()) {
            throw new IOException("Main server has already been stopped");
        }
        if (serverSocket == null) {
            serverSocket = new ServerSocket(serverPort, CONNECTION_BACKLOG);
        }
    }

    public synchronized int getPort() {
        return serverSocket == null ? serverPort : serverSocket.getLocalPort();
    }
}
