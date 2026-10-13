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
package dev.mars.plugin.builtin;

import dev.mars.plugin.PluginContext;
import dev.mars.protocol.Json;
import dev.mars.server.ServerInstance;
import dev.mars.server.ServerOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class AddressBroadcastPluginTest {
    @Test
    void sendsUdpWithActualBoundPortsAndServerIdentity() throws Exception {
        var loopback = InetAddress.getByName("127.0.0.1");
        try (var receiver = new DatagramSocket(0, loopback);
             var server = ServerInstance.start(new ServerOptions("Server-001", 0, 0))) {
            receiver.setSoTimeout(3000);
            var endpoint = new AddressBroadcastPlugin.Endpoint(loopback, loopback);
            var plugin = new AddressBroadcastPlugin(() -> List.of(endpoint), receiver.getLocalPort());
            plugin.start(new PluginContext(server.getServerId(), server.getPort(), server.getAdminPort()));
            var packet = new DatagramPacket(new byte[2048], 2048);
            receiver.receive(packet);
            var reply = Json.read(new String(packet.getData(), packet.getOffset(), packet.getLength(),
                    java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("SERVER_STARTED", reply.path("type").asText());
            assertEquals(1, reply.path("version").asInt());
            assertEquals("Server-001", reply.path("serverId").asText());
            assertEquals("127.0.0.1", reply.path("ip").asText());
            assertEquals(server.getPort(), reply.path("port").asInt());
            assertEquals(server.getAdminPort(), reply.path("adminPort").asInt());
            System.out.println("Address broadcast test PASSED | received=" + reply);
        }
    }

    @Test
    void advertisesEachDistinctInterfaceAndContinuesAfterSendFailure() throws Exception {
        var first = endpoint("192.0.2.10", "192.0.2.255");
        var second = endpoint("198.51.100.20", "198.51.100.255");
        var attempts = new ArrayList<AddressBroadcastPlugin.Endpoint>();
        var plugin = new AddressBroadcastPlugin(() -> List.of(first, first, second), (target, port, payload) -> {
            assertEquals(9002, port);
            attempts.add(target);
            if (target.equals(first)) { throw new IOException("Network unavailable"); }
        });
        assertDoesNotThrow(() -> plugin.start(new PluginContext("Server-001", 9010, 9011)));
        assertEquals(List.of(first, second), attempts);
    }

    @Test
    void unavailableInterfacesDoNotPreventStartup() {
        var plugin = new AddressBroadcastPlugin(() -> { throw new IOException("Enumeration failed"); },
                (target, port, payload) -> fail("Nothing should be sent"));
        assertDoesNotThrow(() -> plugin.start(new PluginContext("Server-001", 9010, 9011)));
    }

    @Test
    void noBroadcastInterfacesRequiresNoSocket() {
        var plugin = new AddressBroadcastPlugin(List::of,
                (target, port, payload) -> fail("Nothing should be sent"));
        assertDoesNotThrow(() -> plugin.start(new PluginContext("Server-001", 9010, 9011)));
    }

    private static AddressBroadcastPlugin.Endpoint endpoint(String ip, String broadcast) throws Exception {
        return new AddressBroadcastPlugin.Endpoint(InetAddress.getByName(ip), InetAddress.getByName(broadcast));
    }
}
