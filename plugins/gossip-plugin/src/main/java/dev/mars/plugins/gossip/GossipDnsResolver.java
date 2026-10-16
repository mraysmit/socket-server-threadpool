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

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Scheduled hostname lookups are cached and isolated so stop can kill a blocked platform resolver. */
final class GossipDnsResolver implements AutoCloseable {
    @FunctionalInterface interface Launcher { Process start(String host) throws IOException; }
    private static final long CACHE_NANOS = Duration.ofSeconds(60).toNanos();
    private final int capacity;
    private final int maxHelpers;
    private final long timeoutMillis;
    private final LongSupplier clock;
    private final Launcher launcher;
    private final Set<Process> active = new HashSet<>();
    private final LinkedHashMap<String, Cached> cache = new LinkedHashMap<>(16, 0.75f, true);
    private boolean closed;

    GossipDnsResolver(int capacity, int maxHelpers) {
        this(capacity, maxHelpers, Duration.ofSeconds(2), System::nanoTime, GossipDnsResolver::launch);
    }

    GossipDnsResolver(int capacity, int maxHelpers, Duration timeout, LongSupplier clock, Launcher launcher) {
        if (capacity < 1 || maxHelpers < 1 || timeout.toMillis() < 1) { throw new IllegalArgumentException("Positive resolver limits required"); }
        this.capacity = capacity;
        this.maxHelpers = maxHelpers;
        timeoutMillis = timeout.toMillis();
        this.clock = clock;
        this.launcher = launcher;
    }

    GossipConfig.Seed resolve(GossipConfig.Seed peer) throws IOException { return resolveAll(peer).getFirst(); }

    /**
     * Every address of the peer's host. The first address rotates between calls to spread load; the rest are
     * connection fallbacks, so one unreachable address (for example an unrouted IPv6 record) cannot fail the peer.
     */
    List<GossipConfig.Seed> resolveAll(GossipConfig.Seed peer) throws IOException {
        String host = peer.host().toLowerCase(Locale.ROOT);
        Process process;
        synchronized (this) {
            if (closed) { throw new IOException("Gossip resolver is closed"); }
            try { return List.of(numeric(peer, InetAddress.ofLiteral(host))); }
            catch (IllegalArgumentException notLiteral) { /* Resolve names in an owned helper. */ }
            Cached previous = cache.get(host);
            if (previous != null && clock.getAsLong() - previous.created < CACHE_NANOS) { return numeric(peer, previous.rotation()); }
            if (active.size() >= maxHelpers) { throw new IOException("Gossip resolver capacity is full"); }
            process = launcher.start(host);
            active.add(process);
        }
        try {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) { throw new SocketTimeoutException("Gossip DNS lookup timed out"); }
            byte[] output = process.getInputStream().readNBytes(4097);
            if (process.exitValue() != 0 || output.length > 4096) { throw new IOException("Gossip DNS lookup failed"); }
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(output)).toString();
            var addresses = new ArrayList<InetAddress>();
            boolean header = false;
            for (String line : text.lines().toList()) {
                if (!header) { header = line.equals("GOSSIP_DNS_V1"); continue; }
                if (addresses.size() == 16) { throw new IOException("Too many DNS addresses"); }
                try { addresses.add(InetAddress.ofLiteral(line)); }
                catch (IllegalArgumentException e) { throw new IOException("Invalid DNS helper reply", e); }
            }
            if (!header || addresses.isEmpty()) { throw new IOException("Empty DNS helper reply"); }
            synchronized (this) {
                if (closed) { throw new IOException("Gossip resolver is closed"); }
                var resolved = new Cached(List.copyOf(addresses), clock.getAsLong());
                cache.put(host, resolved);
                while (cache.size() > capacity) { cache.pollFirstEntry(); }
                return numeric(peer, resolved.rotation());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Gossip DNS lookup interrupted");
        } finally {
            terminate(process);
            try {
                process.getInputStream().close();
                process.getErrorStream().close();
                process.getOutputStream().close();
            } finally { synchronized (this) { active.remove(process); } }
        }
    }

    private static GossipConfig.Seed numeric(GossipConfig.Seed peer, InetAddress address) {
        return new GossipConfig.Seed(peer.serverId(), address.getHostAddress(), peer.port());
    }

    private static List<GossipConfig.Seed> numeric(GossipConfig.Seed peer, List<InetAddress> addresses) {
        return addresses.stream().map(address -> numeric(peer, address)).toList();
    }

    private static Process launch(String host) throws IOException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        if (!Files.exists(java)) { java = Path.of(System.getProperty("java.home"), "bin", "java"); }
        try {
            String classpath = Path.of(GossipDnsLookup.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
            return new ProcessBuilder(java.toString(), "-Ddev.mars.announce=false", "-cp", classpath, GossipDnsLookup.class.getName(), host)
                    .redirectErrorStream(true).start();
        } catch (java.net.URISyntaxException e) { throw new IOException("Cannot locate the DNS helper", e); }
    }

    private static void terminate(Process process) throws IOException {
        if (!process.isAlive()) { return; }
        process.destroyForcibly();
        boolean interrupted = Thread.interrupted();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (process.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) { throw new IOException("DNS helper did not terminate"); }
                try { process.waitFor(remaining, TimeUnit.NANOSECONDS); }
                catch (InterruptedException e) { interrupted = true; }
            }
        } finally { if (interrupted) { Thread.currentThread().interrupt(); } }
    }

    synchronized int activeHelpers() { return active.size(); }
    synchronized int cacheSize() { return cache.size(); }

    @Override public void close() throws IOException {
        List<Process> processes;
        synchronized (this) {
            closed = true;
            cache.clear();
            processes = List.copyOf(active);
        }
        IOException failure = null;
        for (Process process : processes) { process.destroyForcibly(); }
        for (Process process : processes) {
            try { terminate(process); }
            catch (IOException e) { if (failure == null) { failure = e; } else { failure.addSuppressed(e); } }
        }
        if (failure != null) { throw failure; }
    }

    private static final class Cached {
        final List<InetAddress> addresses;
        final long created;
        int cursor;
        Cached(List<InetAddress> addresses, long created) { this.addresses = addresses; this.created = created; }
        /** All addresses starting at the cursor, which then advances. Callers hold the resolver monitor. */
        List<InetAddress> rotation() {
            var result = new ArrayList<InetAddress>(addresses.size());
            for (int i = 0; i < addresses.size(); i++) { result.add(addresses.get((cursor + i) % addresses.size())); }
            cursor = (cursor + 1) % addresses.size();
            return result;
        }
    }
}
