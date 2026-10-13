package dev.mars.worker;

import dev.mars.http.HttpResponse;
import dev.mars.http.HttpResponseBuilder;
import dev.mars.http.HttpResponseWriter;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Worker class that handles individual client requests.
 * This class now follows SOLID principles by delegating HTTP response creation
 * to specialized classes, adhering to the Single Responsibility Principle.
 */
public class WorkerRunnable implements Runnable {
    private static final AtomicLong NEXT_CONNECTION_ID = new AtomicLong();
    private static final DateTimeFormatter LOG_TIMESTAMP = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss.SSS XXX", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    protected Socket clientSocket = null;
    protected String serverText = null;
    private final HttpResponseWriter responseWriter;
    private final long connectionId = NEXT_CONNECTION_ID.incrementAndGet();
    private final UUID serverId;
    private UUID messageId;

    public WorkerRunnable(Socket clientSocket, String serverText, UUID serverId) {
        this(clientSocket, serverText, serverId, new HttpResponseWriter());
    }

    /**
     * Constructor that allows dependency injection of HttpResponseWriter.
     * This follows the Dependency Inversion Principle.
     */
    public WorkerRunnable(Socket clientSocket, String serverText, UUID serverId, HttpResponseWriter responseWriter) {
        this.clientSocket = clientSocket;
        this.serverText = serverText;
        this.responseWriter = responseWriter;
        this.serverId = serverId;
    }

    @Override
    public void run() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter writer = new PrintWriter(clientSocket.getOutputStream(), true, StandardCharsets.UTF_8)) {

            logEvent("Client connected", Instant.now());
            clientSocket.setSoTimeout(10000);
            String request = reader.readLine();
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
                    writer.println("reply " + messageId + " " + serverId + " " + connectionId
                            + " Received: " + message);
                    logEvent("Message reply sent", Instant.now());
                }
                if (writer.checkError()) {
                    throw new IOException("Could not send message reply");
                }
                return;
            }
            // Consume the request headers before closing the socket to avoid a TCP reset.
            String line;
            do {
                line = reader.readLine();
                if (line == null) {
                    return;
                }
            } while (!line.isEmpty());

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
            // Log the exception with more context
            System.err.println("Error processing client request: " + e.getMessage());
            e.printStackTrace();

            // Try to send an error response
            try (PrintWriter errorWriter = new PrintWriter(clientSocket.getOutputStream(), true, StandardCharsets.UTF_8)) {
                responseWriter.writeInternalServerErrorResponse(errorWriter);
            } catch (IOException errorResponseException) {
                System.err.println("Failed to send error response: " + errorResponseException.getMessage());
            }
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
