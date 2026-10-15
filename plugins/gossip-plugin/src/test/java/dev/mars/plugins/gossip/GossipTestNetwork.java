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

import dev.mars.client.TextMessageClient;
import dev.mars.plugin.CommandPlugin;
import dev.mars.plugin.InvalidParametersException;
import dev.mars.plugin.PluginContext;
import dev.mars.protocol.CommandProtocol;
import dev.mars.protocol.CommandReply;
import dev.mars.protocol.CommandRequest;
import dev.mars.plugins.gossip.fixture.GossipServerFixture;
import dev.mars.server.ServerInstance;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real host backends behind controlled TCP relays; advertised endpoints always traverse the relays. */
final class GossipTestNetwork implements AutoCloseable {
    final List<Node> nodes = new ArrayList<>();
    final AtomicInteger active = new AtomicInteger();
    final AtomicInteger peak = new AtomicInteger();
    final AtomicInteger dropped = new AtomicInteger();
    volatile Set<String> partition;
    volatile boolean stalled;

    Node start(String id, GossipConfig config, boolean scheduled) throws Exception {
        var relay = new Relay(this, id);
        var plugin = new GossipPlugin(config, scheduled);
        var wrapper = new CommandPlugin() {
            @Override public String instruction() { return plugin.instruction(); }
            @Override public void start(PluginContext context) throws Exception {
                plugin.start(new PluginContext(context.serverId(), relay.port(), context.adminPort()));
            }
            @Override public Object execute(Map<String, Object> parameters) throws InvalidParametersException { return plugin.execute(parameters); }
            @Override public void stop() throws Exception { plugin.stop(); }
        };
        try {
            var backend = GossipServerFixture.start(id, wrapper);
            relay.backend = backend.getPort();
            var node = new Node(id, config, plugin, backend, relay);
            nodes.add(node);
            return node;
        } catch (Exception | Error failure) {
            try { relay.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    boolean blocked(String source, String target) {
        var side = partition;
        return side != null && side.contains(source) != side.contains(target);
    }

    static GossipConfig config(int cap, int records, int batch, int bytes, String host, String seeds) {
        var p = GossipConfigTest.minimal();
        p.setProperty("advertise.host", host);
        p.setProperty("seeds", seeds);
        p.setProperty("max.members", Integer.toString(cap));
        p.setProperty("max.records", Integer.toString(records));
        p.setProperty("max.batch.members", Integer.toString(batch));
        p.setProperty("max.exchange.bytes", Integer.toString(bytes));
        p.setProperty("round.millis", "40");
        p.setProperty("heartbeat.millis", "120");
        p.setProperty("suspect.after.millis", "800");
        p.setProperty("dead.after.millis", "1600");
        p.setProperty("terminal.retention.millis", "20000");
        return GossipConfig.fromProperties(p, "Self");
    }

    record Node(String id, GossipConfig config, GossipPlugin plugin, ServerInstance backend, Relay relay) implements AutoCloseable {
        TextMessageClient client() { return new TextMessageClient("127.0.0.1", relay.port(), id); }
        GossipConfig.Seed endpoint() { return new GossipConfig.Seed(id, "127.0.0.1", relay.port()); }
        Map<?, ?> status() throws Exception { return GossipSwarmTest.status(plugin); }
        List<Map<?, ?>> members() throws Exception { return GossipSwarmTest.members(plugin); }
        MemberRecord self() throws Exception { return MemberRecord.fromWire(status().get("self")); }
        @Override public void close() throws Exception { backend.close(); }
    }

    record Sample(CommandRequest request, CommandReply reply, int requestBytes, int replyBytes) { }
    static final class Delay {
        final CompletableFuture<CommandReply> captured = new CompletableFuture<>();
        final CountDownLatch release = new CountDownLatch(1);
    }

    static final class Relay implements AutoCloseable {
        private final GossipTestNetwork network;
        private final String id;
        private final ServerSocket listener;
        private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private final List<Thread> threads = new CopyOnWriteArrayList<>();
        private final List<Throwable> failures = new CopyOnWriteArrayList<>();
        private final List<Sample> samples = new ArrayList<>();
        final AtomicInteger dropRequests = new AtomicInteger();
        final AtomicInteger dropReplies = new AtomicInteger();
        final AtomicReference<Delay> delayNextReply = new AtomicReference<>();
        volatile int backend;
        private volatile boolean closed;

        Relay(GossipTestNetwork network, String id) throws IOException {
            this.network = network;
            this.id = id;
            listener = new ServerSocket(0, 64, InetAddress.ofLiteral("127.0.0.1"));
            executor.submit(() -> {
                threads.add(Thread.currentThread());
                try {
                    while (!closed) {
                        var socket = listener.accept();
                        sockets.add(socket);
                        try { executor.submit(() -> serve(socket)); }
                        catch (java.util.concurrent.RejectedExecutionException stopping) {
                            sockets.remove(socket);
                            socket.close();
                            if (!closed) { failures.add(stopping); }
                        }
                    }
                } catch (IOException e) { if (!closed) { failures.add(e); } }
            });
        }

        int port() { return listener.getLocalPort(); }
        synchronized List<Sample> samples() { return List.copyOf(samples); }
        synchronized void clearSamples() { samples.clear(); }
        private static boolean consume(AtomicInteger count) { return count.getAndUpdate(value -> Math.max(0, value - 1)) > 0; }

        private void serve(Socket frontend) {
            threads.add(Thread.currentThread());
            int concurrent = network.active.incrementAndGet();
            network.peak.accumulateAndGet(concurrent, Math::max);
            try (frontend) {
                frontend.setSoTimeout(8000);
                var input = new BufferedReader(new InputStreamReader(frontend.getInputStream(), StandardCharsets.UTF_8));
                String line = boundedLine(input);
                if (line == null) { return; }
                var request = CommandProtocol.parseRequest(line);
                String source = "inspection";
                if (request.parameters().get("sender") instanceof Map<?, ?> sender) { source = (String) sender.get("serverId"); }
                if (network.blocked(source, id) || consume(dropRequests)) { network.dropped.incrementAndGet(); return; }
                frontend.setSoTimeout(50);
                while (network.stalled && !closed) {
                    try { if (input.read() == -1) { return; } throw new IOException("Unexpected second request while stalled"); }
                    catch (SocketTimeoutException waiting) { /* Observe heal or client cancellation. */ }
                }
                if (closed || network.blocked(source, id)) { return; }
                frontend.setSoTimeout(8000);
                try (var upstream = new Socket()) {
                    sockets.add(upstream);
                    try {
                        upstream.connect(new java.net.InetSocketAddress("127.0.0.1", backend), 1000);
                        upstream.setSoTimeout(8000);
                        upstream.getOutputStream().write((line + "\n").getBytes(StandardCharsets.UTF_8));
                        upstream.getOutputStream().flush();
                        String response = boundedLine(new BufferedReader(new InputStreamReader(upstream.getInputStream(), StandardCharsets.UTF_8)));
                        if (response == null) { return; }
                        var reply = CommandProtocol.parseReply(response);
                        synchronized (this) {
                            if (samples.size() < 2048) { samples.add(new Sample(request, reply, line.getBytes(StandardCharsets.UTF_8).length, response.getBytes(StandardCharsets.UTF_8).length)); }
                        }
                        if (consume(dropReplies)) { network.dropped.incrementAndGet(); return; }
                        var delay = delayNextReply.getAndSet(null);
                        if (delay != null) {
                            delay.captured.complete(reply);
                            if (!delay.release.await(5, TimeUnit.SECONDS)) { throw new AssertionError("Delayed reply was not released"); }
                        }
                        // Recheck partitions after a delayed reply so a partition cannot be bypassed by an old connection.
                        if (network.blocked(source, id)) { network.dropped.incrementAndGet(); return; }
                        frontend.getOutputStream().write((response + "\n").getBytes(StandardCharsets.UTF_8));
                        frontend.getOutputStream().flush();
                    } finally { sockets.remove(upstream); }
                }
            } catch (IOException expectedDisconnect) {
                // Faults, client cancellation, and host shutdown deliberately disconnect complete exchanges.
            } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            catch (Throwable failure) { failures.add(failure); }
            finally { sockets.remove(frontend); network.active.decrementAndGet(); }
        }

        private static String boundedLine(BufferedReader input) throws IOException {
            var line = new StringBuilder();
            int value;
            while ((value = input.read()) != -1 && value != '\n') {
                if (line.length() >= 65536) { throw new IOException("Relay frame too large"); }
                if (value != '\r') { line.append((char) value); }
            }
            return value == -1 && line.isEmpty() ? null : line.toString();
        }

        int openSockets() { return sockets.size(); }
        @Override public void close() throws Exception {
            closed = true;
            IOException failure = null;
            try { listener.close(); } catch (IOException e) { failure = e; }
            for (var socket : sockets) {
                try { socket.close(); }
                catch (IOException e) { if (failure == null) { failure = e; } else { failure.addSuppressed(e); } }
            }
            executor.shutdownNow();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS), "Relay tasks did not terminate");
            // Executor termination can precede the virtual thread's final exit. Join the actual owned threads too.
            for (var thread : threads) {
                long remaining = deadline - System.nanoTime();
                if (thread.isAlive() && remaining > 0) { thread.join(Duration.ofNanos(remaining)); }
            }
            if (failure != null) { throw failure; }
            assertTrue(threads.stream().noneMatch(Thread::isAlive), "Relay thread leaked");
            assertEquals(0, sockets.size(), "Relay socket leaked");
            assertTrue(failures.isEmpty(), failures.toString());
        }
    }

    @FunctionalInterface interface Check { boolean test() throws Exception; }
    static long await(Duration timeout, Check condition) throws Exception {
        long began = System.nanoTime();
        long deadline = began + timeout.toNanos();
        while (!condition.test() && System.nanoTime() < deadline) { Thread.sleep(20); }
        assertTrue(condition.test(), "Condition did not converge within " + timeout);
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
    }

    @Override public void close() throws Exception {
        Throwable failure = null;
        for (var node : nodes) {
            try { node.close(); } catch (Throwable e) { if (failure == null) { failure = e; } else { failure.addSuppressed(e); } }
        }
        for (var node : nodes) {
            try { node.relay.close(); } catch (Throwable e) { if (failure == null) { failure = e; } else { failure.addSuppressed(e); } }
        }
        if (failure instanceof Error error) { throw error; }
        if (failure != null) { throw (Exception) failure; }
        assertEquals(0, active.get());
    }
}
