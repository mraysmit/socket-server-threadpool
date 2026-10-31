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
package dev.mars.client;

import dev.mars.protocol.CommandError;
import dev.mars.protocol.CommandProtocol;
import dev.mars.protocol.CommandReply;
import dev.mars.protocol.Json;
import dev.mars.server.ThreadPooledServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Requests to a real server share kept-open connections, and a connection the server closed is replaced unseen. */
@Timeout(30)
class KeepAliveClientTest {
    private static KeepAliveClient.Settings settings() {
        return KeepAliveClient.Settings.of("Test", 65_536, Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(15));
    }

    /** Counts what the client reports, as a plugin's metrics would. */
    private static final class Counts implements KeepAliveClient.Listener {
        final AtomicInteger opened = new AtomicInteger();
        final AtomicInteger reused = new AtomicInteger();
        final AtomicInteger stale = new AtomicInteger();

        @Override public void opened() { opened.incrementAndGet(); }
        @Override public void reused() { reused.incrementAndGet(); }
        @Override public void stale() { stale.incrementAndGet(); }
    }

    private static final class Running implements AutoCloseable {
        final ThreadPooledServer server = new ThreadPooledServer(0, "Server-001");
        final Thread listener;

        Running() throws IOException {
            server.bind();
            listener = Thread.ofVirtual().start(server);
        }

        InetSocketAddress address() { return new InetSocketAddress("127.0.0.1", server.getPort()); }

        @Override public void close() throws Exception {
            server.stop();
            listener.join(10_000);
        }
    }

    /** The text the echo plugin received, from its "Received: ..." reply. */
    private static String echo(KeepAliveClient client, InetSocketAddress address, String text) throws IOException {
        String reply = (String) client.execute(address, "echo", Map.of("text", text)).result();
        assertTrue(reply.startsWith("Received: "), reply);
        return reply.substring("Received: ".length());
    }

    @Test void requestsToOneServerShareOneConnection() throws Exception {
        var counts = new Counts();
        try (var running = new Running(); var client = new KeepAliveClient(settings(), counts)) {
            for (int i = 0; i < 20; i++) { assertEquals("hello " + i, echo(client, running.address(), "hello " + i)); }
            assertEquals(1, counts.opened.get());
            assertEquals(19, counts.reused.get());
            assertEquals(0, counts.stale.get());
            assertEquals(1, client.idleConnections());
        }
    }

    @Test void aConnectionTheServerClosedIsReplacedAndTheRequestStillAnswered() throws Exception {
        var counts = new Counts();
        // Like a host whose idle limit passed, or that restarted: it keeps the connection open only until it has replied.
        try (var host = new FakeHost(true); var client = new KeepAliveClient(settings(), counts)) {
            assertEquals("ok", client.execute(host.address(), "echo", Map.of("text", "first")).result());
            assertEquals("ok", client.execute(host.address(), "echo", Map.of("text", "second")).result());
            assertEquals(2, counts.opened.get());
            assertEquals(1, counts.stale.get());
            assertEquals(2, host.requests.size(), "The stale connection carried nothing to the host");
        }
    }

    @Test void aConnectionIdleLongerThanTheLimitIsClosedRatherThanTried() throws Exception {
        var counts = new Counts();
        var shortIdle = new KeepAliveClient.Settings("Test", 65_536, Duration.ofSeconds(5), Duration.ofSeconds(10),
                Duration.ofSeconds(15), 2, Duration.ofMillis(50), 900);
        try (var running = new Running(); var client = new KeepAliveClient(shortIdle, counts)) {
            echo(client, running.address(), "first");
            Thread.sleep(200);
            echo(client, running.address(), "second");
            assertEquals(2, counts.opened.get());
            assertEquals(0, counts.stale.get(), "The old connection was dropped before it could be found stale");
        }
    }

    @Test void aConnectionIsRetiredAfterItsUses() throws Exception {
        var counts = new Counts();
        var fewUses = new KeepAliveClient.Settings("Test", 65_536, Duration.ofSeconds(5), Duration.ofSeconds(10),
                Duration.ofSeconds(15), 2, Duration.ofMinutes(4), 3);
        try (var running = new Running(); var client = new KeepAliveClient(fewUses, counts)) {
            for (int i = 0; i < 7; i++) { echo(client, running.address(), "x"); }
            assertEquals(3, counts.opened.get(), "Three uses each: 3 + 3 + 1");
            assertEquals(4, counts.reused.get());
        }
    }

