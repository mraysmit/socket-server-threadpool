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
package dev.mars.client;

import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * Network faults injected into the requests servers make of one another, for testing how a swarm behaves on a bad
 * network: a request can be delayed, or lost, so that its sender hears nothing and times out, as when a partition or a
 * dropped packet swallows it. A {@link KeepAliveClient} that knows which server it sends for asks the installed policy
 * before every request; nothing is installed in a real deployment, and then nothing happens.
 *
 * <p>The policy is one per JVM, because it is meant for a test harness that runs a whole swarm in one process and
 * decides, for every pair of servers, what the network between them does.
 */
public final class NetworkFaults {
    /** What happens to one request: sent after {@code delayMillis}, or never arriving if {@code lost}. */
    public record Fault(long delayMillis, boolean lost) {
        public static final Fault NONE = new Fault(0, false);

        public Fault {
            if (delayMillis < 0) { throw new IllegalArgumentException("Delay must not be negative"); }
        }
    }

    /** Decides the fault for a request that the server {@code source} sends to {@code destination}. */
    @FunctionalInterface
    public interface Policy {
        Fault fault(String source, InetSocketAddress destination);
    }

    private static final Policy NONE = (source, destination) -> Fault.NONE;
    private static volatile Policy policy = NONE;

    private NetworkFaults() { }

    public static void install(Policy installed) { policy = Objects.requireNonNull(installed); }

    /** A network without faults again. */
    public static void clear() { policy = NONE; }

    static Fault fault(String source, InetSocketAddress destination) {
        var current = policy;
        if (current == NONE) { return Fault.NONE; }
        var fault = current.fault(source, destination);
        return fault == null ? Fault.NONE : fault;
    }
}
