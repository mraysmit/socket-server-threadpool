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
package dev.mars.plugins.multiply;

import dev.mars.client.CommandExecutionException;
import dev.mars.client.TextMessageClient;
import dev.mars.protocol.CommandReply;
import dev.mars.protocol.Json;
import dev.mars.testing.HostProcess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.jar.JarFile;

import static dev.mars.protocol.CommandError.Code.INVALID_PARAMETERS;
import static dev.mars.protocol.CommandError.Code.UNKNOWN_INSTRUCTION;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the packaged artifact through a server JVM with no example classes on its classpath. */
@Timeout(25)
class MultiplyPluginIT {
    private static final String SERVER_ID = "Server-001";
    private static final Duration WAIT = Duration.ofSeconds(10);
    @TempDir Path temporary;

    @Test
    void packagedPluginRegistersProviderWithoutBundlingHostApi() throws Exception {
        try (var jar = new JarFile(pluginJar().toFile())) {
            assertNotNull(jar.getEntry("dev/mars/plugins/multiply/MultiplyPlugin.class"));
            var provider = jar.getEntry("META-INF/services/dev.mars.plugin.CommandPlugin");
            assertNotNull(provider);
            try (var content = jar.getInputStream(provider)) {
                var providers = new String(content.readAllBytes(), StandardCharsets.UTF_8).lines()
                        .map(line -> line.split("#", 2)[0].strip()).filter(line -> !line.isEmpty()).distinct().toList();
                assertEquals(List.of("dev.mars.plugins.multiply.MultiplyPlugin"), providers);
            }
            assertNull(jar.getEntry("dev/mars/plugin/CommandPlugin.class"),
                    "The provided plugin API must come from the host");
        }
    }

    @Test
    void multiplyIsUnavailableWithoutRuntimePluginDirectory() throws Exception {
        try (var server = HostProcess.console(SERVER_ID).start()) {
            var client = client(server);
            UUID id = UUID.randomUUID();
            var failure = assertThrows(CommandExecutionException.class,
                    () -> client.execute(id, "math.multiply", Map.of("a", 6, "b", 7)));
            assertFailure(failure, id, UNKNOWN_INSTRUCTION);
            assertCleanExit(server);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void packagedJarLoadsFromEitherRuntimeDirectoryAndReturnsCorrelatedResults(boolean secondFolder) throws Exception {
        Path plugins = copyPlugin();
        Path empty = Files.createDirectory(temporary.resolve("empty plugins"));
        try (var server = secondFolder ? HostProcess.console(SERVER_ID, empty, plugins).start()
                : HostProcess.console(SERVER_ID, plugins, empty).start()) {
            var client = client(server);
            var connections = new HashSet<Long>();
            String[][] cases = {
                    {"{\"a\":6,\"b\":7}", "42"},
                    {"{\"a\":0.1,\"b\":0.2}", "0.02"},
                    {"{\"a\":9223372036854775808,\"b\":2}", "18446744073709551616"},
                    {"{\"a\":-6,\"b\":7}", "-42"},
                    {"{\"a\":0,\"b\":7}", "0"}
            };
            for (String[] testCase : cases) {
                UUID id = UUID.randomUUID();
                var reply = client.execute(id, "math.multiply", Json.parameters(Json.read(testCase[0])));
                assertIdentity(reply, id);
                assertEquals("OK", reply.status());
                assertNull(reply.error());
                assertInstanceOf(Number.class, reply.result());
                assertEquals(0, new BigDecimal(testCase[1]).compareTo(new BigDecimal(reply.result().toString())),
                        () -> "Unexpected product: " + reply);
                assertTrue(connections.add(reply.connectionId()), "Each connection must have its own ID");
                System.out.println("Standalone plugin JAR test PASSED | instruction=math.multiply | " + reply);
            }
            assertCleanExit(server);
        }
        Files.delete(plugins.resolve(pluginJar().getFileName()));
    }

    @Test
    void invalidParametersReturnCorrelatedErrorsAndPluginRemainsUsable() throws Exception {
        try (var server = HostProcess.console(SERVER_ID, copyPlugin()).start()) {
            var client = client(server);
            for (String parameters : List.of("{}", "{\"a\":6}", "{\"b\":7}", "{\"a\":null,\"b\":7}",
                    "{\"a\":\"6\",\"b\":7}", "{\"a\":true,\"b\":7}", "{\"a\":[],\"b\":7}",
                    "{\"a\":6,\"b\":7,\"extra\":1}")) {
                UUID id = UUID.randomUUID();
                var failure = assertThrows(CommandExecutionException.class,
                        () -> client.execute(id, "math.multiply", Json.parameters(Json.read(parameters))), parameters);
                assertFailure(failure, id, INVALID_PARAMETERS);
                assertFalse(failure.reply().error().message().isBlank());
                System.out.println("Standalone plugin rejection verified | " + failure.reply());
            }
            UUID id = UUID.randomUUID();
            var reply = client.execute(id, "math.multiply", Map.of("a", 6, "b", 7));
            assertIdentity(reply, id);
            assertEquals("OK", reply.status());
            assertNull(reply.error());
            assertEquals(new java.math.BigInteger("42"), reply.result());
            assertCleanExit(server);
        }
    }

    private static void assertIdentity(CommandReply reply, UUID id) {
        assertEquals(1, reply.version());
        assertEquals(id, reply.messageId());
        assertEquals("Server-001", reply.serverId());
        assertTrue(reply.connectionId() > 0);
    }

    private static void assertFailure(CommandExecutionException failure, UUID id, dev.mars.protocol.CommandError.Code code) {
        var reply = failure.reply();
        assertIdentity(reply, id);
        assertEquals("ERROR", reply.status());
        assertNull(reply.result());
        assertNotNull(reply.error());
        assertEquals(code, reply.error().code());
    }

    private Path pluginJar() {
        String configured = System.getProperty("multiply.plugin.jar");
        assertNotNull(configured, "Use mvn verify so Failsafe supplies the packaged plugin path");
        Path plugin = Path.of(configured).toAbsolutePath();
        assertTrue(Files.isRegularFile(plugin), "Run mvn verify to package the plugin before testing");
        return plugin;
    }

    private Path copyPlugin() throws IOException {
        Path directory = Files.createDirectory(temporary.resolve("runtime plugins"));
        Files.copy(pluginJar(), directory.resolve(pluginJar().getFileName()));
        return directory;
    }

    private static TextMessageClient client(HostProcess server) throws IOException {
        return new TextMessageClient("localhost", server.awaitReady(SERVER_ID, WAIT).port(), SERVER_ID);
    }

    private static void assertCleanExit(HostProcess server) throws IOException {
        assertEquals(0, server.stop(WAIT), server.outputText());
    }
}