    @Test void concurrentRequestsKeepOnlyTheIdleLimitOpenAfterwards() throws Exception {
        try (var running = new Running(); var client = new KeepAliveClient(settings())) {
            var failures = new CopyOnWriteArrayList<Throwable>();
            var threads = new ArrayList<Thread>();
            for (int i = 0; i < 8; i++) {
                threads.add(Thread.ofVirtual().start(() -> {
                    try { for (int j = 0; j < 10; j++) { echo(client, running.address(), "x"); } }
                    catch (Throwable failure) { failures.add(failure); }
                }));
            }
            for (var thread : threads) { thread.join(); }
            assertEquals(List.of(), failures);
            assertTrue(client.idleConnections() <= KeepAliveClient.Settings.MAX_IDLE_PER_PEER, String.valueOf(client.idleConnections()));
        }
    }

    @Test void anErrorReplyIsThrownWithTheReplyAndTheConnectionIsKept() throws Exception {
        var counts = new Counts();
        try (var running = new Running(); var client = new KeepAliveClient(settings(), counts)) {
            var refused = assertThrows(CommandExecutionException.class,
                    () -> client.execute(running.address(), "no-such-instruction", Map.of()));
            assertEquals(CommandError.Code.UNKNOWN_INSTRUCTION, refused.reply().error().code());
            assertEquals("still here", echo(client, running.address(), "still here"));
            assertEquals(1, counts.opened.get());
        }
    }

    @Test void aClosedClientRefusesWorkAndLeavesNothingOpen() throws Exception {
        try (var running = new Running()) {
            var client = new KeepAliveClient(settings());
            echo(client, running.address(), "first");
            assertEquals(1, client.idleConnections());
            client.close();
            assertEquals(0, client.idleConnections());
            var refused = assertThrows(IOException.class, () -> echo(client, running.address(), "second"));
            assertEquals("Test transport is closed", refused.getMessage());
        }
    }

    @Test void anUnreachableFirstAddressFallsBackToTheNext() throws Exception {
        var counts = new Counts();
        int closedPort;
        try (var unused = new ServerSocket(0)) { closedPort = unused.getLocalPort(); }
        try (var running = new Running(); var client = new KeepAliveClient(settings(), counts)) {
            var request = new dev.mars.protocol.CommandRequest(1, java.util.UUID.randomUUID(), "echo", Map.of("text", "x"));
            var exchange = client.exchange(List.of(new InetSocketAddress("127.0.0.1", closedPort), running.address()),
                    KeepAliveClient.envelope(request, true), KeepAliveClient.envelope(request, false),
                    System.nanoTime() + Duration.ofSeconds(5).toNanos());
            assertEquals(running.address(), exchange.address());
            assertEquals("Received: x", exchange.reply().result());
        }
    }

    /**
     * A host that answers one request per connection and then closes it. One that knows the keep-alive field accepts
     * it, so the client keeps the connection and finds it closed next time; an older one rejects the field.
     */
    private static final class FakeHost implements AutoCloseable {
        final ServerSocket listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        final List<String> requests = new CopyOnWriteArrayList<>();
        final Thread thread;

        FakeHost(boolean knowsKeepAlive) throws IOException {
            thread = Thread.ofVirtual().start(() -> {
                while (!listener.isClosed()) {
                    try (var socket = listener.accept()) {
                        var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                        String line = in.readLine();
                        if (line == null) { continue; }
                        requests.add(line);
                        var id = java.util.UUID.fromString(Json.read(line).get("messageId").textValue());
                        boolean asked = line.contains("\"" + CommandProtocol.CONNECTION_FIELD + "\"");
                        CommandReply reply = asked && !knowsKeepAlive
                                ? CommandReply.failure(id, "Fake", 1, CommandError.Code.INVALID_REQUEST, "Unknown field: connection")
                                : new CommandReply(1, id, "Fake", 1, "OK", "ok", null);
                        socket.getOutputStream().write((Json.write(reply) + "\n").getBytes(StandardCharsets.UTF_8));
                    } catch (IOException _) {
                        if (listener.isClosed()) { return; }
                    }
                }
            });
        }

