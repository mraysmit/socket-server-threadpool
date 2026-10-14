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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class ServerInstanceTest {
    @Test
    void bindsDistinctAutomaticPortsAndReleasesBothOnRepeatedClose() throws Exception {
        var instance = ServerInstance.start(new ServerOptions("Server-001", 0, 0));
        int main = instance.getPort();
        int admin = instance.getAdminPort();
        try (instance) {
            assertTrue(main > 0);
            assertTrue(admin > 0);
            assertNotEquals(main, admin);
            assertHealthy(instance);
            var mainListener = instance.server().runningThread;
            var adminListener = instance.monitor().runningThread;
            assertNotNull(mainListener);
            assertNotNull(adminListener);
            assertTrue(mainListener.isVirtual(), "Main listener must run on a virtual thread");
            assertTrue(adminListener.isVirtual(), "Admin listener must run on a virtual thread");
            assertNotSame(mainListener, adminListener);
        }
        instance.close();
        assertReleased(main, admin);
        assertTrue(instance.server().requestExecutor.isTerminated());
        assertTrue(instance.serverTask().isDone());
        assertTrue(instance.monitorTask().isDone());
    }

    @Test
    void usesExplicitPorts() throws Exception {
        int main;
        int admin;
        try (var a = new ServerSocket(0); var b = new ServerSocket(0)) {
            main = a.getLocalPort(); admin = b.getLocalPort();
        }
        try (var instance = ServerInstance.start(new ServerOptions("Server-001", main, admin))) {
            assertEquals(main, instance.getPort());
            assertEquals(admin, instance.getAdminPort());
            assertHealthy(instance);
        }
        assertReleased(main, admin);
    }

    @Test
    void occupiedMainPortFailsWithoutDisturbingItsOwner() throws Exception {
        try (var occupied = new ServerSocket(0)) {
            IOException error = assertThrows(IOException.class, () -> ServerInstance.start(
                    new ServerOptions("Blocked", occupied.getLocalPort(), 0)));
            assertTrue(error.getMessage().contains("Main port " + occupied.getLocalPort()));
            assertTrue(error.getMessage().contains("already in use"));
            assertFalse(occupied.isClosed());
        }
    }

    @Test
    void occupiedAdminPortRollsBackTheMainBinding() throws Exception {
        int main;
        try (var reservation = new ServerSocket(0)) { main = reservation.getLocalPort(); }
        try (var occupied = new ServerSocket(0)) {
            IOException error = assertThrows(IOException.class, () -> ServerInstance.start(
                    new ServerOptions("Blocked", main, occupied.getLocalPort())));
            assertTrue(error.getMessage().contains("Admin port " + occupied.getLocalPort()));
            assertTrue(error.getMessage().contains("already in use"));
            try (var rebound = new ServerSocket(main)) { assertEquals(main, rebound.getLocalPort()); }
            assertFalse(occupied.isClosed());
        }
    }

    @Test
    void bothOccupiedPortsFailCleanly() throws Exception {
        try (var main = new ServerSocket(0); var admin = new ServerSocket(0)) {
            assertThrows(IOException.class, () -> ServerInstance.start(
                    new ServerOptions("Blocked", main.getLocalPort(), admin.getLocalPort())));
            assertFalse(main.isClosed());
            assertFalse(admin.isClosed());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"main-main", "admin-admin", "main-admin", "admin-main"})
    void collisionsWithAnotherInstanceLeaveTheOriginalHealthy(String scenario) throws Exception {
        try (var owner = ServerInstance.start(new ServerOptions("Owner", 0, 0))) {
            int occupied = scenario.endsWith("main") ? owner.getPort() : owner.getAdminPort();
            int main = scenario.startsWith("main") ? occupied : 0;
            int admin = scenario.startsWith("admin") ? occupied : 0;
            assertThrows(IOException.class, () -> ServerInstance.start(new ServerOptions("Blocked", main, admin)));
            assertHealthy(owner);
        }
    }

    @Test
    void sixInstancesCanStartSimultaneouslyAndReplyWithTheirOwnIds() throws Exception {
        var opened = new ConcurrentLinkedQueue<ServerInstance>();
        try {
            try (var starters = Executors.newVirtualThreadPerTaskExecutor()) {
                var ready = new CountDownLatch(6);
                var go = new CountDownLatch(1);
                var futures = new ArrayList<Future<ServerInstance>>();
                for (int i = 1; i <= 6; i++) {
                    String id = "Server-00" + i;
                    futures.add(starters.submit(() -> {
                        ready.countDown();
                        assertTrue(go.await(5, TimeUnit.SECONDS));
                        var instance = ServerInstance.start(new ServerOptions(id, 0, 0));
                        opened.add(instance);
                        return instance;
                    }));
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                go.countDown();
                var ports = new HashSet<Integer>();
                for (var future : futures) {
                    var instance = future.get(5, TimeUnit.SECONDS);
                    assertTrue(ports.add(instance.getPort()));
                    assertTrue(ports.add(instance.getAdminPort()));
                    assertHealthy(instance);
                }
                assertEquals(12, ports.size());
            }
        } finally {
            for (var instance : opened) { instance.close(); }
        }
    }

    @Test
    void racingForTheSamePortPairHasOneWinnerAndOneCleanFailure() throws Exception {
        int main;
        int admin;
        try (var a = new ServerSocket(0); var b = new ServerSocket(0)) {
            main = a.getLocalPort(); admin = b.getLocalPort();
        }
        var opened = new ConcurrentLinkedQueue<ServerInstance>();
        try {
            try (var starters = Executors.newVirtualThreadPerTaskExecutor()) {
                var ready = new CountDownLatch(2);
                var go = new CountDownLatch(1);
                var attempts = new ArrayList<Future<Object>>();
                for (int i = 1; i <= 2; i++) {
                    String id = "Racer-" + i;
                    attempts.add(starters.submit(() -> {
                        ready.countDown();
                        assertTrue(go.await(5, TimeUnit.SECONDS));
                        try {
                            var instance = ServerInstance.start(new ServerOptions(id, main, admin));
                            opened.add(instance);
                            return instance;
                        } catch (IOException e) { return e; }
                    }));
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                go.countDown();
                int winners = 0;
                int failures = 0;
                for (var attempt : attempts) {
                    Object result = attempt.get(5, TimeUnit.SECONDS);
                    if (result instanceof ServerInstance winner) {
                        winners++;
                        assertHealthy(winner);
                    } else {
                        failures++;
                        assertTrue(((IOException) result).getMessage().contains("already in use"));
                    }
                }
                assertEquals(1, winners);
                assertEquals(1, failures);
            }
        } finally {
            for (var instance : opened) { instance.close(); }
        }
        assertReleased(main, admin);
    }

    @Test
    void clientCanRequireTheExpectedServerIdentity() throws Exception {
        try (var instance = ServerInstance.start(new ServerOptions("Server-001", 0, 0))) {
            var wrong = new TextMessageClient("localhost", instance.getPort(), "Server-002");
            IOException error = assertThrows(IOException.class, () -> wrong.send("Hello"));
            assertTrue(error.getMessage().contains("Server ID mismatch"));
            assertHealthy(instance);
        }
    }

    @Test
    void idleAdminConnectionDoesNotBlockClose() throws Exception {
        var instance = ServerInstance.start(new ServerOptions("Server-001", 0, 0));
        try (instance; var idle = new Socket("localhost", instance.getAdminPort())) {
            assertTimeoutPreemptively(Duration.ofSeconds(2), instance::close);
        }
        assertReleased(instance.getPort(), instance.getAdminPort());
    }

    private static void assertHealthy(ServerInstance instance) throws IOException {
        var reply = new TextMessageClient("localhost", instance.getPort(), instance.getServerId()).send("Hello");
        assertEquals(instance.getServerId(), reply.serverId());
        assertEquals("Received: Hello", reply.text());
        try (var socket = new Socket("localhost", instance.getAdminPort());
             var out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
             var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            socket.setSoTimeout(3000);
            out.println("status");
            assertEquals("Server status: ALIVE", in.readLine());
        }
    }

    private static void assertReleased(int main, int admin) throws IOException {
        try (var a = new ServerSocket(main); var b = new ServerSocket(admin)) {
            assertEquals(main, a.getLocalPort());
            assertEquals(admin, b.getLocalPort());
        }
    }
}
