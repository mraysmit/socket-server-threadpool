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

import dev.mars.client.KeepAliveClient;
import dev.mars.plugin.RunGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** A frozen server holds its connections and answers nothing; thawed, it answers what waited. Stopping it never hangs. */
@Timeout(30)
class FreezeTest {
    private static String echo(String text) {
        return "{\"version\":1,\"messageId\":\"" + UUID.randomUUID() + "\",\"instruction\":\"echo\",\"parameters\":{\"text\":\"" + text + "\"}}\n";
    }

    @Test void aFrozenServerAnswersNothingAndAnswersWhatWaitedOnceThawed() throws Exception {
        try (var instance = ServerInstance.start(new ServerOptions("Server-001", 0, 0))) {
            var settings = KeepAliveClient.Settings.of("Test", 65_536, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1));
            var address = new InetSocketAddress("127.0.0.1", instance.getPort());
            try (var client = new KeepAliveClient(settings)) {
                // A connection already open before the freeze: its next request is read but not answered.
                client.execute(address, "echo", Map.of("text", "before"));
                instance.freeze();
                assertTrue(instance.frozen());
                assertThrows(SocketTimeoutException.class, () -> client.execute(address, "echo", Map.of("text", "frozen")));
            }
            // A new connection is taken by the kernel but never accepted, so its request waits until the thaw.
            try (var socket = new Socket()) {
                socket.connect(address, 1000);
                socket.getOutputStream().write(echo("waited").getBytes(StandardCharsets.UTF_8));
                socket.setSoTimeout(500);
                var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                assertThrows(SocketTimeoutException.class, in::readLine);
                instance.thaw();
                socket.setSoTimeout(5000);
                assertTrue(in.readLine().contains("Received: waited"));
            }
        }
    }

    @Test void stoppingAFrozenServerDoesNotWaitForAThaw() throws Exception {
        var instance = ServerInstance.start(new ServerOptions("Server-001", 0, 0));
        instance.freeze();
        long start = System.nanoTime();
        instance.close();
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(10), "Closed without a thaw");
        assertFalse(instance.frozen());
    }

    @Test void theGateWaitsOnlyWhileFrozen() throws Exception {
        var gate = new RunGate();
        gate.awaitRunning();
        gate.freeze();
        var waiter = Thread.ofVirtual().start(() -> {
            try { gate.awaitRunning(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        waiter.join(200);
        assertTrue(waiter.isAlive(), "Waits while frozen");
        gate.thaw();
        waiter.join(2000);
        assertFalse(waiter.isAlive(), "Released by the thaw");
    }
}
