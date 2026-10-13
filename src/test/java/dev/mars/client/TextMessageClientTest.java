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
package dev.mars.client;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mars.protocol.CommandError;
import dev.mars.protocol.CommandReply;
import dev.mars.protocol.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class TextMessageClientTest {
    private static final UUID MESSAGE_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String SERVER_ID = "Server-001";

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "hello\nshutdown", "hello\rshutdown"})
    void rejectsEmptyAndMultilineMessagesBeforeConnecting(String message) {
        assertThrows(IllegalArgumentException.class, () -> new TextMessageClient("localhost", 1).send(message));
    }

    @Test
    void rejectsNullMessageIdBeforeConnecting() {
        assertThrows(IllegalArgumentException.class, () -> new TextMessageClient("localhost", 1).send(null, "Hello"));
    }

    @Test
    void rejectsReplyForAnotherMessage() throws Exception {
        var wrong = new CommandReply(1, UUID.randomUUID(), SERVER_ID, 1, "OK", 30, null);
        IOException error = assertThrows(IOException.class, () -> exchangeReply(Json.write(wrong), null));
        assertTrue(error.getMessage().contains("Message ID mismatch"));
    }

    @Test
    void rejectsUnexpectedServerId() throws Exception {
        IOException error = assertThrows(IOException.class, () -> exchangeReply(success(30), "Server-002"));
        assertTrue(error.getMessage().contains("Server ID mismatch"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"30", "\"A different plugin result\"", "true", "null", "[1,2]", "{\"answer\":42}"})
    void acceptsGenericResultsWithoutRequiringEchoText(String value) throws Exception {
        Object result = Json.value(Json.read(value));
        var reply = exchangeReply(success(result), SERVER_ID);
        assertEquals(Json.read(value), Json.read(Json.write(reply.result())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"version-missing", "version-unknown", "version-fraction", "id-missing", "id-invalid",
            "id-null", "server-missing", "server-invalid", "connection-missing", "connection-zero",
            "connection-negative", "connection-fraction", "connection-overflow", "status-invalid", "result-missing", "error-on-success"})
    void rejectsMalformedJsonReplies(String mutation) throws Exception {
        ObjectNode root = (ObjectNode) Json.read(success(30));
        switch (mutation) {
            case "version-missing" -> root.remove("version");
            case "version-unknown" -> root.put("version", 2);
            case "version-fraction" -> root.put("version", 1.5);
            case "id-missing" -> root.remove("messageId");
            case "id-invalid" -> root.put("messageId", "not-a-uuid");
            case "id-null" -> root.putNull("messageId");
            case "server-missing" -> root.remove("serverId");
            case "server-invalid" -> root.put("serverId", "bad|id");
            case "connection-missing" -> root.remove("connectionId");
            case "connection-zero" -> root.put("connectionId", 0);
            case "connection-negative" -> root.put("connectionId", -1);
            case "connection-fraction" -> root.put("connectionId", 1.5);
            case "connection-overflow" -> root.put("connectionId", new java.math.BigInteger("999999999999999999999"));
            case "status-invalid" -> root.put("status", "MAYBE");
            case "result-missing" -> root.remove("result");
            case "error-on-success" -> root.putObject("error").put("code", "PLUGIN_ERROR").put("message", "bad");
            default -> fail("Unknown mutation");
        }
        IOException error = assertThrows(IOException.class, () -> exchangeReply(Json.write(root), null));
        assertTrue(error.getMessage().contains("Invalid message reply"), error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Received: Hello", "{", "[]", "{} {}"})
    void rejectsNonProtocolReplies(String reply) {
        IOException error = assertThrows(IOException.class, () -> exchangeReply(reply, null));
        assertTrue(error.getMessage().contains("Invalid message reply"));
    }

    @Test
    void exposesCorrelatedStructuredServerErrors() throws Exception {
        var reply = CommandReply.failure(MESSAGE_ID, SERVER_ID, 1, CommandError.Code.INVALID_PARAMETERS, "Missing a");
        var error = assertThrows(CommandExecutionException.class, () -> exchangeReply(Json.write(reply), SERVER_ID));
        assertEquals(MESSAGE_ID, error.reply().messageId());
        assertEquals(CommandError.Code.INVALID_PARAMETERS, error.reply().error().code());
    }

    @Test
    void reportsServerClosingWithoutReplying() {
        IOException error = assertThrows(IOException.class, () -> exchangeReply(null, null));
        assertTrue(error.getMessage().contains("without replying"));
    }

    private String success(Object result) throws IOException {
        return Json.write(new CommandReply(1, MESSAGE_ID, SERVER_ID, 1, "OK", result, null));
    }

    private CommandReply exchangeReply(String reply, String expectedServerId) throws Exception {
        try (var listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             var responder = Executors.newVirtualThreadPerTaskExecutor()) {
            listener.setSoTimeout(5000);
            var task = responder.submit(() -> {
                try (var socket = listener.accept();
                     var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                     var writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {
                    socket.setSoTimeout(5000);
                    var request = Json.read(reader.readLine());
                    assertEquals(1, request.get("version").intValue());
                    assertEquals(MESSAGE_ID.toString(), request.get("messageId").textValue());
                    assertEquals("math.add", request.get("instruction").textValue());
                    assertEquals(10, request.get("parameters").get("a").intValue());
                    if (reply != null) { writer.println(reply); }
                }
                return null;
            });
            try {
                return new TextMessageClient("127.0.0.1", listener.getLocalPort(), expectedServerId)
                        .execute(MESSAGE_ID, "math.add", Map.of("a", 10, "b", 20));
            } finally { task.get(5, TimeUnit.SECONDS); }
        }
    }
}
