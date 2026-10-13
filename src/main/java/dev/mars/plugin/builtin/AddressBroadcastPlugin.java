package dev.mars.plugin.builtin;

import dev.mars.plugin.PluginContext;
import dev.mars.plugin.CommandPlugin;
import dev.mars.protocol.Json;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** One best-effort IPv4 UDP announcement per active broadcast-capable interface. */
public final class AddressBroadcastPlugin implements CommandPlugin {
    public static final int BROADCAST_PORT = 9002;
    private final Endpoints endpoints;
    private final Sender sender;
    private final int broadcastPort;

    public AddressBroadcastPlugin() { this(AddressBroadcastPlugin::networkEndpoints, AddressBroadcastPlugin::send); }

    AddressBroadcastPlugin(Endpoints endpoints, Sender sender) {
        this(endpoints, sender, BROADCAST_PORT);
    }

    AddressBroadcastPlugin(Endpoints endpoints, int broadcastPort) {
        this(endpoints, AddressBroadcastPlugin::send, broadcastPort);
    }

    private AddressBroadcastPlugin(Endpoints endpoints, Sender sender, int broadcastPort) {
        this.endpoints = endpoints;
        this.sender = sender;
        this.broadcastPort = broadcastPort;
    }

    @Override
    public void start(PluginContext context) {
        List<Endpoint> available;
        try { available = endpoints.get(); }
        catch (IOException e) {
            System.err.println("Server announcement unavailable | serverId=" + context.serverId()
                    + " | port=" + context.port() + " | reason=" + e.getMessage());
            return;
        }
        if (available.isEmpty()) {
            System.out.println("Server announcement skipped | serverId=" + context.serverId()
                    + " | port=" + context.port() + " | reason=no active IPv4 broadcast interface");
        }
        for (var endpoint : available.stream().distinct().toList()) {
            String address = endpoint.address().getHostAddress();
            String details = "serverId=" + context.serverId() + " | ip=" + address + " | port=" + context.port()
                    + " | adminPort=" + context.adminPort() + " | udp="
                    + endpoint.broadcast().getHostAddress() + ":" + broadcastPort;
            try {
                var announcement = new Announcement(1, "SERVER_STARTED", context.serverId(), address,
                        context.port(), context.adminPort());
                sender.send(endpoint, broadcastPort, Json.write(announcement).getBytes(StandardCharsets.UTF_8));
                System.out.println("Server announcement sent | " + details);
            } catch (IOException e) {
                // Discovery is advisory; restricted networking must not disable the TCP server.
                System.err.println("Server announcement failed | " + details + " | reason=" + e.getMessage());
            }
        }
    }

    private static List<Endpoint> networkEndpoints() throws IOException {
        var result = new ArrayList<Endpoint>();
        for (var network : NetworkInterface.networkInterfaces().toList()) {
            if (!network.isUp() || network.isLoopback()) { continue; }
            for (var bound : network.getInterfaceAddresses()) {
                if (bound.getAddress() instanceof Inet4Address && bound.getBroadcast() != null) {
                    result.add(new Endpoint(bound.getAddress(), bound.getBroadcast()));
                }
            }
        }
        return result;
    }

    private static void send(Endpoint endpoint, int port, byte[] payload) throws IOException {
        try (var socket = new DatagramSocket(new InetSocketAddress(endpoint.address(), 0))) {
            socket.setBroadcast(true);
            socket.send(new DatagramPacket(payload, payload.length, endpoint.broadcast(), port));
        }
    }

    public record Announcement(int version, String type, String serverId, String ip, int port, int adminPort) { }
    record Endpoint(InetAddress address, InetAddress broadcast) { }
    @FunctionalInterface interface Endpoints { List<Endpoint> get() throws IOException; }
    @FunctionalInterface interface Sender { void send(Endpoint endpoint, int port, byte[] payload) throws IOException; }
}
