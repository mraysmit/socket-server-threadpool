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
package dev.mars.server;

import dev.mars.client.TextMessageClient;
import dev.mars.client.MessageReply;
import dev.mars.protocol.Json;
import dev.mars.worker.WorkerRunnable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class ServerLifecycleTest {
    private ServerInstance instance;
    private ThreadPooledServer server;
    private Future<?> serverTask;
    private Future<?> monitorTask;
    private int serverPort;
    private int monitorPort;

    @BeforeEach
    void startServers() throws Exception {
        instance = ServerInstance.start(new ServerOptions("Server-001", 0, 0));
        server = instance.server();
        serverPort = instance.getPort();
        monitorPort = instance.getAdminPort();
        serverTask = instance.serverTask();
        monitorTask = instance.monitorTask();
        try (var client = awaitConnection(serverPort, serverTask)) {
            assertHttpResponse(client);
        }
        try (var client = awaitConnection(monitorPort, monitorTask)) {
            assertEquals("Server status: ALIVE", command(client, "status"));
        }
    }

    @AfterEach
    void stopServers() throws Exception {
        if (instance != null) {
            instance.close();
            if (monitorTask != null) {
                monitorTask.get(5, TimeUnit.SECONDS);
            }
            if (serverTask != null) {
                serverTask.get(5, TimeUnit.SECONDS);
            }
            assertTrue(server.requestExecutor.isTerminated(), "Worker threads must terminate after each test");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "STATUS", "StAtUs"})
    void statusCommandIsCaseInsensitive(String input) throws Exception {
        assertEquals("Server status: ALIVE", command(input));
    }

    @Test
    void unknownCommandDoesNotStopTheServer() throws Exception {
        assertEquals("Unknown command. Available commands: status, shutdown", command("invalid"));
        assertEquals("Server status: ALIVE", command("status"));
    }

    @Test
    void disconnectedAdminClientDoesNotStopMonitor() throws Exception {
        try (var client = connect(monitorPort)) {
            client.shutdownOutput();
            var reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            assertEquals("Unknown command. Available commands: status, shutdown", reader.readLine());
        }
        assertEquals("Server status: ALIVE", command("status"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"shutdown", "SHUTDOWN"})
    void shutdownClosesBothListenersAndStopsThreads(String input) throws Exception {
        assertEquals("Shutting down server...", command(input));
        monitorTask.get(5, TimeUnit.SECONDS);
        serverTask.get(5, TimeUnit.SECONDS);
        assertTrue(server.isStopped());
        assertTrue(server.requestExecutor.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(server.serverSocket.isClosed(), "Main listener socket must be closed");
        assertTrue(instance.monitor().serverSocket.isClosed(), "Admin listener socket must be closed");
        assertNotAnsweredByThisServer(serverPort, HTTP_REQUEST, HTTP_MARKER);
        assertNotAnsweredByThisServer(monitorPort, "status\n", ADMIN_MARKER);
    }

    @Test
    void closedListenerCheckDetectsAListenerThatIsStillOpen() {
        assertThrows(AssertionError.class, () -> assertNotAnsweredByThisServer(serverPort, HTTP_REQUEST, HTTP_MARKER));
        assertThrows(AssertionError.class, () -> assertNotAnsweredByThisServer(monitorPort, "status\n", ADMIN_MARKER));
    }

    @Test
    void statusReportsAnExternallyStoppedServer() throws Exception {
        server.stop();
        serverTask.get(5, TimeUnit.SECONDS);
        assertEquals("Server status: STOPPED", command("status"));
    }

    @Test
    void concurrentClientsReceiveCompleteHttpResponses() throws Exception {
        try (var clients = Executors.newVirtualThreadPerTaskExecutor()) {
            var requests = new ArrayList<Future<?>>();
            for (int i = 0; i < 12; i++) {
                requests.add(clients.submit(() -> {
                    try (var client = connect(serverPort)) {
                        assertHttpResponse(client);
                    }
                    return null;
                }));
            }
            for (var request : requests) {
                request.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void configuredServerIdAppearsInMessageReply() throws Exception {
        UUID messageId = UUID.randomUUID();
        var reply = new TextMessageClient("127.0.0.1", serverPort).send(messageId, "Hello Server-001");

        assertEquals("Server-001", server.getServerId());
        assertEquals("Server-001", reply.serverId());
        assertEquals(messageId, reply.messageId());
        assertEquals("Received: Hello Server-001", reply.text());
        System.out.println("Named server test PASSED: " + reply);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Hello server", "你好 🌍", "status", "shutdown", "  keep these spaces  "})
    void textMessagesReceiveUtf8Replies(String message) throws Exception {
        var client = new TextMessageClient("127.0.0.1", serverPort);
        UUID messageId = UUID.randomUUID();
        var reply = client.send(messageId, message);
        assertEquals(messageId, reply.messageId());
        assertEquals(server.getServerId(), reply.serverId());
        assertTrue(reply.connectionId() > 0);
        assertEquals("Received: " + message, reply.text());
        assertEquals("Server status: ALIVE", command("status"));
        try (var httpClient = connect(serverPort)) {
            assertHttpResponse(httpClient);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"message", "MESSAGE", "MeSsAgE"})
    void textProtocolIsCaseInsensitive(String input) throws Exception {
        UUID messageId = UUID.randomUUID();
        try (var client = connect(serverPort)) {
            String[] fields = command(client, input + " " + messageId + " Hello").split(" ", 5);
            assertEquals(5, fields.length);
            assertEquals("reply", fields[0]);
            assertEquals(messageId.toString(), fields[1]);
            assertEquals(server.getServerId().toString(), fields[2]);
            assertTrue(Long.parseLong(fields[3]) > 0);
            assertEquals("Received: Hello", fields[4]);
            assertEquals(-1, client.getInputStream().read(), "Connection closes after the reply");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   "})
    void emptyTextMessageReceivesAnError(String input) throws Exception {
        UUID messageId = UUID.randomUUID();
        try (var client = connect(serverPort)) {
            String[] fields = command(client, "message " + messageId + " " + input).split(" ", 5);
            assertEquals(5, fields.length);
            assertEquals("error", fields[0]);
            assertEquals(messageId.toString(), fields[1]);
            assertEquals(server.getServerId().toString(), fields[2]);
            assertEquals("Message must not be empty", fields[4]);
        }
        assertEquals("Server status: ALIVE", command("status"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"message", "message ", "message not-a-uuid Hello"})
    void missingOrInvalidMessageIdReceivesAnError(String input) throws Exception {
        try (var client = connect(serverPort)) {
            assertTrue(command(client, input).startsWith("Error:"));
        }
        assertEquals("Server status: ALIVE", command("status"));
    }

    @Test
    void identicalMessagesHaveDifferentIdsAndStableServerIdentity() throws Exception {
        var client = new TextMessageClient("127.0.0.1", serverPort);
        var first = client.send("Hello");
        var second = client.send("Hello");
        assertNotEquals(first.messageId(), second.messageId());
        assertNotEquals(first.connectionId(), second.connectionId());
        assertEquals(server.getServerId(), first.serverId());
        assertEquals(first.serverId(), second.serverId());
        assertEquals(first.text(), second.text());
    }

    @Test
    void twoServersIdentifyWhichInstanceRepliedToTheMessage() throws Exception {
        try (var other = ServerInstance.start(new ServerOptions("Server-002", 0, 0))) {
                UUID messageId = UUID.randomUUID();
                var first = new TextMessageClient("127.0.0.1", serverPort).send(messageId, "Hello");
                var second = new TextMessageClient("127.0.0.1", other.getPort()).send(messageId, "Hello");
                assertEquals(messageId, first.messageId());
                assertEquals(messageId, second.messageId());
                assertEquals(server.getServerId(), first.serverId());
                assertEquals(other.getServerId(), second.serverId());
                assertEquals("Server-001", first.serverId());
                assertEquals("Server-002", second.serverId());
                assertNotEquals(first.serverId(), second.serverId());
                assertEquals("Received: Hello", first.text());
                assertEquals("Received: Hello", second.text());
        }
    }

    @Test
    void concurrentTextMessagesReceiveTheirOwnReplies() throws Exception {
        try (var clients = Executors.newVirtualThreadPerTaskExecutor()) {
            var replies = new ArrayList<Future<MessageReply>>();
            for (int i = 0; i < 12; i++) {
                String message = "Message " + i + " 你好";
                replies.add(clients.submit(() -> {
                    var reply = new TextMessageClient("127.0.0.1", serverPort).send(message);
                    assertEquals("Received: " + message, reply.text());
                    assertEquals(server.getServerId(), reply.serverId());
                    return reply;
                }));
            }
            var messageIds = new HashSet<UUID>();
            for (var reply : replies) {
                assertTrue(messageIds.add(reply.get(5, TimeUnit.SECONDS).messageId()),
                        "Each sent message must have a unique ID");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void requestLineLongerThanTheLimitIsRejectedWithoutBufferingIt(int excess) throws Exception {
        int limit = WorkerRunnable.MAX_LINE_LENGTH;
        var line = new byte[limit + excess];
        Arrays.fill(line, (byte) 'a');
        try (var client = connect(serverPort)) {
            var out = client.getOutputStream();
            out.write(line);
            if (excess == 0) { out.write('\n'); }
            out.flush();
            var reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            var reply = Json.read(reader.readLine());
            assertEquals("ERROR", reply.path("status").asText());
            assertEquals("INVALID_REQUEST", reply.path("error").path("code").asText());
            String message = reply.path("error").path("message").asText();
            if (excess == 0) {
                assertEquals("Malformed JSON request", message, "A line at the limit is parsed normally");
            } else {
                assertTrue(message.contains(String.valueOf(limit)), message);
            }
            assertNull(reader.readLine(), "Connection closes after the reply");
        }
        assertEquals("Server status: ALIVE", command("status"));
    }

    @Test
    void requestTasksRunOnVirtualThreads() throws Exception {
        assertTrue(server.requestExecutor.submit(() -> Thread.currentThread().isVirtual())
                .get(5, TimeUnit.SECONDS));
    }

    @Test
    void shutdownWaitsForAcceptedWorkToFinish() throws Exception {
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var work = server.requestExecutor.submit(() -> {
            started.countDown();
            finish.await();
            return null;
        });
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            server.stop();
            assertThrows(TimeoutException.class, () -> serverTask.get(100, TimeUnit.MILLISECONDS));
        } finally {
            finish.countDown();
        }
        work.get(5, TimeUnit.SECONDS);
        serverTask.get(5, TimeUnit.SECONDS);
        assertTrue(server.requestExecutor.isTerminated());
    }

    @Test
    @Timeout(45)
    void handlesOneThousandRequests() throws Exception {
        int requestCount = 1000;
        long started = System.nanoTime();
        System.out.println(String.format(Locale.ROOT,
                "Load test: sending %,d HTTP requests to 127.0.0.1:%d (up to 100 in flight)",
                requestCount, serverPort));
        var inFlight = new Semaphore(100);
        var clients = Executors.newVirtualThreadPerTaskExecutor();
        var requests = new ArrayList<Future<?>>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        try {
            for (int i = 0; i < requestCount; i++) {
                requests.add(clients.submit(() -> {
                    inFlight.acquire();
                    try (var client = connect(serverPort)) {
                        assertHttpResponse(client);
                    } finally {
                        inFlight.release();
                    }
                    return null;
                }));
            }
            int completed = 0;
            for (var request : requests) {
                long remaining = deadline - System.nanoTime();
                assertTrue(remaining > 0, "1,000 requests must complete within 30 seconds");
                request.get(remaining, TimeUnit.NANOSECONDS);
                completed++;
                if (completed % 100 == 0) {
                    System.out.println(String.format(Locale.ROOT,
                            "Load test: %,d / %,d responses verified", completed, requestCount));
                }
            }
            assertEquals(requestCount, completed);
            assertEquals("Server status: ALIVE", command("status"));
            System.out.println(String.format(Locale.ROOT,
                    "Load test PASSED: %,d responses verified in %d ms; server is ALIVE",
                    completed, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
        } finally {
            clients.shutdownNow();
            assertTrue(clients.awaitTermination(10, TimeUnit.SECONDS),
                    "Load test clients must terminate");
        }
    }

    private String command(String input) throws IOException {
        try (var client = connect(monitorPort)) {
            return command(client, input);
        }
    }

    private static String command(Socket client, String input) throws IOException {
        var writer = new PrintWriter(client.getOutputStream(), true, StandardCharsets.UTF_8);
        var reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        writer.println(input);
        return reader.readLine();
    }

    private static void assertHttpResponse(Socket client) throws IOException {
        var writer = new PrintWriter(client.getOutputStream(), false, StandardCharsets.UTF_8);
        writer.print("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
        writer.flush();
        assertFalse(writer.checkError(), "HTTP request must be sent successfully");
        // Reading through EOF also verifies that the worker releases its connection.
        String response = new String(client.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int separator = response.indexOf("\r\n\r\n");
        assertTrue(separator >= 0, "Response is missing HTTP header separator");
        String headers = response.substring(0, separator);
        String body = response.substring(separator + 4);
        assertTrue(headers.startsWith("HTTP/1.1 200 OK\r\n"));
        assertTrue(headers.contains("\r\nContent-Type: text/plain; charset=UTF-8"));
        assertTrue(headers.contains("\r\nContent-Length: " + body.getBytes(StandardCharsets.UTF_8).length));
        assertTrue(body.matches("WorkerRunnable: ThreadPooledServer - \\d+"), body);
    }

    private static Socket awaitConnection(int port, Future<?> task) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            try {
                return connect(port);
            } catch (ConnectException e) {
                if (task.isDone()) {
                    task.get(); // Surface startup failures rather than hiding them behind a timeout.
                    throw new IllegalStateException("Server exited before accepting connections", e);
                }
                if (System.nanoTime() >= deadline) {
                    throw e;
                }
                Thread.sleep(10);
            }
        }
    }

    private static final String HTTP_REQUEST = "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
    private static final String HTTP_MARKER = "WorkerRunnable: ThreadPooledServer";
    private static final String ADMIN_MARKER = "Server status:";

    /**
     * A closed listener's automatic port returns to the OS pool, so a connect can still succeed through another
     * program's listener or a TCP self-connect (which echoes the request). Only this server's own reply proves
     * that one of its listeners is still open.
     */
    private static void assertNotAnsweredByThisServer(int port, String request, String marker) throws IOException {
        Socket client;
        try { client = connect(port); }
        catch (ConnectException refused) { return; }
        try (client) {
            client.setSoTimeout(500);
            var reply = new java.io.ByteArrayOutputStream();
            try {
                client.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
                client.getOutputStream().flush();
                byte[] chunk = new byte[512];
                for (int read; reply.size() < 8192 && (read = client.getInputStream().read(chunk)) > 0; ) { reply.write(chunk, 0, read); }
            } catch (IOException silentOrReset) { /* Whatever answered was not this server. */ }
            assertFalse(reply.toString(StandardCharsets.UTF_8).contains(marker), "Port " + port + " is still answered by this server: local="
                    + client.getLocalSocketAddress() + " remote=" + client.getRemoteSocketAddress());
        }
    }

    private static Socket connect(int port) throws IOException {
        var client = new Socket();
        try {
            client.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            client.setSoTimeout(10000);
            return client;
        } catch (IOException e) {
            client.close();
            throw e;
        }
    }
}
