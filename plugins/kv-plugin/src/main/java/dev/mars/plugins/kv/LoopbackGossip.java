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
package dev.mars.plugins.kv;

import dev.mars.client.KeepAliveClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;

/**
 * Calls the gossip instruction on this server's own main port, as any client could, over a connection kept open between
 * calls. Every call only reads, so it is safe to send twice.
 */
final class LoopbackGossip implements Gossip {
    private final InetSocketAddress address;
    private final KeepAliveClient connections;

    LoopbackGossip(int port) { this(port, TcpPeerLink.newClient()); }

    LoopbackGossip(int port, KeepAliveClient connections) {
        address = new InetSocketAddress("localhost", port);
        this.connections = java.util.Objects.requireNonNull(connections);
    }

    @Override public Map<String, Object> call(Map<String, Object> parameters) throws IOException {
        var reply = connections.execute(address, "gossip", parameters);
        if (!(reply.result() instanceof Map<?, ?> result)) { throw new IOException("Gossip's reply is not an object"); }
        @SuppressWarnings("unchecked") var typed = (Map<String, Object>) result;
        return typed;
    }
}
