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
package dev.mars.plugins.gossip;

import dev.mars.client.CommandExecutionException;
import dev.mars.client.TextMessageClient;
import dev.mars.protocol.CommandReply;
import dev.mars.testing.HostProcess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.Optional;

import static dev.mars.protocol.CommandError.Code.INVALID_PARAMETERS;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies real ServiceLoader discovery and TCP replies with the provider absent from the parent classpath. */
@Timeout(40)
class GossipPluginIT {
    @TempDir Path temporary;

    @Test void packagesOnlyPluginImplementationAndResources() throws Exception {
        try (var jar = new JarFile(pluginJar().toFile())) {
            assertNotNull(jar.getEntry("dev/mars/plugins/gossip/GossipPlugin.class"));
            assertNotNull(jar.getEntry("dev/mars/plugins/gossip/GossipConfig.class"));
            assertNotNull(jar.getEntry("META-INF/LICENSE"));
            assertNotNull(jar.getEntry("META-INF/NOTICE"));
            var provider = jar.getEntry("META-INF/services/dev.mars.plugin.CommandPlugin");
            assertNotNull(provider);
            try (var input = jar.getInputStream(provider)) {
                assertEquals("dev.mars.plugins.gossip.GossipPlugin", new String(input.readAllBytes(), StandardCharsets.UTF_8).strip());
            }
            // Only plugin classes may be packaged; host and Jackson classes come from the server.
            assertTrue(jar.stream().map(JarEntry::getName).noneMatch(name ->
                    (name.endsWith(".class") && !name.startsWith("dev/mars/plugins/gossip/"))
                    || name.startsWith("com/fasterxml/") || name.contains("Test.class")));
        }
    }

    @Test void packagedInstancesResolveTheirOwnConfigurationAndClosingOneLeavesOtherUsable() throws Exception {
        Path config = temporary.resolve("shared properties.properties");
        Files.writeString(config, """
                swarm.id=default-swarm
                advertise.host=127.0.0.1
                seeds=Unavailable@seed-not-present.invalid:9000
                server.Server-A.swarm.id=first-swarm
                server.Server-B.swarm.id=second-swarm
                server.Server-B.advertise.host=127.0.0.2
                """);
        Path directory = copyPlugin();
        try (var host = host(config)) {
            var firstPorts = host.startServer("Server-A", 0, 0, directory);
            var secondPorts = host.startServer("Server-B", 0, 0, directory);
            var firstClient = new TextMessageClient("127.0.0.1", firstPorts.port(), "Server-A");
            var secondClient = new TextMessageClient("127.0.0.1", secondPorts.port(), "Server-B");
            var first = status(firstClient);
            var second = status(secondClient);
            assertEquals("first-swarm", first.get("swarmId"));
            assertEquals("second-swarm", second.get("swarmId"));
            var firstSelf = self(first);
            var secondSelf = self(second);
            assertEquals("Server-A", firstSelf.get("serverId"));
            assertEquals("Server-B", secondSelf.get("serverId"));
            assertEquals(BigInteger.valueOf(firstPorts.port()), firstSelf.get("port"));
            assertEquals(BigInteger.valueOf(secondPorts.port()), secondSelf.get("port"));
            assertEquals("127.0.0.1", firstSelf.get("host"));
            assertEquals("127.0.0.2", secondSelf.get("host"));
            UUID.fromString((String) firstSelf.get("bootId"));
            UUID.fromString((String) secondSelf.get("bootId"));
            assertNotEquals(firstSelf.get("bootId"), secondSelf.get("bootId"));
            assertEquals("departure-runtime", first.get("implementationStage"));
            assertEquals(List.of("exchange", "members", "status", "leave"), first.get("supportedOperations"));
            assertEquals(true, first.get("isolated"));
            assertEquals(firstSelf, self(status(firstClient)));

            for (var parameters : List.<Map<String, Object>>of(Map.of(), Map.of("operation", 1),
                    Map.of("operation", "exchange"), Map.of("operation", "leave", "unexpected", true),
                    Map.of("operation", "status", "unexpected", true))) {
                UUID id = UUID.randomUUID();
                var failure = assertThrows(CommandExecutionException.class,
                        () -> firstClient.execute(id, "gossip", parameters));
                assertEquals(id, failure.reply().messageId());
                assertEquals("Server-A", failure.reply().serverId());
                assertEquals(INVALID_PARAMETERS, failure.reply().error().code());
            }
            assertEquals("Received: hello 节点", firstClient.send("hello 节点").text());
            host.stopServer("Server-A");
            assertEquals(secondSelf, self(status(secondClient)));
            assertEquals("RUNNING", status(secondClient).get("workerState"));
            host.stopAllServers();
            assertTrue(host.isAlive(), "Verify loader cleanup before the child JVM exits");
            // Delete while the JVM is still alive so Windows can detect leaked class-loader handles.
            Files.delete(directory.resolve(pluginJar().getFileName()));
            assertCleanExit(host);
        }
    }

