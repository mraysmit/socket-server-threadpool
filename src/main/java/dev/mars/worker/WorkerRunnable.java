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
package dev.mars.worker;

import dev.mars.http.HttpResponse;
import dev.mars.http.HttpResponseBuilder;
import dev.mars.http.HttpResponseWriter;
import dev.mars.plugin.PluginRegistry;
import dev.mars.protocol.CommandRequest;
import dev.mars.protocol.CommandReply;
import dev.mars.protocol.CommandProtocol;
import dev.mars.protocol.InvalidRequestException;
import dev.mars.protocol.Json;
import dev.mars.protocol.CommandError;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Worker class that handles individual client requests.
 * This class now follows SOLID principles by delegating HTTP response creation
 * to specialized classes, adhering to the Single Responsibility Principle.
 */
public class WorkerRunnable implements Runnable {
    /** Longest request or header line read before the connection is rejected. */
    public static final int MAX_LINE_LENGTH = 64 * 1024;
    /** A kept-open connection serves at most this many requests, then the server closes it; clients reconnect. */
    public static final int MAX_REQUESTS_PER_CONNECTION = 1000;
    /**
     * How long a kept-open connection may sit idle before the server closes it. It must outlast the gap between two
     * requests from the same client, or the client reconnects each time and keeps nothing; a gossip server contacts a
     * given peer only about once a minute in a swarm of a hundred. An idle connection costs one parked virtual thread.
     */
    public static final Duration DEFAULT_KEEP_ALIVE_IDLE = Duration.ofMinutes(5);
    private static final AtomicLong NEXT_CONNECTION_ID = new AtomicLong();
    private static final DateTimeFormatter LOG_TIMESTAMP = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss.SSS XXX", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    protected Socket clientSocket = null;
    protected String serverText = null;
    private final HttpResponseWriter responseWriter;
    private final long connectionId = NEXT_CONNECTION_ID.incrementAndGet();
    private final String serverId;
    private UUID messageId;
    private final PluginRegistry plugins;
    private final BooleanSupplier draining;
    private final Duration keepAliveIdle;
    /** Guards {@link #waiting}, so that closing an idle connection cannot hit one that has just begun a request. */
    private final Object stateLock = new Object();
    private boolean waiting;

    public WorkerRunnable(Socket clientSocket, String serverText, String serverId, PluginRegistry plugins) {
        this(clientSocket, serverText, serverId, new HttpResponseWriter(), plugins);
    }

    /**
     * Constructor that allows dependency injection of HttpResponseWriter.
     * This follows the Dependency Inversion Principle.
     */
    public WorkerRunnable(Socket clientSocket, String serverText, String serverId, HttpResponseWriter responseWriter,
                          PluginRegistry plugins) {
        this(clientSocket, serverText, serverId, responseWriter, plugins, () -> false, DEFAULT_KEEP_ALIVE_IDLE);
    }

    /**
     * @param draining reports that the server is stopping, so an idle kept-open connection ends at once
     * @param keepAliveIdle how long a kept-open connection may wait for its next request
     */
    public WorkerRunnable(Socket clientSocket, String serverText, String serverId, PluginRegistry plugins,
                          BooleanSupplier draining, Duration keepAliveIdle) {
        this(clientSocket, serverText, serverId, new HttpResponseWriter(), plugins, draining, keepAliveIdle);
    }

    private WorkerRunnable(Socket clientSocket, String serverText, String serverId, HttpResponseWriter responseWriter,
                           PluginRegistry plugins, BooleanSupplier draining, Duration keepAliveIdle) {
        this.clientSocket = clientSocket;
        this.serverText = serverText;
        this.responseWriter = responseWriter;
        this.serverId = serverId;
        this.plugins = plugins;
        this.draining = java.util.Objects.requireNonNull(draining, "draining");
        this.keepAliveIdle = java.util.Objects.requireNonNull(keepAliveIdle, "keepAliveIdle");
    }

