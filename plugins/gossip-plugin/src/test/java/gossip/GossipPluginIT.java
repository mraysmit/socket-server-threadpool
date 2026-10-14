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
package gossip;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.client.CommandExecutionException;
import dev.mars.client.TextMessageClient;
import dev.mars.protocol.CommandReply;
import dev.mars.server.ServerInstance;
import gossip.fixture.GossipHostProcess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import static org.junit.jupiter.api.Assertions.*;

/** Verifies real ServiceLoader discovery and TCP replies with the provider absent from the parent classpath. */
@Timeout(40)
class GossipPluginIT {
    @TempDir Path temporary;

    @Test void packagesOnlyPluginImplementationAndResources() throws Exception {
        try (var jar = new JarFile(pluginJar().toFile())) {
            assertNotNull(jar.getEntry("gossip/GossipPlugin.class"));
            assertNotNull(jar.getEntry("gossip/GossipConfig.class"));
            assertNotNull(jar.getEntry("META-INF/LICENSE"));
            assertNotNull(jar.getEntry("META-INF/NOTICE"));
            var provider = jar.getEntry("META-INF/services/dev.mars.plugin.CommandPlugin");
            assertNotNull(provider);
            try (var input = jar.getInputStream(provider)) {
                assertEquals("gossip.GossipPlugin", new String(input.readAllBytes(), StandardCharsets.UTF_8).strip());
            }
            assertTrue(jar.stream().noneMatch(entry -> entry.getName().startsWith("dev/mars/")
                    || entry.getName().startsWith("com/fasterxml/") || entry.getName().contains("Test.class")));
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
        try (var servers = new ServerProcess(directory, config, false)) {
            Ready ready = servers.ready.get(10, TimeUnit.SECONDS);
            var firstClient = new TextMessageClient("127.0.0.1", ready.firstPort(), "Server-A");
            var secondClient = new TextMessageClient("127.0.0.1", ready.secondPort(), "Server-B");
            var first = status(firstClient);
            var second = status(secondClient);
            assertEquals("first-swarm", first.get("swarmId"));
            assertEquals("second-swarm", second.get("swarmId"));
            var firstSelf = self(first);
            var secondSelf = self(second);
            assertEquals("Server-A", firstSelf.get("serverId"));
            assertEquals("Server-B", secondSelf.get("serverId"));
            assertEquals(BigInteger.valueOf(ready.firstPort()), firstSelf.get("port"));
            assertEquals(BigInteger.valueOf(ready.secondPort()), secondSelf.get("port"));
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
            servers.send("close-first");
            servers.firstClosed.get(5, TimeUnit.SECONDS);
            assertEquals(secondSelf, self(status(secondClient)));
            assertEquals("RUNNING", status(secondClient).get("workerState"));
            servers.send("close-all");
            servers.allClosed.get(5, TimeUnit.SECONDS);
            assertTrue(servers.process.isAlive(), "Verify loader cleanup before the child JVM exits");
            // Delete while the JVM is still alive so Windows can detect leaked class-loader handles.
            Files.delete(directory.resolve(pluginJar().getFileName()));
        }
    }

    @Test void invalidConfigurationFailsStartupAndReleasesPackagedJar() throws Exception {
        Path config = temporary.resolve("invalid.properties");
        Files.writeString(config, "swarm.id=demo\nadvertise.host=127.0.0.1\nmax.members=0\n");
        Path directory = copyPlugin();
        try (var servers = new ServerProcess(directory, config, true)) {
            String failure = servers.startupFailure.get(10, TimeUnit.SECONDS);
            assertTrue(failure.contains("max.members"), failure);
            assertTrue(servers.process.isAlive(), "Verify failed-startup cleanup before the child JVM exits");
            Files.delete(directory.resolve(pluginJar().getFileName()));
        }
    }

    @Test void packagedProvidersExchangeInTheSameSwarmAndServeBoundedMembershipPages() throws Exception {
        Path configFile = temporary.resolve("same swarm.properties");
        Files.writeString(configFile, "swarm.id=demo-swarm\nadvertise.host=127.0.0.1\nmax.batch.members=2\n");
        var config = GossipConfig.load(configFile, "Server-A");
        var wire = new GossipWire(config);
        Path directory = copyPlugin();
        try (var servers = new ServerProcess(directory, configFile, false)) {
            var ready = servers.ready.get(10, TimeUnit.SECONDS);
            var first = new TextMessageClient("127.0.0.1", ready.firstPort(), "Server-A");
            var second = new TextMessageClient("127.0.0.1", ready.secondPort(), "Server-B");
            var firstSelf = MemberRecord.fromWire(status(first).get("self"));
            UUID id = UUID.randomUUID();
            var reply = second.execute(id, "gossip", wire.parameters(firstSelf, List.of()));
            assertEquals(id, reply.messageId());
            assertTrue(GossipWire.bytes(reply).length <= config.maxExchangeBytes());
            var exchange = wire.decode(reply.result(), false);
            assertEquals("Server-B", exchange.sender().key().serverId());
            first.execute("gossip", wire.parameters(exchange.sender(), exchange.members()));
            var page = assertInstanceOf(Map.class, first.execute("gossip", Map.of("operation", "members", "limit", 1)).result());
            assertEquals(1, ((List<?>) page.get("members")).size());
            assertNotNull(page.get("nextCursor"));
            var next = assertInstanceOf(Map.class, first.execute("gossip",
                    Map.of("operation", "members", "limit", 1, "cursor", page.get("nextCursor"))).result());
            assertEquals("Server-B", ((Map<?, ?>) ((List<?>) next.get("members")).getFirst()).get("serverId"));
            assertNull(next.get("nextCursor"));
            var wrongSwarm = new java.util.LinkedHashMap<>(wire.parameters(firstSelf, List.of()));
            wrongSwarm.put("swarmId", "other-swarm");
            assertThrows(CommandExecutionException.class, () -> second.execute("gossip", wrongSwarm));
            assertEquals(BigInteger.valueOf(2), ((Map<?, ?>) status(second).get("capacity")).get("retainedIdentities"));
            servers.send("close-all");
            servers.allClosed.get(5, TimeUnit.SECONDS);
            assertTrue(servers.process.isAlive());
            Files.delete(directory.resolve(pluginJar().getFileName()));
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
        try (var servers = new ServerProcess(directory, file, false, seedPort)) {
            var ready = servers.ready.get(10, TimeUnit.SECONDS);
            int thirdPort = servers.thirdReady.get(10, TimeUnit.SECONDS);
            var clients = List.of(new TextMessageClient("127.0.0.1", ready.firstPort(), "Server-A"),
                    new TextMessageClient("127.0.0.1", ready.secondPort(), "Server-B"),
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
            servers.send("close-all");
            servers.allClosed.get(5, TimeUnit.SECONDS);
            assertTrue(servers.process.isAlive());
            Files.delete(directory.resolve(pluginJar().getFileName()));
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
            Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
            if (!Files.exists(java)) { java = Path.of(System.getProperty("java.home"), "bin", "java"); }
            var process = new ProcessBuilder(java.toString(), "-Ddev.mars.announce=false", "-Dgossip.config=" + config,
                    "-cp", childClasspath(), GossipHostProcess.class.getName(), directory.toString(), "console", inspected.toString())
                    .redirectErrorStream(true).start();
            var closed = new CompletableFuture<Void>();
            try (var readers = Executors.newVirtualThreadPerTaskExecutor()) {
                var output = readers.submit(() -> {
                    var captured = new StringBuilder();
                    try (var lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = lines.readLine()) != null) {
                            captured.append(line).append('\n');
                            if (line.contains("GOSSIP_CONSOLE_CLOSED")) { closed.complete(null); }
                        }
                    }
                    closed.completeExceptionally(new IOException("Console exited before cleanup signal:\n" + captured));
                    return captured.toString();
                });
                try {
                    assertTrue(peer.request.get(5, TimeUnit.SECONDS), "Console gossip did not start background work");
                    try (var input = process.getOutputStream()) { input.write(exitInput.getBytes(StandardCharsets.UTF_8)); }
                    closed.get(5, TimeUnit.SECONDS);
                    assertTrue(peer.closed.get(2, TimeUnit.SECONDS));
                    assertTrue(process.isAlive(), "Inspect cleanup independently of JVM exit");
                    Files.delete(directory.resolve(pluginJar().getFileName()));
                    Files.writeString(inspected, "checked");
                    assertTrue(process.waitFor(5, TimeUnit.SECONDS));
                    assertEquals(0, process.exitValue(), output.get(2, TimeUnit.SECONDS));
                } finally {
                    if (process.isAlive()) { process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS)); }
                }
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

    private static String childClasspath() {
        // Test fixtures and host dependencies only: target/classes (plugin and service file) is excluded.
        return List.of(GossipHostProcess.class, ServerInstance.class, ObjectMapper.class, JsonFactory.class, JsonProperty.class)
                .stream().map(type -> {
                    try { return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
                    catch (Exception e) { throw new IllegalStateException(e); }
                }).distinct().collect(Collectors.joining(File.pathSeparator));
    }

    private record Ready(int firstPort, int secondPort) { }

    private static final class ServerProcess implements AutoCloseable {
        private final Process process;
        private final ExecutorService reader = Executors.newVirtualThreadPerTaskExecutor();
        private final CompletableFuture<Ready> ready = new CompletableFuture<>();
        private final CompletableFuture<Integer> thirdReady = new CompletableFuture<>();
        private final CompletableFuture<Void> firstClosed = new CompletableFuture<>();
        private final CompletableFuture<Void> allClosed = new CompletableFuture<>();
        private final CompletableFuture<String> startupFailure = new CompletableFuture<>();
        private final Future<String> output;

        ServerProcess(Path directory, Path config, boolean checkStartupFailure) throws IOException {
            this(directory, config, checkStartupFailure, 0);
        }

        ServerProcess(Path directory, Path config, boolean checkStartupFailure, int seedPort) throws IOException {
            Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
            if (!Files.exists(java)) { java = Path.of(System.getProperty("java.home"), "bin", "java"); }
            var command = new ArrayList<>(List.of(java.toString(), "-Ddev.mars.announce=false", "-Dgossip.config=" + config,
                    "-cp", childClasspath(), GossipHostProcess.class.getName(), directory.toString()));
            if (checkStartupFailure) { command.add("check-startup-failure"); }
            if (seedPort != 0) { command.add("swarm"); command.add(Integer.toString(seedPort)); }
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            output = reader.submit(() -> {
                var captured = new StringBuilder();
                var pattern = Pattern.compile("^GOSSIP_READY (\\d+) (\\d+)$");
                try (var lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = lines.readLine()) != null) {
                        captured.append(line).append('\n');
                        var match = pattern.matcher(line);
                        if (match.matches()) {
                            ready.complete(new Ready(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2))));
                        }
                        if (line.equals("FIRST_CLOSED")) { firstClosed.complete(null); }
                        if (line.startsWith("THIRD_READY ")) { thirdReady.complete(Integer.parseInt(line.substring("THIRD_READY ".length()))); }
                        if (line.equals("ALL_CLOSED")) { allClosed.complete(null); }
                        if (line.startsWith("STARTUP_FAILURE ")) { startupFailure.complete(line); }
                    }
                    var ended = new IOException("Fixture exited before expected signal:\n" + captured);
                    ready.completeExceptionally(ended);
                    thirdReady.completeExceptionally(ended);
                    firstClosed.completeExceptionally(ended);
                    allClosed.completeExceptionally(ended);
                    startupFailure.completeExceptionally(ended);
                    return captured.toString();
                } catch (IOException e) {
                    ready.completeExceptionally(e);
                    thirdReady.completeExceptionally(e);
                    firstClosed.completeExceptionally(e);
                    allClosed.completeExceptionally(e);
                    startupFailure.completeExceptionally(e);
                    throw e;
                }
            });
        }

        void send(String command) throws IOException {
            process.getOutputStream().write((command + "\n").getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
        }

        @Override public void close() throws Exception {
            try {
                if (process.isAlive()) { send("quit"); }
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Fixture failed to exit");
                String captured = output.get(5, TimeUnit.SECONDS);
                assertEquals(0, process.exitValue(), captured);
                assertFalse(captured.contains("Server announcement sent"), captured);
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    assertTrue(process.waitFor(5, TimeUnit.SECONDS));
                }
                reader.shutdownNow();
                assertTrue(reader.awaitTermination(5, TimeUnit.SECONDS), "Output reader failed to stop");
            }
        }
    }
}
