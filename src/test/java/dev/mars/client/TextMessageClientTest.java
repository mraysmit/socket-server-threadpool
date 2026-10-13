package dev.mars.client;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class TextMessageClientTest {
    private static final UUID MESSAGE_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SERVER_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "hello\nshutdown", "hello\rshutdown"})
    void rejectsEmptyAndMultilineMessagesBeforeConnecting(String message) {
        var client = new TextMessageClient("127.0.0.1", 1);
        assertThrows(IllegalArgumentException.class, () -> client.send(message));
    }

    @Test
    void rejectsNullMessageIdBeforeConnecting() {
        var client = new TextMessageClient("127.0.0.1", 1);
        assertThrows(IllegalArgumentException.class, () -> client.send(null, "Hello"));
    }

    @Test
    void rejectsReplyForAnotherMessage() throws Exception {
        assertRejectedReply("reply " + UUID.randomUUID() + " " + SERVER_ID + " 1 Received: Hello",
                "Message ID mismatch");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Received: Hello",
            "reply",
            "reply {messageId} {serverId} Received: Hello",
            "reply not-a-uuid {serverId} 1 Received: Hello",
            "reply {messageId} not-a-uuid 1 Received: Hello",
            "reply {messageId} {serverId} invalid Received: Hello",
            "reply {messageId} {serverId} 0 Received: Hello",
            "reply {messageId} {serverId} -1 Received: Hello"
    })
    void rejectsMissingAndMalformedReplyIds(String reply) throws Exception {
        assertRejectedReply(reply.replace("{messageId}", MESSAGE_ID.toString())
                .replace("{serverId}", SERVER_ID.toString()), "Invalid message reply");
    }

    @Test
    void rejectsIncorrectTextEvenWithMatchingMessageId() throws Exception {
        assertRejectedReply("reply " + MESSAGE_ID + " " + SERVER_ID + " 1 Received: another message",
                "Reply text does not match");
    }

    @Test
    void reportsCorrelatedServerErrors() throws Exception {
        assertRejectedReply("error " + MESSAGE_ID + " " + SERVER_ID + " 1 Cannot process message",
                "Server rejected message");
    }

    @Test
    void reportsServerClosingWithoutReplying() throws Exception {
        assertRejectedReply(null, "without replying");
    }

    private void assertRejectedReply(String reply, String expectedError) throws Exception {
        try (var listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             var responder = Executors.newVirtualThreadPerTaskExecutor()) {
            listener.setSoTimeout(5000);
            var task = responder.submit(() -> {
                try (var socket = listener.accept();
                     var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                     var writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {
                    socket.setSoTimeout(5000);
                    assertEquals("message " + MESSAGE_ID + " Hello", reader.readLine());
                    if (reply != null) {
                        writer.println(reply);
                    }
                }
                return null;
            });
            var client = new TextMessageClient("127.0.0.1", listener.getLocalPort());
            IOException error = assertThrows(IOException.class, () -> client.send(MESSAGE_ID, "Hello"));
            assertTrue(error.getMessage().contains(expectedError), error.getMessage());
            task.get(5, TimeUnit.SECONDS);
        }
    }
}