    @Test void invalidConfigurationFailsStartupAndReleasesPackagedJar() throws Exception {
        Path config = temporary.resolve("invalid.properties");
        Files.writeString(config, "swarm.id=demo\nadvertise.host=127.0.0.1\nmax.members=0\n");
        Path directory = copyPlugin();
        int mainPort;
        int adminPort;
        try (var main = new ServerSocket(0); var admin = new ServerSocket(0)) {
            mainPort = main.getLocalPort();
            adminPort = admin.getLocalPort();
        }
        try (var host = host(config)) {
            var failure = assertThrows(IOException.class, () -> host.startServer("Server-A", mainPort, adminPort, directory));
            assertTrue(failure.getMessage().contains("max.members"), failure.getMessage());
            // Rebind both listeners while the JVM lives; process exit must not supply the cleanup.
            try (var main = new ServerSocket(mainPort); var admin = new ServerSocket(adminPort)) {
                assertEquals(mainPort, main.getLocalPort());
                assertEquals(adminPort, admin.getLocalPort());
            }
            assertTrue(host.isAlive(), "Verify failed-startup cleanup before the child JVM exits");
            Files.delete(directory.resolve(pluginJar().getFileName()));
            assertCleanExit(host);
        }
    }

    @Test void packagedProvidersExchangeInTheSameSwarmAndServeBoundedMembershipPages() throws Exception {
        Path configFile = temporary.resolve("same swarm.properties");
        Files.writeString(configFile, "swarm.id=demo-swarm\nadvertise.host=127.0.0.1\nmax.batch.members=2\nmax.members=2\n");
        var config = GossipConfig.load(configFile, "Server-A");
        var wire = new GossipWire(config);
        Path directory = copyPlugin();
        try (var host = host(configFile)) {
            var first = new TextMessageClient("127.0.0.1", host.startServer("Server-A", 0, 0, directory).port(), "Server-A");
            var second = new TextMessageClient("127.0.0.1", host.startServer("Server-B", 0, 0, directory).port(), "Server-B");
            var firstSelf = MemberRecord.fromWire(status(first).get("self"));
            UUID id = UUID.randomUUID();
            var reply = second.execute(id, "gossip", wire.toWire(new GossipWire.ExchangeRequest(firstSelf, List.of())));
            assertEquals(id, reply.messageId());
            assertTrue(GossipWire.bytes(reply).length <= config.maxExchangeBytes());
            var exchange = wire.decodeReply(reply.result());
            assertEquals("Server-B", exchange.sender().key().serverId());
            assertEquals(MembershipTable.Admission.ADMITTED, exchange.senderAdmission());
            var full = second.execute("gossip", wire.toWire(new GossipWire.ExchangeRequest(GossipCommandsTest.member("Third", 3), List.of())));
            assertEquals(MembershipTable.Admission.CAPACITY, wire.decodeReply(full.result()).senderAdmission());
            assertTrue(GossipWire.bytes(full).length <= config.maxExchangeBytes());
            assertEquals(BigInteger.ONE, ((Map<?, ?>) status(second).get("capacity")).get("admissionsSkipped"));
            first.execute("gossip", wire.toWire(new GossipWire.ExchangeRequest(exchange.sender(), exchange.members())));
            var page = assertInstanceOf(Map.class, first.execute("gossip", Map.of("operation", "members", "limit", 1)).result());
            assertEquals(1, ((List<?>) page.get("members")).size());
            assertNotNull(page.get("nextCursor"));
            var next = assertInstanceOf(Map.class, first.execute("gossip",
                    Map.of("operation", "members", "limit", 1, "cursor", page.get("nextCursor"))).result());
            assertEquals("Server-B", ((Map<?, ?>) ((List<?>) next.get("members")).getFirst()).get("serverId"));
            assertNull(next.get("nextCursor"));
            var wrongSwarm = new java.util.LinkedHashMap<>(wire.toWire(new GossipWire.ExchangeRequest(firstSelf, List.of())));
            wrongSwarm.put("swarmId", "other-swarm");
            assertThrows(CommandExecutionException.class, () -> second.execute("gossip", wrongSwarm));
            assertEquals(BigInteger.valueOf(2), ((Map<?, ?>) status(second).get("capacity")).get("retainedIdentities"));
            host.stopAllServers();
            assertTrue(host.isAlive());
            Files.delete(directory.resolve(pluginJar().getFileName()));
            assertCleanExit(host);
        }
    }

