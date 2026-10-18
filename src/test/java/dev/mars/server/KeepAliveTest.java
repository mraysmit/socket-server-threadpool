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

import dev.mars.protocol.CommandProtocol;
import dev.mars.protocol.CommandReply;
import dev.mars.worker.WorkerRunnable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** A client may ask the server to keep a connection open for further requests; without asking, nothing changes. */
@Timeout(30)
class KeepAliveTest {
    private static String request(UUID id, String text, String connection) {
        return "{\"version\":1,\"messageId\":\"" + id + "\",\"instruction\":\"echo\",\"parameters\":{\"text\":\"" + text + "\"}"
                + (connection == null ? "" : ",\"connection\":\"" + connection + "\"") + "}";
    }

    private static final class Running implements AutoCloseable {
        final ThreadPooledServer server = new ThreadPooledServer(0, "Server-001");
        final Thread listener;

        Running(Duration idle) throws IOException {
            if (idle != null) { server.keepAliveIdle(idle); }
            server.bind();
            listener = Thread.ofVirtual().start(server);
        }

        Client connect() throws IOException { return new Client(new Socket("localhost", server.getPort())); }

        @Override public void close() throws Exception {
            server.stop();
            listener.join(10_000);
        }
    }

    private static final class Client implements AutoCloseable {
        final Socket socket;
        final BufferedReader in;
        final OutputStream out;

        Client(Socket socket) throws IOException {
            this.socket = socket;
            socket.setSoTimeout(5000);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = socket.getOutputStream();
        }

        void send(String line) throws IOException { out.write((line + "\n").getBytes(StandardCharsets.UTF_8)); out.flush(); }

        CommandReply reply() throws IOException {
            String line = in.readLine();
            assertNotNull(line, "The server closed the connection without replying");
            return CommandProtocol.parseReply(line);
        }

        /** True once the server has closed the connection (a read returns end of stream). */
        boolean closedByServer(int waitMillis) throws IOException {
            socket.setSoTimeout(waitMillis);
            try { return in.readLine() == null; }
            catch (SocketTimeoutException stillOpen) { return false; }
            catch (java.net.SocketException reset) { return true; } // the server closed it with a reset
        }

        @Override public void close() throws IOException { socket.close(); }
    }

    @Test
    void keepAliveServesSeveralRequestsOnOneConnection() throws Exception {
        try (var running = new Running(null); var client = running.connect()) {
            long connectionId = -1;
            for (int i = 0; i < 5; i++) {
                var id = UUID.randomUUID();
                client.send(request(id, "hello " + i, "keep-alive"));
                var reply = client.reply();
                assertEquals(id, reply.messageId());
                assertEquals("Received: hello " + i, reply.result());
                if (connectionId < 0) { connectionId = reply.connectionId(); }
                assertEquals(connectionId, reply.connectionId(), "Every reply comes from the same connection");
            }
            assertFalse(client.closedByServer(300), "The connection stays open between requests");
        }
    }

    @Test
    void withoutTheFieldOrWithCloseTheConnectionClosesAfterOneReply() throws Exception {
        try (var running = new Running(null)) {
            for (String connection : new String[]{null, "close"}) {
                try (var client = running.connect()) {
                    var id = UUID.randomUUID();
                    client.send(request(id, "once", connection));
                    assertEquals(id, client.reply().messageId());
                    assertTrue(client.closedByServer(3000), "connection=" + connection + " must close after the reply");
                }
            }
        }
    }

    @Test
    void theLastRequestWithoutKeepAliveEndsAKeptOpenConnection() throws Exception {
        try (var running = new Running(null); var client = running.connect()) {
            client.send(request(UUID.randomUUID(), "one", "keep-alive"));
            client.reply();
            client.send(request(UUID.randomUUID(), "two", null));
            assertEquals("Received: two", client.reply().result());
            assertTrue(client.closedByServer(3000));
        }
    }

    @Test
    void anInvalidConnectionValueIsRejectedAndTheConnectionClosed() throws Exception {
        try (var running = new Running(null)) {
            for (String bad : new String[]{"forever", "KEEP-ALIVE", ""}) {
                try (var client = running.connect()) {
                    client.send(request(UUID.randomUUID(), "x", bad));
                    var line = client.in.readLine();
                    var reply = CommandProtocol.parseReply(line == null ? "" : line);
                    assertEquals("ERROR", reply.status());
                    assertEquals("INVALID_REQUEST", reply.error().code().name());
                    assertTrue(reply.error().message().contains("keep-alive"), reply.error().message());
                    assertTrue(client.closedByServer(3000));
                }
            }
        }
    }

