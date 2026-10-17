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
package dev.mars.plugins.gossip;

import dev.mars.client.CommandExecutionException;

import java.net.SocketTimeoutException;
import java.nio.charset.CharacterCodingException;

/** Why an exchange failed. Failures the plugin detects carry their kind, and metrics count failures by it. */
enum GossipFailure {
    DNS, NETWORK, TIMEOUT, PROTOCOL, IDENTITY, CAPACITY, VERSION, SWARM;

    /**
     * The kind of a failure, from its type or the kind it carries, looking through its causes. Messages are never
     * parsed, except a remote peer's error, which arrives only as text. Anything else is a network failure.
     */
    static GossipFailure of(Exception failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException) { return TIMEOUT; }
            if (cause instanceof CharacterCodingException) { return PROTOCOL; }
            if (cause instanceof GossipExchangeException exchange) { return exchange.kind(); }
            if (cause instanceof MembershipException member) { return member.kind(); }
            if (cause instanceof CommandExecutionException remote) {
                return ofRemote(remote.reply().error().message());
            }
        }
        return NETWORK;
    }

    /** A peer reports an error only as text: its reason, a colon and the message, as GossipCommands writes them. */
    private static GossipFailure ofRemote(String message) {
        if (message.contains("CAPACITY:")) { return CAPACITY; }
        if (message.contains("CONFLICT:")) { return IDENTITY; }
        if (message.contains("gossipVersion")) { return VERSION; }
        if (message.contains("swarmId")) { return SWARM; }
        return PROTOCOL;
    }
}
