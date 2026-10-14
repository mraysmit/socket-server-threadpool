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
import dev.mars.plugin.CommandPlugin;
import dev.mars.plugin.PluginRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ThreadPooledServerTest {
    @Test
    @Timeout(15)
    void requestsAreHandledConcurrentlyOnVirtualThreads() throws Exception {
        int clients = 8;
        var inside = new CountDownLatch(clients);
        var release = new CountDownLatch(1);
        var plugin = new CommandPlugin() {
            @Override public String instruction() { return "test.block"; }
            @Override public Object execute(Map<String, Object> parameters) throws Exception {
                inside.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return Thread.currentThread().isVirtual();
            }
        };
        var server = new ThreadPooledServer(0, "Server-001", PluginRegistry.of(List.of(plugin)));
        server.bind();
        var listener = Thread.ofVirtual().start(server);
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var replies = IntStream.range(0, clients).mapToObj(i -> callers.submit(() ->
                    new TextMessageClient("localhost", server.getPort(), "Server-001").execute("test.block", Map.of())))
                    .toList();
            assertTrue(inside.await(5, TimeUnit.SECONDS),
                    "All " + clients + " handlers must be inside execute() at the same time");
            release.countDown();
            var connections = new HashSet<Long>();
            for (var reply : replies) {
                var result = reply.get(5, TimeUnit.SECONDS);
                assertEquals(true, result.result(), "Handlers must run on virtual threads");
                assertTrue(connections.add(result.connectionId()));
            }
        } finally {
            release.countDown();
            server.stop();
            listener.join(5000);
        }
    }

    @Test
    @Timeout(15)
    void abortedConnectionIsLoggedOnceWithoutStackTraceOrHttpFallback() throws Exception {
        var server = new ThreadPooledServer(0, "Server-001");
        server.drainTimeout(Duration.ofMillis(200));
        server.bind();
        var listener = Thread.ofVirtual().start(server);
        var captured = new ByteArrayOutputStream();
        var originalErr = System.err;
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try (var stalled = new Socket("localhost", server.getPort())) {
            Thread.sleep(200); // Let the worker reach its blocking read before the drain interrupts it.
            server.stop();
            listener.join(5000);
        } finally {
            System.setErr(originalErr);
        }
        String log = captured.toString(StandardCharsets.UTF_8);
        assertFalse(listener.isAlive());
        assertFalse(log.contains("\tat "), "A client-side abort must not print a stack trace:\n" + log);
        assertFalse(log.contains("Failed to send error response"), "No HTTP fallback on a dead socket:\n" + log);
        assertEquals(1, log.lines().filter(line -> line.contains("Request aborted")).count(), log);
    }

    @Test
    @Timeout(15)
    void transientAcceptFailureDoesNotStopTheListener() throws Exception {
        var failures = new AtomicInteger(1);
        var server = new ThreadPooledServer(0, "Server-001") {
            @Override public synchronized void bind() throws IOException {
                if (serverSocket == null) {
                    serverSocket = new ServerSocket(0) {
                        @Override public Socket accept() throws IOException {
                            if (failures.getAndUpdate(remaining -> Math.max(0, remaining - 1)) > 0) {
                                throw new SocketException("Too many open files");
                            }
                            return super.accept();
                        }
                    };
                }
            }
        };
        server.bind();
        var listener = Thread.ofVirtual().start(server);
        try (var client = new Socket("localhost", server.getPort())) {
            client.setSoTimeout(5000);
            UUID id = UUID.randomUUID();
            new PrintWriter(client.getOutputStream(), true, StandardCharsets.UTF_8).println("message " + id + " retry");
            String[] reply = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
                    .readLine().split(" ", 5);
            assertEquals("reply", reply[0]);
            assertEquals("Received: retry", reply[4]);
            assertEquals(0, failures.get(), "The failing accept must have been attempted");
            assertTrue(listener.isAlive());
            assertFalse(server.isStopped());
        } finally {
            server.stop();
            listener.join(5000);
        }
    }

    @Test
    @Timeout(15)
    void connectionsBeyondTheLimitWaitForAnActiveOneToFinish() throws Exception {
        var server = new ThreadPooledServer(0, "Server-001");
        server.maxConnections(1);
        server.bind();
        var listener = Thread.ofVirtual().start(server);
        try (var holder = new Socket("localhost", server.getPort());
             var waiting = new Socket("localhost", server.getPort())) {
            // The holder connected first, so it is accepted first and occupies the only permit.
            var writer = new PrintWriter(waiting.getOutputStream(), true, StandardCharsets.UTF_8);
            var reader = new BufferedReader(new InputStreamReader(waiting.getInputStream(), StandardCharsets.UTF_8));
            UUID id = UUID.randomUUID();
            writer.println("message " + id + " queued");
            waiting.setSoTimeout(500);
            assertThrows(SocketTimeoutException.class, reader::readLine, "Second connection must wait for a permit");
            holder.close();
            waiting.setSoTimeout(5000);
            String[] reply = reader.readLine().split(" ", 5);
            assertEquals("reply", reply[0]);
            assertEquals(id.toString(), reply[1]);
            assertEquals("Received: queued", reply[4]);
        } finally {
            server.stop();
            listener.join(5000);
            assertFalse(listener.isAlive(), "Listener must exit after stop even while waiting for a permit");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Server-001", "123", "eu.server_1", "11111111-1111-4111-8111-111111111111", "  Server-001  "})
    void usesTheProvidedServerId(String id) {
        var server = new ThreadPooledServer(0, id);
        try {
            assertEquals(id.strip(), server.getServerId());
        } finally {
            server.requestExecutor.close();
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    void generatesDistinctUuidDefaultsForMissingOrBlankIds(String id) {
        var first = new ThreadPooledServer(0, id);
        var second = new ThreadPooledServer(0, id);
        var omitted = new ThreadPooledServer(0);
        try {
            assertDoesNotThrow(() -> UUID.fromString(first.getServerId()));
            assertDoesNotThrow(() -> UUID.fromString(second.getServerId()));
            assertDoesNotThrow(() -> UUID.fromString(omitted.getServerId()));
            assertNotEquals(first.getServerId(), second.getServerId());
            assertNotEquals(first.getServerId(), omitted.getServerId());
        } finally {
            first.requestExecutor.close();
            second.requestExecutor.close();
            omitted.requestExecutor.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"server A", "server\nA", "server\tA", "server|A", "server/A"})
    void rejectsIdsThatCannotBeUsedInTheProtocol(String id) {
        assertThrows(IllegalArgumentException.class, () -> new ThreadPooledServer(0, id));
    }
}