    @Test
    void anIdleKeptOpenConnectionIsClosedAfterTheIdleLimit() throws Exception {
        try (var running = new Running(Duration.ofMillis(400)); var client = running.connect()) {
            client.send(request(UUID.randomUUID(), "hi", "keep-alive"));
            client.reply();
            assertTrue(client.closedByServer(3000), "The server gives up on a connection that stays silent");
        }
    }

    @Test
    void stoppingTheServerClosesIdleKeptOpenConnectionsWithoutWaitingOutTheDrain() throws Exception {
        var running = new Running(null);
        try (var client = running.connect()) {
            client.send(request(UUID.randomUUID(), "hi", "keep-alive"));
            client.reply();
            long began = System.nanoTime();
            running.server.stop();
            running.listener.join(10_000);
            long millis = (System.nanoTime() - began) / 1_000_000;
            assertFalse(running.listener.isAlive());
            assertTrue(millis < 1000, "Shutdown took " + millis + " ms; idle kept-open connections must be closed at once, not wait out the 5000 ms drain");
            assertTrue(client.closedByServer(2000));
        }
    }

    @Test
    void aClientThatResetsAnIdleConnectionEndsItWithoutAnErrorInTheServerLog() throws Exception {
        var captured = new java.io.ByteArrayOutputStream();
        var originalErr = System.err;
        System.setErr(new java.io.PrintStream(captured, true, StandardCharsets.UTF_8));
        try (var running = new Running(null)) {
            try (var client = running.connect()) {
                client.send(request(UUID.randomUUID(), "hi", "keep-alive"));
                client.reply();
                client.socket.setSoLinger(true, 0); // closing now sends a reset instead of a closing handshake
            }
            Thread.sleep(300);
            running.server.stop();
            running.listener.join(10_000);
        } finally {
            System.setErr(originalErr);
        }
        String log = captured.toString(StandardCharsets.UTF_8);
        assertFalse(log.contains("Request aborted"), "A reset between requests is a normal end, not an error: " + log);
    }

    @Test
    void aRequestThatArrivesInPiecesAcrossAnIdlePollIsNotLost() throws Exception {
        try (var running = new Running(null); var client = running.connect()) {
            client.send(request(UUID.randomUUID(), "first", "keep-alive"));
            client.reply();
            var id = UUID.randomUUID();
            String line = request(id, "split across polls", "keep-alive") + "\n";
            byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
            int cut = bytes.length / 2;
            client.out.write(bytes, 0, cut);
            client.out.flush();
            Thread.sleep(700); // longer than the 250 ms idle poll, so the read times out mid-line
            client.out.write(bytes, cut, bytes.length - cut);
            client.out.flush();
            var reply = client.reply();
            assertEquals(id, reply.messageId());
            assertEquals("Received: split across polls", reply.result());
        }
    }

    @Test
    void aKeptOpenConnectionEndsAfterTheRequestLimit() throws Exception {
        try (var running = new Running(null); var client = running.connect()) {
            for (int i = 1; i <= WorkerRunnable.MAX_REQUESTS_PER_CONNECTION; i++) {
                var id = UUID.randomUUID();
                client.send(request(id, "n" + i, "keep-alive"));
                assertEquals(id, client.reply().messageId(), "request " + i);
            }
            assertTrue(client.closedByServer(3000), "The server closes after " + WorkerRunnable.MAX_REQUESTS_PER_CONNECTION + " requests");
        }
    }

    @Test
    void anOversizedLineOnALaterRequestIsRejectedAndTheConnectionClosed() throws Exception {
        try (var running = new Running(null); var client = running.connect()) {
            client.send(request(UUID.randomUUID(), "ok", "keep-alive"));
            client.reply();
            client.send("x".repeat(WorkerRunnable.MAX_LINE_LENGTH + 10));
            // No message ID can be named for a line that was never read, so the reply is checked as text.
            String line = client.in.readLine();
            assertNotNull(line);
            assertTrue(line.contains("\"INVALID_REQUEST\"") && line.contains("exceeds"), line);
            assertTrue(client.closedByServer(3000));
        }
    }

    @Test
    void aClientThatClosesBetweenRequestsLeavesNoWorkerBehind() throws Exception {
        try (var running = new Running(null)) {
            for (int i = 0; i < 20; i++) {
                try (var client = running.connect()) {
                    client.send(request(UUID.randomUUID(), "c" + i, "keep-alive"));
                    client.reply();
                }
            }
            long began = System.nanoTime();
            running.server.stop();
            running.listener.join(10_000);
            assertFalse(running.listener.isAlive());
            assertTrue((System.nanoTime() - began) / 1_000_000 < 2000);
        }
    }
}
