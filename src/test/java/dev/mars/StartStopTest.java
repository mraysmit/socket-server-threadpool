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
package dev.mars;

import dev.mars.server.ServerInstance;
import dev.mars.server.ServerOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class StartStopTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void consoleLoadsRuntimeProviderOnlyWhenRegisteredInItsJar(
            boolean registered, @TempDir Path directory) throws Exception {
        Path jar = directory.resolve("test-commands.jar");
        var fixture = dev.mars.plugin.fixtures.ConsolePlugin.class;
        try (var output = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
            String resource = fixture.getName().replace('.', '/') + ".class";
            output.putNextEntry(new java.util.jar.JarEntry(resource));
            try (var content = fixture.getResourceAsStream("/" + resource)) {
                assertNotNull(content);
                content.transferTo(output);
            }
            output.closeEntry();
            if (registered) {
                output.putNextEntry(new java.util.jar.JarEntry("META-INF/services/dev.mars.plugin.CommandPlugin"));
                output.write((fixture.getName() + "\n").getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        var result = run("dev.mars.StartStop", new String[]{"Server-001", "--port", "0", "--admin-port", "0",
                        "--plugins-dir", directory.toString()},
                "send {\"instruction\":\"test.console\",\"parameters\":{}}\nquit\n",
                productionClasspath());
        assertEquals(0, result.exitCode(), result.output());
        if (registered) {
            assertCommandReplies(result, "Server-001", "result=\"Loaded from runtime JAR\"");
        } else {
            assertCommandReplies(result, "Server-001",
                    "error=UNKNOWN_INSTRUCTION: Unknown instruction: test.console");
        }
        assertReleased(result);
        Files.delete(jar);
    }

    private String productionClasspath() throws Exception {
        var types = java.util.List.of(StartStop.class, com.fasterxml.jackson.databind.ObjectMapper.class,
                com.fasterxml.jackson.core.JsonFactory.class, com.fasterxml.jackson.annotation.JsonProperty.class);
        var paths = new java.util.ArrayList<String>();
        for (Class<?> type : types) {
            paths.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        }
        return String.join(java.io.File.pathSeparator, paths);
    }

    @Test
    void commandLineAcceptsMultipleFoldersIncludingPathsWithSpaces(@TempDir Path temporary) throws Exception {
        Path first = Files.createDirectory(temporary.resolve("first plugins"));
        Path second = Files.createDirectory(temporary.resolve("second plugins"));
        var result = run("dev.mars.StartStop", new String[]{"Server-001", "--port", "0", "--admin-port", "0",
                "--plugins-dir", first.toString(), "--plugins-dir", second.toString()}, "send Hello\nquit\n");
        assertEquals(0, result.exitCode(), result.output());
        assertCommandReplies(result, "Server-001", "result=\"Received: Hello\"");
        assertReleased(result);
    }

    @Test
    void consoleExecutesJsonCommandsAndContinuesAfterPluginError() throws Exception {
        var result = run("dev.mars.StartStop", new String[]{"Server-001", "--port", "0", "--admin-port", "0"},
                "send {\"instruction\":\"unknown\",\"parameters\":{}}\n"
                + "send {\"instruction\":\"echo\",\"parameters\":{\"text\":\"still healthy\"}}\nquit\n",
                productionClasspath());
        assertEquals(0, result.exitCode(), result.output());
        assertCommandReplies(result, "Server-001", "error=UNKNOWN_INSTRUCTION: Unknown instruction: unknown",
                "result=\"Received: still healthy\"");
        assertReleased(result);
    }

    @Test
    void standaloneClientExecutesJsonCommand() throws Exception {
        try (var instance = ServerInstance.start(new ServerOptions("Server-001", 0, 0))) {
            var result = run("dev.mars.client.TextMessageClient", new String[]{"--port", "" + instance.getPort(),
                    "--expected-server-id", "Server-001",
                    "{\"instruction\":\"echo\",\"parameters\":{\"text\":\"hello world\"}}"}, "",
                    productionClasspath());
            assertEquals(0, result.exitCode(), result.output());
            assertCommandReplies(result, "Server-001", "result=\"Received: hello world\"");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"quit\n", "shutdown\nquit\n"})
    void consoleUsesAllocatedPortsAndCleansUpOnExit(String exitCommands) throws Exception {
        var result = run("dev.mars.StartStop", new String[]{"CLI-001", "--port", "0", "--admin-port", "0"},
                "send Hello CLI\nstatus\n" + exitCommands);
        assertEquals(0, result.exitCode(), result.output());
        assertCommandReplies(result, "CLI-001", "result=\"Received: Hello CLI\"");
        assertTrue(result.output().contains("Server status: ALIVE"), result.output());
        assertFalse(result.output().contains("Error connecting"), result.output());
        assertReleased(result);
    }

    @ParameterizedTest
    @ValueSource(strings = {"explicit", "main-auto", "admin-auto"})
    void consoleUsesExplicitAndMixedPortAssignments(String mode) throws Exception {
        int main;
        int admin;
        try (var a = new ServerSocket(0); var b = new ServerSocket(0)) {
            main = mode.equals("main-auto") ? 0 : a.getLocalPort();
            admin = mode.equals("admin-auto") ? 0 : b.getLocalPort();
        }
        var result = run("dev.mars.StartStop", new String[]{"CLI-001", "--port", "" + main,
                "--admin-port", "" + admin}, "send Explicit ports\nstatus\nquit\n");
        assertEquals(0, result.exitCode(), result.output());
        assertCommandReplies(result, "CLI-001", "result=\"Received: Explicit ports\"");
        assertTrue(result.output().contains("Server status: ALIVE"), result.output());
        int[] actual = ports(result);
        if (main != 0) { assertEquals(main, actual[0]); }
        if (admin != 0) { assertEquals(admin, actual[1]); }
        assertReleased(result);
    }

    @Test
    void endOfInputClosesBothListeners() throws Exception {
        var result = run("dev.mars.StartStop", new String[]{"CLI-001", "--port", "0", "--admin-port", "0"}, "");
        assertEquals(0, result.exitCode(), result.output());
        assertReleased(result);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void blankIdAtStartupUsesUuidDefault(String id) throws Exception {
        var result = run("dev.mars.StartStop", new String[]{id, "--port", "0", "--admin-port", "0"}, "send Hello\nquit\n");
        assertEquals(0, result.exitCode(), result.output());
        var match = Pattern.compile("Server ready \\| serverId=([^ ]+)").matcher(result.output());
        assertTrue(match.find(), result.output());
        assertDoesNotThrow(() -> UUID.fromString(match.group(1)));
        assertCommandReplies(result, match.group(1), "result=\"Received: Hello\"");
        assertReleased(result);
    }

    @ParameterizedTest
    @ValueSource(strings = {"--port", "--admin-port"})
    void occupiedPortExitsWithFailureAndNoReadyMessage(String option) throws Exception {
        try (var occupied = new ServerSocket(0)) {
            String main = option.equals("--port") ? "" + occupied.getLocalPort() : "0";
            String admin = option.equals("--admin-port") ? "" + occupied.getLocalPort() : "0";
            var result = run("dev.mars.StartStop", new String[]{"Blocked", "--port", main, "--admin-port", admin}, "");
            assertNotEquals(0, result.exitCode(), result.output());
            assertTrue(result.output().contains("port " + occupied.getLocalPort() + " is already in use"), result.output());
            assertFalse(result.output().contains("Server ready"), result.output());
            assertFalse(result.output().contains("Server announcement"), result.output());
            assertFalse(occupied.isClosed());
        }
    }

    @Test
    void invalidArgumentsExitBeforeStartup() throws Exception {
        var result = run("dev.mars.StartStop", new String[]{"--port"}, "");
        assertNotEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("Missing value for --port"), result.output());
        assertFalse(result.output().contains("Server ready"), result.output());
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev.mars.StartStop", "dev.mars.client.TextMessageClient"})
    void helpExitsWithoutStartingListeners(String mainClass) throws Exception {
        var result = run(mainClass, new String[]{"--help"}, "");
        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("Usage:"), result.output());
        assertFalse(result.output().contains("Server ready"), result.output());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Target-001", "Wrong-server"})
    void standaloneClientTargetsSelectedServerAndChecksIdentity(String expected) throws Exception {
        try (var instance = ServerInstance.start(new ServerOptions("Target-001", 0, 0))) {
            var result = run("dev.mars.client.TextMessageClient", new String[]{"--host", "127.0.0.1", "--port",
                    "" + instance.getPort(), "--expected-server-id", expected, "Hello from CLI"}, "");
            if (expected.equals("Target-001")) {
                assertEquals(0, result.exitCode(), result.output());
                assertCommandReplies(result, "Target-001", "result=\"Received: Hello from CLI\"");
            } else {
                assertNotEquals(0, result.exitCode(), result.output());
                assertTrue(result.output().contains("Server ID mismatch"), result.output());
            }
        }
    }

    private Result run(String mainClass, String[] args, String input) throws Exception {
        return run(mainClass, args, input,
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
    }

    private Result run(String mainClass, String[] args, String input, String classes) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        if (!Files.exists(java)) { java = Path.of(System.getProperty("java.home"), "bin", "java"); }
        var command = new ArrayList<String>();
        command.add(java.toString()); command.add("-cp"); command.add(classes); command.add(mainClass);
        // Preserve embedded JSON quotes through Windows' native command-line parser.
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        command.addAll(Arrays.stream(args).map(arg -> windows ? arg.replace("\"", "\\\"") : arg).toList());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (var outputReader = Executors.newVirtualThreadPerTaskExecutor()) {
            var output = outputReader.submit(() -> new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            try {
                try (var stdin = process.getOutputStream()) { stdin.write(input.getBytes(StandardCharsets.UTF_8)); }
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Command did not exit: " + command);
                return new Result(process.exitValue(), output.get(2, TimeUnit.SECONDS));
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    assertTrue(process.waitFor(5, TimeUnit.SECONDS));
                }
            }
        }
    }

    private int[] ports(Result result) {
        var match = Pattern.compile("main=localhost:(\\d+) \\| admin=localhost:(\\d+)").matcher(result.output());
        assertTrue(match.find(), result.output());
        return new int[]{Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2))};
    }

    private void assertCommandReplies(Result result, String serverId, String... outcomes) {
        var sending = Pattern.compile("Sending: messageId=([^ ]+) \\| text=").matcher(result.output());
        var ids = new ArrayList<UUID>();
        while (sending.find()) { ids.add(UUID.fromString(sending.group(1))); }
        assertEquals(outcomes.length, ids.size(), result.output());
        assertEquals(ids.size(), new HashSet<>(ids).size(), "Each command must use a unique message ID");
        var reply = Pattern.compile("(?:Server response: |Error sending message: Server rejected message: )"
                + "messageId=([^ ]+) \\| serverId=([^ ]+) \\| connectionId=(\\d+) \\| status=(OK|ERROR) \\| ([^\\r\\n]+)")
                .matcher(result.output());
        var connections = new HashSet<Long>();
        for (int i = 0; i < outcomes.length; i++) {
            assertTrue(reply.find(), result.output());
            assertEquals(ids.get(i), UUID.fromString(reply.group(1)), result.output());
            assertEquals(serverId, reply.group(2), result.output());
            long connection = Long.parseLong(reply.group(3));
            assertTrue(connection > 0, result.output());
            assertTrue(connections.add(connection), "Each connection must have its own ID");
            assertEquals(outcomes[i].startsWith("result=") ? "OK" : "ERROR", reply.group(4), result.output());
            assertEquals(outcomes[i], reply.group(5), result.output());
        }
        assertFalse(reply.find(), "Unexpected extra command reply:\n" + result.output());
    }

    private void assertReleased(Result result) throws IOException {
        int[] ports = ports(result);
        assertNotEquals(ports[0], ports[1]);
        try (var main = new ServerSocket(ports[0]); var admin = new ServerSocket(ports[1])) {
            assertEquals(ports[0], main.getLocalPort());
            assertEquals(ports[1], admin.getLocalPort());
        }
    }

    private record Result(int exitCode, String output) { }
}
