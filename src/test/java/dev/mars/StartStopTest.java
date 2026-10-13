package dev.mars;

import dev.mars.server.ServerInstance;
import dev.mars.server.ServerOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class StartStopTest {
    @Test
    void consoleExecutesJsonCommandsAndContinuesAfterPluginError() throws Exception {
        var result = run("dev.mars.StartStop", new String[]{"Server-001", "--port", "0", "--admin-port", "0"},
                "send {\"instruction\":\"unknown\",\"parameters\":{}}\n"
                + "send {\"instruction\":\"math.add\",\"parameters\":{\"a\":10,\"b\":20}}\nquit\n");
        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("UNKNOWN_INSTRUCTION"), result.output());
        assertTrue(result.output().contains("status=OK | result=30"), result.output());
        assertReleased(result);
    }

    @Test
    void standaloneClientExecutesJsonCommand() throws Exception {
        try (var instance = ServerInstance.start(new ServerOptions("Server-001", 0, 0))) {
            var result = run("dev.mars.client.TextMessageClient", new String[]{"--port", "" + instance.getPort(),
                    "--expected-server-id", "Server-001",
                    "{\"instruction\":\"text.concat\",\"parameters\":{\"a\":\"hello \",\"b\":\"world\"}}"}, "");
            assertEquals(0, result.exitCode(), result.output());
            assertTrue(result.output().contains("status=OK | result=\"hello world\""), result.output());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"quit\n", "shutdown\nquit\n"})
    void consoleUsesAllocatedPortsAndCleansUpOnExit(String exitCommands) throws Exception {
        var result = run("dev.mars.StartStop", new String[]{"CLI-001", "--port", "0", "--admin-port", "0"},
                "send Hello CLI\nstatus\n" + exitCommands);
        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("serverId=CLI-001"), result.output());
        assertTrue(result.output().contains("result=\"Received: Hello CLI\""), result.output());
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
        assertTrue(result.output().contains("result=\"Received: Explicit ports\""), result.output());
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
        assertTrue(result.output().contains("result=\"Received: Hello\""), result.output());
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
                assertTrue(result.output().contains("serverId=Target-001"), result.output());
                assertTrue(result.output().contains("result=\"Received: Hello from CLI\""), result.output());
            } else {
                assertNotEquals(0, result.exitCode(), result.output());
                assertTrue(result.output().contains("Server ID mismatch"), result.output());
            }
        }
    }

    private Result run(String mainClass, String[] args, String input) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        if (!Files.exists(java)) { java = Path.of(System.getProperty("java.home"), "bin", "java"); }
        String classes = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
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