    @Test void packagedSwarmBootstrapsAutomaticallyThroughHostnameSeedsAndReleasesAllWorkersAndJarHandles() throws Exception {
        int seedPort;
        try (var reservation = new java.net.ServerSocket(0)) { seedPort = reservation.getLocalPort(); }
        Path file = temporary.resolve("automatic swarm.properties");
        Files.writeString(file, """
                swarm.id=automatic
                advertise.host=127.0.0.1
                round.millis=60
                heartbeat.millis=120
                suspect.after.millis=600
                dead.after.millis=1200
                terminal.retention.millis=2400
                max.batch.members=2
                seeds=Server-B@localhost:%d
                """.formatted(seedPort));
        Path directory = copyPlugin();
        try (var host = host(file)) {
            int firstPort = host.startServer("Server-A", 0, 0, directory).port();
            int secondPort = host.startServer("Server-B", seedPort, 0, directory).port();
            int thirdPort = host.startServer("Server-C", 0, 0, directory).port();
            var clients = List.of(new TextMessageClient("127.0.0.1", firstPort, "Server-A"),
                    new TextMessageClient("127.0.0.1", secondPort, "Server-B"),
                    new TextMessageClient("127.0.0.1", thirdPort, "Server-C"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean converged = false;
            while (System.nanoTime() < deadline && !converged) {
                converged = true;
                for (var client : clients) {
                    var state = status(client);
                    converged &= BigInteger.valueOf(3).equals(((Map<?, ?>) state.get("assessments")).get("ALIVE"));
                    converged &= ((BigInteger) ((Map<?, ?>) state.get("self")).get("heartbeat")).signum() > 0;
                }
                if (!converged) { Thread.sleep(30); }
            }
            assertTrue(converged, "Packaged nodes did not converge through their seed");
            for (var client : clients) {
                var state = status(client);
                assertEquals("RUNNING", state.get("workerState"));
                assertTrue(((BigInteger) ((Map<?, ?>) state.get("self")).get("heartbeat")).signum() > 0);
            }
            var departing = clients.get(1);
            String departingBoot = (String) self(status(departing)).get("bootId");
            departing.execute("gossip", Map.of("operation", "leave"));
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
            boolean left = false;
            while (System.nanoTime() < deadline && !left) {
                left = "QUIESCED".equals(status(departing).get("workerState"));
                for (var client : List.of(clients.getFirst(), clients.getLast())) {
                    var page = (Map<?, ?>) client.execute("gossip", Map.of("operation", "members")).result();
                    // Pages contain at most two rows, so follow the cursor when needed.
                    var rows = new ArrayList<Object>((List<?>) page.get("members"));
                    while (page.get("nextCursor") != null) {
                        page = (Map<?, ?>) client.execute("gossip", Map.of("operation", "members", "cursor", page.get("nextCursor"))).result();
                        rows.addAll((List<?>) page.get("members"));
                    }
                    left &= rows.stream().map(row -> (Map<?, ?>) row).anyMatch(row -> departingBoot.equals(row.get("bootId")) && "LEFT".equals(row.get("state")));
                }
                if (!left) { Thread.sleep(20); }
            }
            assertTrue(left, "Packaged departure did not reach the other members");
            var departure = (Map<?, ?>) status(departing).get("departure");
            assertEquals("COMPLETED", departure.get("state"));
            assertTrue(((BigInteger) departure.get("successes")).signum() > 0);
            assertEquals(departure, ((Map<?, ?>) departing.execute("gossip", Map.of("operation", "leave")).result()).get("departure"));
            assertEquals("Received: after departure", departing.send("after departure").text());
            assertEquals("RUNNING", status(clients.getFirst()).get("workerState"));
            host.stopAllServers();
            var departureLogs = host.output().stream()
                    .filter(line -> line.contains(" | plugin=gossip | ") && line.contains("event=DEPARTURE_FINISHED")
                            && line.contains("bootId=" + departingBoot)).toList();
            assertEquals(1, departureLogs.size(), "Packaged provider must log one outcome despite repeated leave and host close");
            for (var field : List.of("serverId=Server-B", "swarmId=automatic", "state=COMPLETED", "attempts=", "successes=")) {
                assertTrue(departureLogs.getFirst().contains(field), departureLogs.getFirst());
            }
            System.out.println("Gossip logging test PASSED | " + departureLogs.getFirst());
            assertTrue(host.isAlive());
            Files.delete(directory.resolve(pluginJar().getFileName()));
            assertCleanExit(host);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"quit\n", "", "shutdown\nquit\n"})
    void realConsoleExitStopsPackagedGossipBeforeTheJvmExits(String exitInput) throws Exception {
        Path directory = copyPlugin();
        Path config = temporary.resolve("console.properties");
        Path inspected = temporary.resolve("console-inspected");
        try (var peer = new GossipSwarmTest.StalledPeer()) {
            Files.writeString(config, "swarm.id=console\nadvertise.host=127.0.0.1\nround.millis=20\nseeds=Stalled@127.0.0.1:" + peer.port() + "\n");
            try (var console = HostProcess.console("Console", directory).systemProperty("gossip.config", config.toString())
                    .holdAfterExitUntil(inspected).start()) {
                assertTrue(peer.request.get(5, TimeUnit.SECONDS), "Console gossip did not start background work");
                console.input(exitInput);
                console.closeInput();
                console.awaitConsoleExit(Duration.ofSeconds(5));
                assertTrue(peer.closed.get(2, TimeUnit.SECONDS));
                assertTrue(console.isAlive(), "Inspect cleanup independently of JVM exit");
                Files.delete(directory.resolve(pluginJar().getFileName()));
                Files.writeString(inspected, "checked");
                assertEquals(0, console.awaitExit(Duration.ofSeconds(5)), console.outputText());
            }
        }
    }

    private static Map<?, ?> status(TextMessageClient client) throws IOException {
        UUID id = UUID.randomUUID();
        CommandReply reply = client.execute(id, "gossip", Map.of("operation", "status"));
        assertEquals(id, reply.messageId());
        assertEquals("OK", reply.status());
        assertTrue(reply.connectionId() > 0);
        return assertInstanceOf(Map.class, reply.result());
    }

    private static Map<?, ?> self(Map<?, ?> status) { return assertInstanceOf(Map.class, status.get("self")); }

    private static Path pluginJar() {
        String configured = System.getProperty("gossip.plugin.jar");
        assertNotNull(configured, "Use mvn verify so Failsafe supplies the packaged artifact path");
        Path jar = Path.of(configured).toAbsolutePath();
        assertTrue(Files.isRegularFile(jar), "Plugin must be packaged before integration tests");
        return jar;
    }

    private Path copyPlugin() throws IOException {
        Path directory = Files.createDirectory(temporary.resolve("runtime plugins"));
        Files.copy(pluginJar(), directory.resolve(pluginJar().getFileName()));
        return directory;
    }

    /** A controller JVM whose servers read this gossip configuration; the plugin comes only from its JAR. */
    private static HostProcess host(Path config) throws IOException {
        return HostProcess.controller().systemProperty("gossip.config", config.toString()).start();
    }

    private static void assertCleanExit(HostProcess host) throws IOException {
        assertEquals(0, host.stop(Duration.ofSeconds(10)), host.outputText());
        assertFalse(host.outputText().contains("Server announcement sent"), host.outputText());
    }
}
