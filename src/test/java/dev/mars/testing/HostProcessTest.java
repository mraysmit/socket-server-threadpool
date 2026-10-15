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
package dev.mars.testing;

import dev.mars.client.TextMessageClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class HostProcessTest {
    private static final Duration WAIT = Duration.ofSeconds(10);
    @TempDir Path temporary;

    @Test
    void hostClasspathContainsNoTestClasses() {
        var entries = HostProcess.hostClasspath().split(File.pathSeparator);
        assertTrue(entries.length >= 2, HostProcess.hostClasspath());
        assertTrue(Arrays.stream(entries).noneMatch(entry -> entry.contains("test-classes")), HostProcess.hostClasspath());
    }

    @Test
    void consoleReportsReadinessServesClientsAndQuitsCleanly() throws Exception {
        Path plugins = Files.createDirectory(temporary.resolve("plugins with spaces"));
        try (var host = HostProcess.console("Console-001", plugins).start()) {
            var ports = host.awaitReady("Console-001", WAIT);
            var client = new TextMessageClient("localhost", ports.port(), "Console-001");
            assertEquals("Received: hello", client.send("hello").text());
            assertEquals(0, host.stop(WAIT), host.outputText());
            assertFalse(host.outputText().contains("Server announcement sent"), host.outputText());
            assertReleased(ports);
        }
    }

    @Test
    void controllerStartsStopsAndReportsStartupFailures() throws Exception {
        try (var host = HostProcess.controller().start()) {
            var first = host.startServer("First", 0, 0);
            var second = host.startServer("Second", 0, 0);
            assertEquals("Received: one", new TextMessageClient("localhost", first.port(), "First").send("one").text());
            assertEquals("Received: two", new TextMessageClient("localhost", second.port(), "Second").send("two").text());

            host.stopServer("First");
            assertReleased(first);
            var occupied = assertThrows(IOException.class, () -> host.startServer("Blocked", second.port(), 0));
            assertTrue(occupied.getMessage().contains("already in use"), occupied.getMessage());
            assertThrows(IOException.class, () -> host.startServer("Second", 0, 0), "Duplicate IDs are rejected");
            assertThrows(IOException.class, () -> host.stopServer("Missing"));

            host.stopAllServers();
            assertReleased(second);
            assertTrue(host.isAlive(), "Stopping servers must not end the JVM");
            assertEquals(0, host.stop(WAIT), host.outputText());
        }
    }

    @Test
    void heldConsoleStaysAliveUntilReleased() throws Exception {
        Path release = temporary.resolve("release");
        try (var host = HostProcess.console("Held-001").holdAfterExitUntil(release).start()) {
            var ports = host.awaitReady("Held-001", WAIT);
            host.send("quit");
            host.awaitConsoleExit(WAIT);
            assertTrue(host.isAlive(), "The JVM must outlive the console until released");
            assertReleased(ports);
            Files.writeString(release, "done");
            assertEquals(0, host.awaitExit(WAIT), host.outputText());
        }
    }

    @Test
    void runsOtherMainClassesAndReportsTheirExitCode() throws Exception {
        try (var host = HostProcess.of("dev.mars.StartStop", "--port").start()) {
            host.closeInput();
            assertNotEquals(0, host.awaitExit(WAIT), host.outputText());
            assertTrue(host.outputText().contains("Missing value for --port"), host.outputText());
        }
    }

    private static void assertReleased(HostProcess.Ports ports) throws IOException {
        try (var main = new ServerSocket(ports.port()); var admin = new ServerSocket(ports.adminPort())) {
            assertEquals(ports.port(), main.getLocalPort());
            assertEquals(ports.adminPort(), admin.getLocalPort());
        }
    }
}
