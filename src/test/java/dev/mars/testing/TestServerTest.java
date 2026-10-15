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
import dev.mars.plugin.CommandPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class TestServerTest {
    @Test
    void servesCallerPluginsAndEchoAndStopsThemOnClose() throws Exception {
        var stopped = new AtomicBoolean();
        var plugin = new CommandPlugin() {
            @Override public String instruction() { return "test.answer"; }
            @Override public Object execute(Map<String, Object> parameters) { return 42; }
            @Override public void stop() { stopped.set(true); }
        };
        int port;
        try (var server = TestServer.start("Support-001", plugin)) {
            port = server.getPort();
            var client = new TextMessageClient("localhost", port, "Support-001");
            assertEquals("42", client.execute("test.answer", Map.of()).result().toString());
            assertEquals("Received: hello", client.send("hello").text());
        }
        assertTrue(stopped.get(), "Closing the server must stop caller plugins");
        try (var released = new ServerSocket(port)) { assertEquals(port, released.getLocalPort()); }
    }

    @Test
    void usesRequestedMainPort() throws Exception {
        int port;
        try (var reservation = new ServerSocket(0)) { port = reservation.getLocalPort(); }
        try (var server = TestServer.start("Support-002", port)) {
            assertEquals(port, server.getPort());
        }
    }
}
