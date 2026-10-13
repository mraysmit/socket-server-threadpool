package dev.mars.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Sends one UTF-8 text message and receives the server's reply. */
public class TextMessageClient {
    private final String host;
    private final int port;
    private final String expectedServerId;

    public TextMessageClient(String host, int port) {
        this(host, port, null);
    }

    public TextMessageClient(String host, int port, String expectedServerId) {
        this.host = ClientOptions.normalizeHost(host);
        ClientOptions.validatePort(port);
        this.port = port;
        this.expectedServerId = ClientOptions.normalizeExpectedServerId(expectedServerId);
    }

    public MessageReply send(String message) throws IOException {
        return send(UUID.randomUUID(), message);
    }

    public MessageReply send(UUID messageId, String message) throws IOException {
        if (messageId == null) {
            throw new IllegalArgumentException("Message ID must not be null");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Message must not be empty");
        }
        if (message.indexOf('\n') >= 0 || message.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Message must fit on one line");
        }

        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(10000);
            try (var writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
                 var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                writer.println("message " + messageId + " " + message);
                if (writer.checkError()) {
                    throw new IOException("Could not send message");
                }
                String reply = reader.readLine();
                if (reply == null) {
                    throw new IOException("Server closed the connection without replying");
                }
                return validateReply(reply, messageId, message);
            }
        }
    }

    private MessageReply validateReply(String reply, UUID expectedId, String message) throws IOException {
        String[] fields = reply.split(" ", 5);
        if (fields.length != 5 || !(fields[0].equals("reply") || fields[0].equals("error"))) {
            throw new IOException("Invalid message reply: missing IDs or reply fields");
        }
        MessageReply parsed;
        try {
            if (!fields[2].matches("[A-Za-z0-9._-]+")) {
                throw new IllegalArgumentException("Invalid server ID");
            }
            parsed = new MessageReply(UUID.fromString(fields[1]), fields[2],
                    Long.parseLong(fields[3]), fields[4]);
            if (parsed.connectionId() <= 0) {
                throw new IllegalArgumentException("Connection ID must be positive");
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid message reply: malformed IDs", e);
        }
        if (!expectedId.equals(parsed.messageId())) {
            throw new IOException("Message ID mismatch: sent " + expectedId + ", received " + parsed.messageId());
        }
        if (expectedServerId != null && !expectedServerId.equals(parsed.serverId())) {
            throw new IOException("Server ID mismatch: expected " + expectedServerId + ", received " + parsed.serverId());
        }
        if (fields[0].equals("error")) {
            throw new IOException("Server rejected message: " + parsed);
        }
        if (!parsed.text().equals("Received: " + message)) {
            throw new IOException("Reply text does not match the sent message " + expectedId);
        }
        return parsed;
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0 || (args.length == 1 && args[0].equals("--help"))) {
            System.out.println(ClientOptions.USAGE);
            return;
        }
        var options = ClientOptions.parse(args);
        String message = options.message();
        UUID messageId = UUID.randomUUID();
        System.out.println("Sending: messageId=" + messageId + " | text=" + message);
        System.out.println("Target: " + options.host() + ":" + options.port());
        System.out.println("Server response: " + new TextMessageClient(options.host(), options.port(),
                options.expectedServerId()).send(messageId, message));
    }
}
