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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public class ShutdownMonitor implements Runnable {
    protected final int port;
    protected final ThreadPooledServer server;
    protected volatile ServerSocket serverSocket;
    protected final AtomicBoolean running = new AtomicBoolean(true);
    private Socket activeClient;

    public ShutdownMonitor(int port, ThreadPooledServer server) {
        ServerOptions.validatePort(port, "Admin port");
        this.port = port;
        this.server = server;
    }

    public synchronized void bind() throws IOException {
        if (!running.get()) {
            throw new IOException("Shutdown monitor has already been stopped");
        }
        if (serverSocket == null) {
            serverSocket = new ServerSocket(port);
        }
    }

    public synchronized int getPort() {
        return serverSocket == null ? port : serverSocket.getLocalPort();
    }

    @Override
    public void run() {
        try {
            synchronized (this) {
                if (!running.get()) { return; }
                bind();
            }
            System.out.println("Shutdown monitor listening on port " + getPort() + " | serverId=" + server.getServerId());
            while (running.get()) {
                try {
                    Socket client = serverSocket.accept();
                    synchronized (this) {
                        if (!running.get()) {
                            client.close();
                            break;
                        }
                        activeClient = client;
                    }
                    try (client;
                         var in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                         var out = new PrintWriter(client.getOutputStream(), true, StandardCharsets.UTF_8)) {
                        client.setSoTimeout(10000);
                        String command = in.readLine();
                        System.out.println("Command received: " + command);
                        if ("shutdown".equalsIgnoreCase(command)) {
                            out.println("Shutting down server...");
                            stop();
                            server.stop();
                            System.out.println("Server stopped by ShutdownMonitor");
                        } else if ("status".equalsIgnoreCase(command)) {
                            out.println("Server status: " + (server.isStopped() ? "STOPPED" : "ALIVE"));
                        } else {
                            out.println("Unknown command. Available commands: status, shutdown");
                        }
                    } finally {
                        synchronized (this) { activeClient = null; }
                    }
                } catch (IOException e) {
                    if (running.get()) {
                        System.err.println("Admin connection failed: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot start shutdown monitor", e);
        } finally {
            stop();
        }
    }

    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            try {
                if (serverSocket != null) { serverSocket.close(); }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot close admin listener", e);
            } finally {
                if (activeClient != null) {
                    try { activeClient.close(); }
                    catch (IOException e) { throw new UncheckedIOException("Cannot close admin client", e); }
                }
            }
        }
    }
}
