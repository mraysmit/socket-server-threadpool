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
package dev.mars.plugin;

import dev.mars.client.CommandExecutionException;
import dev.mars.client.TextMessageClient;
import dev.mars.protocol.Json;
import dev.mars.server.ServerInstance;
import dev.mars.server.ServerOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static dev.mars.protocol.CommandError.Code.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class PluginExecutionTest {
    @ParameterizedTest(name = "{0} returns correlated results from two servers")
    @MethodSource("builtinCommands")
    void commandsReturnResultsAndCorrelatedIdsFromEachServer(String instruction,
            Map<String, Object> parameters, Object expectedResult) throws Exception {
        try (var first = ServerInstance.start(new ServerOptions("Server-001", 0, 0));
             var second = ServerInstance.start(new ServerOptions("Server-002", 0, 0))) {
            UUID id = UUID.randomUUID();
            for (var server : new ServerInstance[]{first, second}) {
                var client = new TextMessageClient("localhost", server.getPort(), server.getServerId());
                var reply = client.execute(id, instruction, parameters);
                assertEquals(expectedResult, reply.result());
                assertEquals(id, reply.messageId());
                assertEquals(server.getServerId(), reply.serverId());
                assertTrue(reply.connectionId() > 0);
                System.out.println("Plugin test PASSED | instruction=" + instruction
                        + " | parameters=" + Json.write(parameters) + " | " + reply);
            }
        }
    }

    static Stream<Arguments> builtinCommands() {
        return Stream.of(
                Arguments.of("math.add", Map.of("a", 10, "b", 20), BigInteger.valueOf(30)),
                Arguments.of("text.concat", Map.of("a", "你好\n", "b", "world 🌍"), "你好\nworld 🌍"),
                Arguments.of("echo", Map.of("text", "Hello plugin"), "Received: Hello plugin"));
    }

    @Test
    void concurrentCommandsAndErrorsLeaveServerOperational() throws Exception {
        try (var server = ServerInstance.start(new ServerOptions("Server-001", 0, 0));
             var clients = Executors.newVirtualThreadPerTaskExecutor()) {
            var client = new TextMessageClient("localhost", server.getPort(), "Server-001");
            var unknown = assertThrows(CommandExecutionException.class,
                    () -> client.execute("unknown", Map.of()));
            assertEquals(UNKNOWN_INSTRUCTION, unknown.reply().error().code());
            var invalid = assertThrows(CommandExecutionException.class,
                    () -> client.execute("math.add", Map.of("a", "ten", "b", 20)));
            assertEquals(INVALID_PARAMETERS, invalid.reply().error().code());
            var tasks = java.util.stream.IntStream.range(0, 100).mapToObj(i -> clients.submit(() -> {
                UUID id = UUID.randomUUID();
                var reply = client.execute(id, "math.add", Map.of("a", i, "b", 20));
                assertEquals(id, reply.messageId());
                assertEquals(BigInteger.valueOf(i + 20), reply.result());
                return reply.connectionId();
            })).toList();
            var connections = new java.util.HashSet<Long>();
            for (var task : tasks) { assertTrue(connections.add(task.get())); }
            assertEquals("Received: still healthy", client.send("still healthy").text());
            System.out.println("Concurrent plugin test PASSED | instruction=math.add | 100 correlated results verified");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "[]", "{}", "{\"version\":1,\"version\":1}", "{} {}",
            "{\"version\":2,\"messageId\":\"11111111-1111-4111-8111-111111111111\",\"instruction\":\"echo\",\"parameters\":{}}",
            "{\"version\":1,\"messageId\":\"11111111-1111-4111-8111-111111111111\",\"instruction\":\"echo\",\"parameters\":[]}",
            "{\"version\":1,\"messageId\":\"11111111-1111-4111-8111-111111111111\",\"instruction\":\"echo\",\"parameters\":{},\"extra\":1}"})
    void malformedRequestsReturnStructuredErrors(String payload) throws Exception {
        try (var server = ServerInstance.start(new ServerOptions("Server-001", 0, 0));
             var socket = new Socket("localhost", server.getPort())) {
            socket.setSoTimeout(5000);
            var writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer.println(payload);
            var reply = Json.read(reader.readLine());
            assertEquals("ERROR", reply.path("status").asText());
            assertEquals("INVALID_REQUEST", reply.path("error").path("code").asText());
            assertEquals("Server-001", reply.path("serverId").asText());
            assertTrue(reply.path("connectionId").asLong() > 0);
            if (payload.contains("11111111-1111-4111-8111-111111111111")) {
                assertEquals("11111111-1111-4111-8111-111111111111", reply.path("messageId").asText());
            }
        }
    }
}