        InetSocketAddress address() { return new InetSocketAddress(listener.getInetAddress(), listener.getLocalPort()); }

        @Override public void close() throws Exception { listener.close(); thread.join(5000); }
    }

    @Test void aHostThatRejectsKeepAliveIsAskedForOneRequestPerConnectionFromThenOn() throws Exception {
        var counts = new Counts();
        try (var older = new FakeHost(false); var client = new KeepAliveClient(settings(), counts)) {
            assertEquals("ok", client.execute(older.address(), "echo", Map.of("text", "x")).result());
            assertEquals("ok", client.execute(older.address(), "echo", Map.of("text", "y")).result());
            assertEquals(3, older.requests.size(), "Rejected once, then plain requests only");
            assertTrue(older.requests.get(0).contains("keep-alive"));
            assertFalse(older.requests.get(1).contains("keep-alive"));
            assertFalse(older.requests.get(2).contains("keep-alive"));
            assertEquals(0, client.idleConnections());
        }
    }

    @Test void aReplyLongerThanTheLimitIsRefusedBeforeParsing() throws Exception {
        var small = KeepAliveClient.Settings.of("Test", 64, Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(15));
        try (var running = new Running(); var client = new KeepAliveClient(small)) {
            var refused = assertThrows(KeepAliveClient.ReplyTooLargeException.class,
                    () -> echo(client, running.address(), "x".repeat(200)));
            assertEquals("Test reply exceeds 64 bytes before parsing", refused.getMessage());
            assertEquals(0, client.idleConnections(), "A connection with an unread reply on it is never reused");
        }
    }

    @Test void aLongReplyGrowsTheBufferAndArrivesWhole() throws Exception {
        try (var running = new Running(); var client = new KeepAliveClient(settings())) {
            String text = "y".repeat(40_000);
            assertEquals(text, echo(client, running.address(), text));
        }
    }

    @Test void aReplyThatIsNotInTheProtocolIsMalformed() throws Exception {
        try (var listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
             var client = new KeepAliveClient(settings())) {
            var server = Thread.ofVirtual().start(() -> {
                try (Socket socket = listener.accept()) {
                    new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
                    socket.getOutputStream().write("not json\n".getBytes(StandardCharsets.UTF_8));
                } catch (IOException ignored) { }
            });
            var address = new InetSocketAddress(listener.getInetAddress(), listener.getLocalPort());
            var failure = assertThrows(KeepAliveClient.MalformedReplyException.class,
                    () -> client.execute(address, "echo", Map.of("text", "x")));
            assertTrue(failure.getMessage().startsWith("Invalid message reply"), failure.getMessage());
            server.join(5000);
        }
    }