    @Override
    public void run() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter writer = new PrintWriter(clientSocket.getOutputStream(), true, StandardCharsets.UTF_8)) {

            logEvent("Client connected", Instant.now());
            clientSocket.setSoTimeout(10000);
            var lines = new BoundedLineReader(reader);
            String request;
            try {
                request = lines.readLine();
            } catch (LineTooLongException e) {
                writer.println(Json.write(CommandReply.failure(null, serverId, connectionId,
                        CommandError.Code.INVALID_REQUEST, e.getMessage())));
                return;
            }
            if (request == null) {
                return;
            }
            if (request.equalsIgnoreCase("message") || request.regionMatches(true, 0, "message ", 0, 8)) {
                String[] fields = request.split(" ", 3);
                if (fields.length < 2) {
                    writer.println("Error: usage: message <messageId> <text>");
                    return;
                }
                try {
                    messageId = UUID.fromString(fields[1]);
                } catch (IllegalArgumentException e) {
                    writer.println("Error: invalid message ID; use a UUID");
                    return;
                }
                String message = fields.length == 3 ? fields[2] : "";
                if (message.isBlank()) {
                    writer.println("error " + messageId + " " + serverId + " " + connectionId
                            + " Message must not be empty");
                } else {
                    logEvent("Message received: " + message, Instant.now());
                    var reply = plugins.dispatch(new CommandRequest(1, messageId, "echo", Map.of("text", message)),
                            serverId, connectionId);
                    if (reply.error() != null) {
                        writer.println("error " + messageId + " " + serverId + " " + connectionId + " " + reply.error().message());
                    } else {
                        writer.println("reply " + messageId + " " + serverId + " " + connectionId + " " + reply.result());
                    }
                    logEvent("Message reply sent", Instant.now());
                }
                if (writer.checkError()) {
                    throw new IOException("Could not send message reply");
                }
                return;
            }
            if (!request.matches("[A-Z]+\\s+\\S+\\s+HTTP/[0-9.]+")) {
                serveCommands(request, lines, writer);
                return;
            }
            // Consume the request headers before closing the socket to avoid a TCP reset.
            String line;
            try {
                do {
                    line = lines.readLine();
                    if (line == null) {
                        return;
                    }
                } while (!line.isEmpty());
            } catch (LineTooLongException e) {
                logEvent("Rejected request: " + e.getMessage(), Instant.now());
                return;
            }

            long time = System.currentTimeMillis();

            // Log the request processing
            logEvent("Request processed", Instant.ofEpochMilli(time));

            // Create response content
            String responseBody = "WorkerRunnable: " + this.serverText + " - " + time;

            // Use the HttpResponseBuilder and HttpResponseWriter to create and send response
            HttpResponse response = new HttpResponseBuilder()
                    .status(200, "OK")
                    .contentType("text/plain; charset=UTF-8")
                    .body(responseBody)
                    .build();

            responseWriter.writeResponse(response, writer);

        } catch (IOException e) {
            // Timeouts, resets and interrupted reads are client-side aborts; the socket is already unusable.
            System.err.println("Request aborted: " + e.getMessage() + " | connectionId=" + connectionId
                    + " | serverId=" + serverId);
        } finally {
            try {
                if (clientSocket != null) {
                    clientSocket.close();
                }
            } catch (IOException e) {
                System.err.println("Error closing client socket: " + e.getMessage());
            }
        }
    }

    /**
     * Serves a command request and, while the client keeps asking for it with {@code "connection":"keep-alive"}, the
     * requests that follow on the same connection. Without that field the connection closes after one reply, as before.
     */
    private void serveCommands(String first, BoundedLineReader lines, PrintWriter writer) throws IOException {
        String request = first;
        for (int served = 1; request != null; served++) {
            if (!handleCommand(request, writer) || served >= MAX_REQUESTS_PER_CONNECTION) { return; }
            try {
                request = awaitNextRequest(lines);
            } catch (LineTooLongException e) {
                writer.println(Json.write(CommandReply.failure(null, serverId, connectionId,
                        CommandError.Code.INVALID_REQUEST, e.getMessage())));
                return;
            }
        }
    }

    /**
     * Waits for the next request on a kept-open connection. Returns null when the client closed, the connection has
     * been idle for the idle limit, or the server is stopping. A stopping server does not wait for this to notice:
     * it calls {@link #closeIfIdle()}, which closes the socket and ends the wait at once, so an idle connection never
     * delays shutdown and no polling is needed.
     */
    private String awaitNextRequest(BoundedLineReader lines) throws IOException {
        long left = TimeUnit.NANOSECONDS.toMillis(keepAliveIdle.toNanos());
        if (left <= 0) { return null; }
        synchronized (stateLock) {
            // A server that began stopping before this point does not sweep this worker, so check here under the lock.
            if (draining.getAsBoolean() || Thread.currentThread().isInterrupted()) { return null; }
            waiting = true;
        }
        try {
            clientSocket.setSoTimeout((int) Math.min(left, Integer.MAX_VALUE));
            return lines.readLine();
        } catch (SocketTimeoutException idleTooLong) {
            return null;
        } catch (SocketException endedWhileWaiting) {
            // Closed by closeIfIdle because the server is stopping, or reset by a client that is closing its idle
            // connections without a closing handshake. Nothing was in flight, so it is a normal end, not an error.
            return null;
        } finally {
            synchronized (stateLock) { waiting = false; }
        }
    }

    /**
     * Closes this connection if it is idle between requests, which ends its wait at once; a connection that is serving
     * a request is left to finish. Returns whether it was closed. Called when the server stops.
     *
     * <p>The close is a reset rather than a closing handshake. Nothing is in flight on an idle connection, so nothing is
     * lost, and a handshake would leave the closing side's port in TIME_WAIT for two minutes. A server that stops with
     * thousands of kept-open connections would otherwise fill the operating system's table of such ports.
     */
    public boolean closeIfIdle() {
        synchronized (stateLock) {
            if (!waiting) { return false; }
            try { clientSocket.setSoLinger(true, 0); } catch (IOException cannotReset) { /* an ordinary close then */ }
            try { clientSocket.close(); } catch (IOException alreadyClosed) { /* the wait ends either way */ }
            return true;
        }
    }

    /** Replies to one command request; returns whether the client asked to keep the connection open. */
    private boolean handleCommand(String text, PrintWriter writer) throws IOException {
        CommandReply reply;
        boolean keepAlive = false;
        try {
            var parsed = CommandProtocol.parse(text);
            var command = parsed.request();
            keepAlive = parsed.keepAlive();
            messageId = command.messageId();
            logEvent("Executing instruction: " + command.instruction(), Instant.now());
            reply = plugins.dispatch(command, serverId, connectionId);
        } catch (InvalidRequestException e) {
            messageId = e.messageId();
            reply = CommandReply.failure(messageId, serverId, connectionId, CommandError.Code.INVALID_REQUEST, e.getMessage());
        }
        writer.println(Json.write(reply));
        if (writer.checkError()) { throw new IOException("Could not send command reply"); }
        logEvent("Command reply sent: " + reply.status(), Instant.now());
        return keepAlive;
    }

    private static final class LineTooLongException extends IOException {
        LineTooLongException() { super("Request line exceeds " + MAX_LINE_LENGTH + " characters"); }
    }

    /**
     * Line reader that stops as soon as a line exceeds the limit instead of growing with the input. A read timeout
     * leaves the part of the line already read in place, so the next call carries on from it.
     */
    private static final class BoundedLineReader {
        private final Reader in;
        private final StringBuilder line = new StringBuilder();
        private boolean skipLineFeed;

        BoundedLineReader(Reader in) { this.in = in; }

        /** Terminators are \n, \r or \r\n, as for BufferedReader; returns null at end of stream. */
        String readLine() throws IOException {
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\n' && skipLineFeed) { skipLineFeed = false; continue; }
                skipLineFeed = false;
                if (c == '\n') { return take(); }
                if (c == '\r') { skipLineFeed = true; return take(); }
                if (line.length() == MAX_LINE_LENGTH) { throw new LineTooLongException(); }
                line.append((char) c);
            }
            return line.isEmpty() ? null : take();
        }

        private String take() {
            String text = line.toString();
            line.setLength(0);
            return text;
        }
    }

    private void logEvent(String event, Instant timestamp) {
        String host = clientSocket.getInetAddress().getHostAddress();
        String client = (host.contains(":") ? "[" + host + "]" : host) + ":" + clientSocket.getPort();
        Thread thread = Thread.currentThread();
        System.out.println(String.format(Locale.ROOT,
                "[%s] %s | connectionId=%d | client=%s | server=%s | serverId=%s | messageId=%s | thread=%d | virtual=%s",
                LOG_TIMESTAMP.format(timestamp), event, connectionId, client, serverText,
                serverId, messageId == null ? "-" : messageId,
                thread.threadId(), thread.isVirtual()));
    }
}
