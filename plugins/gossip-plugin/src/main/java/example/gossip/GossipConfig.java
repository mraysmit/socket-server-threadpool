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
package example.gossip;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** Immutable per-instance configuration. Endpoint parsing performs no DNS or network I/O. */
public record GossipConfig(String swarmId, String advertiseHost, List<Seed> seeds,
                           long roundMillis, long heartbeatMillis, int fanout, int maxInflight,
                           int maxMembers, int maxRecords, int maxBatchMembers, int maxExchangeBytes,
                           long suspectAfterMillis, long deadAfterMillis, long terminalRetentionMillis,
                           boolean discoveryEnabled) {
    public static final String CONFIG_PROPERTY = "gossip.config";
    private static final long MAX_MILLIS = Long.MAX_VALUE / 1_000_000;
    private static final Set<String> KEYS = Set.of("swarm.id", "advertise.host", "seeds", "round.millis",
            "heartbeat.millis", "fanout", "max.inflight", "max.members", "max.records", "max.batch.members",
            "max.exchange.bytes", "suspect.after.millis", "dead.after.millis", "terminal.retention.millis",
            "discovery.enabled");

    public GossipConfig {
        requireId(swarmId, "swarm.id");
        requireHost(advertiseHost, "advertise.host");
        seeds = List.copyOf(seeds);
        if (new HashSet<>(seeds).size() != seeds.size()) {
            throw invalid("seeds", "must not contain duplicate endpoints with the same server ID");
        }
        requireMillis(roundMillis, "round.millis");
        requireMillis(heartbeatMillis, "heartbeat.millis");
        requireMillis(suspectAfterMillis, "suspect.after.millis");
        requireMillis(deadAfterMillis, "dead.after.millis");
        requireMillis(terminalRetentionMillis, "terminal.retention.millis");
        if (suspectAfterMillis <= heartbeatMillis || deadAfterMillis <= suspectAfterMillis
                || terminalRetentionMillis < deadAfterMillis) {
            throw invalid("timing", "requires heartbeat < suspect < dead <= terminal retention");
        }
        if (maxMembers < 1 || maxRecords < maxMembers) {
            throw invalid("capacity", "requires 1 <= max.members <= max.records");
        }
        if (maxBatchMembers < 1 || maxBatchMembers > maxRecords) {
            throw invalid("max.batch.members", "must be between 1 and max.records (including sender)");
        }
        if (fanout < 1 || fanout > maxMembers || maxInflight < 1 || maxInflight > maxMembers) {
            throw invalid("fanout/max.inflight", "must each be between 1 and max.members");
        }
        if (maxExchangeBytes < 1024 || maxExchangeBytes > 65_536) {
            throw invalid("max.exchange.bytes", "must be between 1024 and 65536");
        }
        if (discoveryEnabled) {
            throw invalid("discovery.enabled", "UDP discovery is not implemented; use false");
        }
    }

    public static GossipConfig fromSystemProperty(String serverId) throws IOException {
        String file = System.getProperty(CONFIG_PROPERTY);
        if (file == null || file.isBlank()) {
            throw invalid(CONFIG_PROPERTY, "must name a UTF-8 properties file (-Dgossip.config=...)");
        }
        return load(Path.of(file), serverId);
    }

    public static GossipConfig load(Path file, String serverId) throws IOException {
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return fromProperties(properties, serverId);
    }

    /** Global keys are defaults; server.<serverId>.<key> overrides are resolved on each load. */
    public static GossipConfig fromProperties(Properties source, String serverId) {
        requireId(serverId, "serverId");
        var resolved = new Properties();
        String prefix = "server." + serverId + ".";
        for (String name : source.stringPropertyNames()) {
            if (KEYS.contains(name)) {
                resolved.setProperty(name, source.getProperty(name).strip());
            } else if (!validOverride(name)) {
                throw invalid(name, "unknown configuration key");
            }
        }
        for (String key : KEYS) {
            String override = source.getProperty(prefix + key);
            if (override != null) { resolved.setProperty(key, override.strip()); }
        }
        String seedText = resolved.getProperty("seeds", "");
        List<Seed> seeds = seedText.isEmpty() ? List.of() : Arrays.stream(seedText.split(",", -1))
                .map(String::strip).map(Seed::parse).toList();
        String discovery = resolved.getProperty("discovery.enabled", "false");
        if (!discovery.equals("true") && !discovery.equals("false")) {
            throw invalid("discovery.enabled", "must be true or false");
        }
        return new GossipConfig(required(resolved, "swarm.id"), required(resolved, "advertise.host"), seeds,
                number(resolved, "round.millis", 1000), number(resolved, "heartbeat.millis", 5000),
                integer(resolved, "fanout", 2), integer(resolved, "max.inflight", 2),
                integer(resolved, "max.members", 128), integer(resolved, "max.records", 512),
                integer(resolved, "max.batch.members", 32), integer(resolved, "max.exchange.bytes", 32768),
                number(resolved, "suspect.after.millis", 60000), number(resolved, "dead.after.millis", 180000),
                number(resolved, "terminal.retention.millis", 1800000), Boolean.parseBoolean(discovery));
    }

    private static boolean validOverride(String name) {
        if (!name.startsWith("server.")) { return false; }
        for (String key : KEYS) {
            String suffix = "." + key;
            if (name.endsWith(suffix) && name.length() > "server.".length() + suffix.length()) {
                String id = name.substring("server.".length(), name.length() - suffix.length());
                if (id.matches("[A-Za-z0-9._-]{1,128}")) { return true; }
            }
        }
        return false;
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) { throw invalid(key, "is required"); }
        return value;
    }

    private static long number(Properties properties, String key, long fallback) {
        String value = properties.getProperty(key, Long.toString(fallback));
        if (!value.matches("[0-9]+")) { throw invalid(key, "must be a nonnegative decimal integer"); }
        try { return Long.parseLong(value); }
        catch (NumberFormatException e) { throw invalid(key, "is outside the supported integer range"); }
    }

    private static int integer(Properties properties, String key, int fallback) {
        long value = number(properties, key, fallback);
        if (value > Integer.MAX_VALUE) { throw invalid(key, "is outside the supported integer range"); }
        return (int) value;
    }

    private static void requireMillis(long value, String key) {
        if (value < 1 || value > MAX_MILLIS) {
            throw invalid(key, "must be positive and convertible to nanoseconds without overflow");
        }
    }

    private static void requireId(String value, String key) {
        if (!GossipValidation.validId(value)) {
            throw invalid(key, "must contain 1-128 letters, digits, dots, underscores, or hyphens");
        }
    }

    private static void requireHost(String host, String key) {
        if (!GossipValidation.validHost(host)) {
            throw invalid(key, "must be a hostname, IPv4 address, or unbracketed IPv6 address");
        }
    }

    private static IllegalArgumentException invalid(String key, String message) {
        return new IllegalArgumentException("Gossip configuration '" + key + "' " + message);
    }

    public record Seed(String serverId, String host, int port) {
        public Seed {
            requireId(serverId, "seed serverId");
            requireHost(host, "seed host");
            if (port < 1 || port > 65535) { throw invalid("seed port", "must be between 1 and 65535"); }
        }

        public static Seed parse(String text) {
            int separator = text.indexOf('@');
            if (separator < 1 || separator != text.lastIndexOf('@')) {
                throw invalid("seeds", "expects serverId@host:port (bracket IPv6 hosts)");
            }
            String address = text.substring(separator + 1);
            try {
                URI endpoint = new URI("tcp://" + address);
                String host = endpoint.getHost();
                if (host == null || endpoint.getRawUserInfo() != null || !endpoint.getRawPath().isEmpty()
                        || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null) {
                    throw invalid("seeds", "has an invalid endpoint");
                }
                if (host.startsWith("[")) { host = host.substring(1, host.length() - 1); }
                // URI accepts an empty trailing port; require an explicit decimal port instead.
                int colon = address.lastIndexOf(':');
                if (colon < 0 || !address.substring(colon + 1).matches("[0-9]+")) {
                    throw invalid("seeds", "requires an explicit decimal port");
                }
                return new Seed(text.substring(0, separator), host, endpoint.getPort());
            } catch (URISyntaxException e) {
                throw invalid("seeds", "has an invalid endpoint");
            }
        }
    }
}
