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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.StartStop;
import dev.mars.client.CommandExecutionException;
import dev.mars.client.TextMessageClient;
import dev.mars.protocol.CommandReply;
import dev.mars.protocol.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static dev.mars.protocol.CommandError.Code.INVALID_PARAMETERS;
import static dev.mars.protocol.CommandError.Code.UNKNOWN_INSTRUCTION;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the packaged artifact through a server JVM with no example classes on its classpath. */
@Timeout(25)
class MultiplyPluginIT {
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
        try (var server = new ServerProcess()) {
            var client = server.client();
            UUID id = UUID.randomUUID();
            var failure = assertThrows(CommandExecutionException.class,
                    () -> client.execute(id, "math.multiply", Map.of("a", 6, "b", 7)));
            assertFailure(failure, id, UNKNOWN_INSTRUCTION);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void packagedJarLoadsFromEitherRuntimeDirectoryAndReturnsCorrelatedResults(boolean secondFolder) throws Exception {
        Path plugins = copyPlugin();
        Path empty = Files.createDirectory(temporary.resolve("empty plugins"));
        try (var server = secondFolder ? new ServerProcess(empty, plugins) : new ServerProcess(plugins, empty)) {
            var client = server.client();
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
        }
        Files.delete(plugins.resolve(pluginJar().getFileName()));
    }

    @Test
    void invalidParametersReturnCorrelatedErrorsAndPluginRemainsUsable() throws Exception {
        try (var server = new ServerProcess(copyPlugin())) {
            var client = server.client();
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

    private static String productionClasspath() {
        // Include only the server and its JSON dependencies. The provider must come from --plugins-dir.
        return List.of(StartStop.class, ObjectMapper.class, JsonFactory.class, JsonProperty.class)
                .stream().map(type -> {
                    try { return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
                    catch (Exception e) { throw new IllegalStateException(e); }
                }).distinct().collect(Collectors.joining(File.pathSeparator));
    }

    /** The child has no example classes; only the server discovers the copied runtime JAR. */
    private static final class ServerProcess implements AutoCloseable {
        private final Process process;
        private final ExecutorService reader = Executors.newVirtualThreadPerTaskExecutor();
        private final CompletableFuture<Integer> port = new CompletableFuture<>();
        private final Future<String> output;

        ServerProcess(Path... directories) throws IOException {
            Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
            if (!Files.exists(java)) { java = Path.of(System.getProperty("java.home"), "bin", "java"); }
            var command = new ArrayList<>(List.of(java.toString(), "-cp", productionClasspath(), "dev.mars.StartStop",
                    "Server-001", "--port", "0", "--admin-port", "0"));
            for (Path directory : directories) { command.add("--plugins-dir"); command.add(directory.toString()); }
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            output = reader.submit(() -> {
                var captured = new StringBuilder();
                var ready = Pattern.compile("Server ready \\| serverId=Server-001 \\| main=localhost:(\\d+)");
                try (var lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = lines.readLine()) != null) {
                        captured.append(line).append('\n');
                        System.out.println(line);
                        var match = ready.matcher(line);
                        if (match.find()) { port.complete(Integer.parseInt(match.group(1))); }
                    }
                    port.completeExceptionally(new IOException("Server exited before readiness:\n" + captured));
                    return captured.toString();
                } catch (IOException e) {
                    port.completeExceptionally(e);
                    throw e;
                }
            });
        }

        TextMessageClient client() throws Exception {
            return new TextMessageClient("localhost", port.get(10, TimeUnit.SECONDS), "Server-001");
        }

        @Override
        public void close() throws Exception {
            try {
                if (process.isAlive()) {
                    try (var input = process.getOutputStream()) { input.write("quit\n".getBytes(StandardCharsets.UTF_8)); }
                }
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Server did not exit");
                assertEquals(0, process.exitValue(), output.get(3, TimeUnit.SECONDS));
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    assertTrue(process.waitFor(5, TimeUnit.SECONDS));
                }
                reader.close();
            }
        }
    }
}