    @Test void aRequestTheNetworkLosesTimesOutAndOneItDelaysArrivesLate() throws Exception {
        var quick = KeepAliveClient.Settings.of("Test", 65_536, Duration.ofMillis(300), Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (var running = new Running();
             var faulty = new KeepAliveClient(quick, KeepAliveClient.Listener.NONE, Socket::new, "Server-A");
             var plain = new KeepAliveClient(quick)) {
            NetworkFaults.install((source, destination) -> new NetworkFaults.Fault(0, true));
            long start = System.nanoTime();
            var lost = assertThrows(java.net.SocketTimeoutException.class, () -> echo(faulty, running.address(), "x"));
            assertTrue(lost.getMessage().contains("injected network fault"), lost.getMessage());
            assertTrue(System.nanoTime() - start >= Duration.ofMillis(250).toNanos(), "Waited as an unanswered connect would");
            assertEquals("y", echo(plain, running.address(), "y"), "A client that sends for no server is never faulted");

            var seen = new CopyOnWriteArrayList<String>();
            NetworkFaults.install((source, destination) -> {
                seen.add(source + "->" + destination.getPort());
                return new NetworkFaults.Fault(200, false);
            });
            start = System.nanoTime();
            assertEquals("z", echo(faulty, running.address(), "z"));
            assertTrue(System.nanoTime() - start >= Duration.ofMillis(190).toNanos(), "Delayed");
            assertEquals(List.of("Server-A->" + running.server.getPort()), seen);
        } finally {
            NetworkFaults.clear();
        }
    }

    @Test void anObserverIsToldOfEachCallAClientMakesForAServerAndHowItEnded() throws Exception {
        var quick = KeepAliveClient.Settings.of("Test", 65_536, Duration.ofMillis(300), Duration.ofSeconds(5), Duration.ofSeconds(5));
        var started = new CopyOnWriteArrayList<PeerTraffic.Call>();
        var ended = new CopyOnWriteArrayList<PeerTraffic.Outcome>();
        var details = new CopyOnWriteArrayList<String>();
        int closedPort;
        try (var closed = new ServerSocket(0)) { closedPort = closed.getLocalPort(); }
        try (var running = new Running();
             var observed = new KeepAliveClient(quick, KeepAliveClient.Listener.NONE, Socket::new, "Server-A");
             var plain = new KeepAliveClient(quick)) {
            PeerTraffic.install(new PeerTraffic.Observer() {
                @Override public void started(PeerTraffic.Call call) { started.add(call); }
                @Override public void finished(PeerTraffic.Call call, PeerTraffic.Outcome outcome, String detail, long elapsedNanos) {
                    assertTrue(elapsedNanos >= 0);
                    ended.add(outcome);
                    details.add(detail);
                }
            });
            assertEquals("x", echo(observed, running.address(), "x"));
            var call = started.getFirst();
            assertEquals("Server-A", call.source());
            assertEquals(running.address(), call.destination());
            assertEquals("echo", call.instruction());
            assertNull(call.operation(), "Echo has no operation");
            assertEquals(36, call.messageId().length());
            assertTrue(call.requestBytes() > 0);
            assertEquals(List.of(PeerTraffic.Outcome.OK), ended);
            assertNull(details.getFirst());

            assertThrows(CommandExecutionException.class, () -> observed.execute(running.address(), "nope", Map.of("operation", "try")));
            assertEquals("nope", started.get(1).instruction());
            assertEquals("try", started.get(1).operation());
            assertEquals(PeerTraffic.Outcome.ERROR_REPLY, ended.get(1));
            assertEquals("UNKNOWN_INSTRUCTION", details.get(1));

            assertThrows(java.net.SocketException.class, () -> observed.execute(new InetSocketAddress("127.0.0.1", closedPort), "echo", Map.of("text", "x")));
            assertEquals(PeerTraffic.Outcome.REFUSED, ended.get(2));

            NetworkFaults.install((source, destination) -> new NetworkFaults.Fault(0, true));
            assertThrows(java.net.SocketTimeoutException.class, () -> echo(observed, running.address(), "x"));
            assertEquals(PeerTraffic.Outcome.LOST, ended.get(3));
            assertTrue(details.get(3).contains("injected network fault"), details.get(3));
            NetworkFaults.clear();

            assertEquals("y", echo(plain, running.address(), "y"));
            assertEquals(4, started.size(), "A client that sends for no server is not watched");

            PeerTraffic.install(new PeerTraffic.Observer() {
                @Override public void started(PeerTraffic.Call watched) { throw new IllegalStateException("broken observer"); }
                @Override public void finished(PeerTraffic.Call watched, PeerTraffic.Outcome outcome, String detail, long elapsedNanos) {
                    throw new IllegalStateException("broken observer");
                }
            });
            assertEquals("z", echo(observed, running.address(), "z"), "A broken observer must not disturb the request");
        } finally {
            NetworkFaults.clear();
            PeerTraffic.clear();
        }
    }

    @Test void settingsRefuseLimitsThatCannotWork() {
        assertThrows(IllegalArgumentException.class, () -> KeepAliveClient.Settings.of(" ", 1, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> KeepAliveClient.Settings.of("T", 0, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> KeepAliveClient.Settings.of("T", 1, Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new KeepAliveClient.Settings("T", 1, Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(1), -1, Duration.ofSeconds(1), 1));
        assertThrows(IllegalArgumentException.class, () -> new KeepAliveClient.Settings("T", 1, Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(1), 1, Duration.ofSeconds(1), 0));
    }
}
