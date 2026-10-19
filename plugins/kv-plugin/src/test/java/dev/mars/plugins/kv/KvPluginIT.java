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
package dev.mars.plugins.kv;

import dev.mars.client.CommandExecutionException;
import dev.mars.client.TextMessageClient;
import dev.mars.protocol.CommandReply;
import dev.mars.protocol.Json;
import dev.mars.testing.HostProcess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.jar.JarFile;

import static dev.mars.protocol.CommandError.Code.INVALID_PARAMETERS;
import static dev.mars.protocol.CommandError.Code.UNKNOWN_INSTRUCTION;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the packaged plugin JAR inside real server processes, over the server's socket protocol. */
@Timeout(40)
class KvPluginIT {
    private static final Duration WAIT = Duration.ofSeconds(10);
    @TempDir Path temporary;

    @Test
    void packagedJarRegistersOnlyTheKvProviderAndUsesTheHostsApi() throws Exception {
        try (var jar = new JarFile(pluginJar().toFile())) {
            assertNotNull(jar.getEntry("dev/mars/plugins/kv/KvPlugin.class"));
            var provider = jar.getEntry("META-INF/services/dev.mars.plugin.CommandPlugin");
            assertNotNull(provider);
            try (var content = jar.getInputStream(provider)) {
                var providers = new String(content.readAllBytes(), StandardCharsets.UTF_8).lines()
                        .map(line -> line.split("#", 2)[0].strip()).filter(line -> !line.isEmpty()).toList();
                assertEquals(List.of("dev.mars.plugins.kv.KvPlugin"), providers);
            }
            assertNull(jar.getEntry("dev/mars/plugin/CommandPlugin.class"), "The plugin API must come from the host");
        }
    }

    @Test
    void kvIsUnavailableWhenThePluginIsNotLoaded() throws Exception {
        try (var server = HostProcess.console("Server-001").start()) {
            var client = client(server, "Server-001");
            UUID id = UUID.randomUUID();
            var failure = assertThrows(CommandExecutionException.class,
                    () -> client.execute(id, "kv", Map.of("operation", "list")));
            assertFailure(failure, id, "Server-001", UNKNOWN_INSTRUCTION);
            assertCleanExit(server);
        }
    }

    @Test
    void everyOperationWorksOverTheSocketForEveryJsonType() throws Exception {
        try (var server = HostProcess.console("Server-001", copyPlugin()).start()) {
            var client = client(server, "Server-001");
            String[][] cases = {
                    {"string", "\"blue\""},
                    {"integer", "-42"},
                    {"big integer", "123456789012345678901234567890"},
                    {"decimal", "3.141592653589793238462643383279"},
                    {"true", "true"},
                    {"false", "false"},
                    {"null", "null"},
                    {"object", "{\"name\":\"node\",\"ports\":[9000,9001],\"missing\":null,\"tags\":{\"live\":true}}"},
                    {"array", "[\"a\",1,null,false,[],{}]"},
                    {"unicode", "\"ünïcode € 你好\""}
            };
            for (String[] testCase : cases) {
                var put = command(client, "Server-001",
                        "{\"operation\":\"put\",\"key\":\"" + testCase[0] + "\",\"value\":" + testCase[1] + "}");
                assertEquals(Map.of("key", testCase[0], "created", true), put.result());
            }
            // A new connection sees the same store: it belongs to the server, not to a connection.
            var other = client(server, "Server-001");
            for (String[] testCase : cases) {
                var got = (Map<?, ?>) command(other, "Server-001",
                        "{\"operation\":\"get\",\"key\":\"" + testCase[0] + "\"}").result();
                assertEquals(true, got.get("found"), testCase[0]);
                assertEquals(Json.write(Json.value(Json.read(testCase[1]))), Json.write(got.get("value")), testCase[0]);
            }
            assertEquals(Map.of("key", "string", "created", false),
                    command(client, "Server-001", "{\"operation\":\"put\",\"key\":\"string\",\"value\":\"green\"}").result());
            assertEquals("green", ((Map<?, ?>) command(client, "Server-001",
                    "{\"operation\":\"get\",\"key\":\"string\"}").result()).get("value"));
            var listed = (Map<?, ?>) command(client, "Server-001", "{\"operation\":\"list\"}").result();
            assertEquals(cases.length, ((Number) listed.get("count")).intValue());
            assertEquals(List.of("array", "big integer", "decimal", "false", "integer", "null", "object", "string",
                    "true", "unicode"), listed.get("keys"));
            assertEquals(Map.of("key", "string", "deleted", true),
                    command(client, "Server-001", "{\"operation\":\"delete\",\"key\":\"string\"}").result());
            assertEquals(Map.of("key", "string", "found", false),
                    command(client, "Server-001", "{\"operation\":\"get\",\"key\":\"string\"}").result());
            System.out.println("KV plugin over the socket PASSED | " + listed);
            assertCleanExit(server);
        }
    }

