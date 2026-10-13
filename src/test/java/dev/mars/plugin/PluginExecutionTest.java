package dev.mars.plugin;

import dev.mars.client.CommandExecutionException;
import dev.mars.client.TextMessageClient;
import dev.mars.protocol.Json;
import dev.mars.server.ServerInstance;
import dev.mars.server.ServerOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
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

import static dev.mars.protocol.CommandError.Code.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class PluginExecutionTest {
    @Test
    void commandsReturnResultsAndCorrelatedIdsFromEachServer() throws Exception {
        try (var first = ServerInstance.start(new ServerOptions("Server-001", 0, 0));
             var second = ServerInstance.start(new ServerOptions("Server-002", 0, 0))) {
            UUID id = UUID.randomUUID();
            for (var server : new ServerInstance[]{first, second}) {
                var client = new TextMessageClient("localhost", server.getPort(), server.getServerId());
                var reply = client.execute(id, "math.add", Map.of("a", 10, "b", 20));
                assertEquals(new BigInteger("30"), reply.result());
                assertEquals(id, reply.messageId());
                assertEquals(server.getServerId(), reply.serverId());
                assertTrue(reply.connectionId() > 0);
                assertEquals("你好\nworld 🌍", client.execute("text.concat",
                        Map.of("a", "你好\n", "b", "world 🌍")).result());
                System.out.println("Plugin test PASSED: " + reply);
            }
        }
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
            System.out.println("Concurrent plugin test PASSED: 100 correlated results verified");
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
