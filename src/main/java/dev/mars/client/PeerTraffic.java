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

import dev.mars.protocol.Json;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * The requests servers make of one another, for a test harness that wants to watch them. A {@link KeepAliveClient} that
 * knows which server it sends for tells the installed observer when each request starts and how it ended; nothing is
 * installed in a real deployment, and then a request is not even read.
 *
 * <p>The observer is one per JVM, like {@link NetworkFaults}, because it is meant for a harness that runs a whole swarm in
 * one process.
 */
public final class PeerTraffic {
    /** How one request ended: answered, answered with an error, or not answered, and why. */
    public enum Outcome { OK, ERROR_REPLY, TIMEOUT, LOST, REFUSED, FAILED }

    /** One request to one address. {@code instruction}, {@code operation} and {@code messageId} are null if not in the request. */
    public record Call(String source, InetSocketAddress destination, String instruction, String operation, String messageId,
                       int requestBytes, long startedAtMillis, long startedNanos) { }

    /**
     * Told of each request, on the thread making it, so it must not block. {@code detail} is the error code of an error
     * reply or the message of a failure, and null for a request answered successfully.
     */
    public interface Observer {
        void started(Call call);
        void finished(Call call, Outcome outcome, String detail, long elapsedNanos);
    }

    private static volatile Observer observer;

    private PeerTraffic() { }

    public static void install(Observer installed) { observer = Objects.requireNonNull(installed); }

    /** Nothing is told of requests any more. */
    public static void clear() { observer = null; }

    /** Tells the observer a request is starting and returns it for {@link #end}; null when nothing is watching. */
    static Call begin(String source, InetSocketAddress destination, byte[] request) {
        var current = observer;
        if (current == null) { return null; }
        String instruction = null, operation = null, messageId = null;
        try {
            var envelope = Json.read(new String(request, StandardCharsets.UTF_8));
            instruction = envelope.path("instruction").asText(null);
            operation = envelope.path("parameters").path("operation").asText(null);
            messageId = envelope.path("messageId").asText(null);
        } catch (IOException | RuntimeException unreadable) { /* Still a request; it goes unnamed. */ }
        var call = new Call(source, destination, instruction, operation, messageId, request.length,
                System.currentTimeMillis(), System.nanoTime());
        try { current.started(call); }
        catch (RuntimeException ignored) { /* Watching must never disturb the request. */ }
        return call;
    }

    static void end(Call call, Outcome outcome, String detail) {
        var current = observer;
        if (current == null || call == null) { return; }
        try { current.finished(call, outcome, detail, System.nanoTime() - call.startedNanos()); }
        catch (RuntimeException ignored) { /* Watching must never disturb the request. */ }
    }
}