    @Test
    void invalidCommandsReturnCorrelatedErrorsAndTheStoreStaysUsable() throws Exception {
        try (var server = HostProcess.console("Server-001", copyPlugin()).start()) {
            var client = client(server, "Server-001");
            for (String parameters : List.of("{}", "{\"operation\":\"rename\",\"key\":\"a\"}",
                    "{\"operation\":\"get\"}", "{\"operation\":\"get\",\"key\":7}", "{\"operation\":\"get\",\"key\":\"\"}",
                    "{\"operation\":\"put\",\"key\":\"a\"}", "{\"operation\":\"list\",\"extra\":1}",
                    "{\"operation\":\"put\",\"key\":\"" + "k".repeat(KvPlugin.MAX_KEY_LENGTH + 1) + "\",\"value\":1}",
                    "{\"operation\":\"put\",\"key\":\"big\",\"value\":\"" + "v".repeat(KvPlugin.MAX_VALUE_BYTES) + "\"}")) {
                UUID id = UUID.randomUUID();
                var failure = assertThrows(CommandExecutionException.class,
                        () -> client.execute(id, "kv", Json.parameters(Json.read(parameters))), parameters);
                assertFailure(failure, id, "Server-001", INVALID_PARAMETERS);
                assertFalse(failure.reply().error().message().isBlank());
            }
            command(client, "Server-001", "{\"operation\":\"put\",\"key\":\"a\",\"value\":1}");
            assertEquals(Map.of("keys", List.of("a"), "count", java.math.BigInteger.ONE),
                    command(client, "Server-001", "{\"operation\":\"list\"}").result());
            assertCleanExit(server);
        }
    }

    @Test
    void eachServerHasItsOwnStore() throws Exception {
        Path plugins = copyPlugin();
        try (var first = HostProcess.console("Server-001", plugins).start();
             var second = HostProcess.console("Server-002", plugins).start()) {
            var one = client(first, "Server-001");
            var two = client(second, "Server-002");
            command(one, "Server-001", "{\"operation\":\"put\",\"key\":\"owner\",\"value\":\"one\"}");
            assertEquals(Map.of("key", "owner", "found", false),
                    command(two, "Server-002", "{\"operation\":\"get\",\"key\":\"owner\"}").result());
            assertCleanExit(first);
            assertCleanExit(second);
        }
    }

    private static CommandReply command(TextMessageClient client, String serverId, String parameters) throws Exception {
        UUID id = UUID.randomUUID();
        var reply = client.execute(id, "kv", Json.parameters(Json.read(parameters)));
        assertEquals(id, reply.messageId());
        assertEquals(serverId, reply.serverId());
        assertEquals("OK", reply.status());
        assertNull(reply.error());
        return reply;
    }

    private static void assertFailure(CommandExecutionException failure, UUID id, String serverId,
            dev.mars.protocol.CommandError.Code code) {
        var reply = failure.reply();
        assertEquals(id, reply.messageId());
        assertEquals(serverId, reply.serverId());
        assertEquals("ERROR", reply.status());
        assertNull(reply.result());
        assertEquals(code, reply.error().code());
    }

    private Path pluginJar() {
        String configured = System.getProperty("kv.plugin.jar");
        assertNotNull(configured, "Use mvn verify so Failsafe supplies the packaged plugin path");
        Path plugin = Path.of(configured).toAbsolutePath();
        assertTrue(Files.isRegularFile(plugin), "Run mvn verify to package the plugin before testing");
        return plugin;
    }

    private Path copyPlugin() throws IOException {
        Path directory = Files.createDirectories(temporary.resolve("runtime plugins"));
        Path target = directory.resolve(pluginJar().getFileName());
        if (!Files.exists(target)) { Files.copy(pluginJar(), target); }
        return directory;
    }

    private static TextMessageClient client(HostProcess server, String serverId) throws IOException {
        return new TextMessageClient("localhost", server.awaitReady(serverId, WAIT).port(), serverId);
    }

    private static void assertCleanExit(HostProcess server) throws IOException {
        assertEquals(0, server.stop(WAIT), server.outputText());
    }
}
