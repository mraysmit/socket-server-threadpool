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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class GossipConfigTest {
    @TempDir Path temporary;

    static Properties minimal() {
        var properties = new Properties();
        properties.setProperty("swarm.id", "demo-swarm");
        properties.setProperty("advertise.host", "127.0.0.1");
        return properties;
    }

    @Test void defaultsMatchProposalAndInputMutationCannotChangeResolvedConfig() {
        var properties = minimal();
        var config = GossipConfig.fromProperties(properties, "Server-001");
        properties.setProperty("swarm.id", "changed");
        assertEquals("demo-swarm", config.swarmId());
        assertEquals(1000, config.roundMillis());
        assertEquals(5000, config.heartbeatMillis());
        assertEquals(2, config.fanout());
        assertEquals(2, config.maxInflight());
        assertEquals(128, config.maxMembers());
        assertEquals(512, config.maxRecords());
        assertEquals(32, config.maxBatchMembers());
        assertEquals(32768, config.maxExchangeBytes());
        assertEquals(60000, config.suspectAfterMillis());
        assertEquals(180000, config.deadAfterMillis());
        assertEquals(1800000, config.terminalRetentionMillis());
        assertFalse(config.discoveryEnabled());
        assertTrue(config.seeds().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> config.seeds().add(new GossipConfig.Seed("other", "localhost", 9000)));
    }

    /** Every key has a value no other key has, so two settings passed in each other's place cannot go unnoticed. */
    @Test void everyKeyReachesItsOwnSetting() {
        var properties = minimal();
        properties.setProperty("advertise.host", "gossip.example");
        properties.setProperty("seeds", "Seed@seed.example:9001");
        properties.setProperty("round.millis", "1001");
        properties.setProperty("heartbeat.millis", "1002");
        properties.setProperty("suspect.after.millis", "1003");
        properties.setProperty("dead.after.millis", "1004");
        properties.setProperty("terminal.retention.millis", "1005");
        properties.setProperty("fanout", "3");
        properties.setProperty("max.inflight", "4");
        properties.setProperty("max.members", "5");
        properties.setProperty("max.batch.members", "6");
        properties.setProperty("max.records", "7");
        properties.setProperty("max.exchange.bytes", "2048");
        var config = GossipConfig.fromProperties(properties, "Server-001");
        assertEquals("demo-swarm", config.swarmId());
        assertEquals("gossip.example", config.advertiseHost());
        assertEquals(java.util.List.of(new GossipConfig.Seed("Seed", "seed.example", 9001)), config.seeds());
        assertEquals(1001, config.roundMillis());
        assertEquals(1002, config.heartbeatMillis());
        assertEquals(1003, config.suspectAfterMillis());
        assertEquals(1004, config.deadAfterMillis());
        assertEquals(1005, config.terminalRetentionMillis());
        assertEquals(3, config.fanout());
        assertEquals(4, config.maxInflight());
        assertEquals(5, config.maxMembers());
        assertEquals(6, config.maxBatchMembers());
        assertEquals(7, config.maxRecords());
        assertEquals(2048, config.maxExchangeBytes());
        assertFalse(config.discoveryEnabled());
    }

    @Test void resolvesPerServerOverridesWithoutCachingOrMutatingDefaults() throws Exception {
        Path file = temporary.resolve("shared config.properties");
        Files.writeString(file, """
                # UTF-8 comment: 节点
                swarm.id=shared
                advertise.host=localhost
                seeds=Seed@localhost:9000
                server.Server.001.swarm.id=first
                server.Server.001.advertise.host=::1
                server.Server-002.swarm.id=second
                server.Server-002.seeds=
                """);
        var first = GossipConfig.load(file, "Server.001");
        var second = GossipConfig.load(file, "Server-002");
        var other = GossipConfig.load(file, "Other");
        assertEquals("first", first.swarmId());
        assertEquals("::1", first.advertiseHost());
        assertEquals("second", second.swarmId());
        assertTrue(second.seeds().isEmpty());
        assertEquals("shared", other.swarmId());
        assertEquals("localhost", other.advertiseHost());
        Files.writeString(file, "swarm.id=updated\nadvertise.host=localhost\n");
        assertEquals("updated", GossipConfig.load(file, "Server.001").swarmId());
        assertEquals("first", first.swarmId());
    }

    @Test void parsesSeedIdentitiesHostnamesIpv4AndBracketedIpv6() {
        var properties = minimal();
        properties.setProperty("seeds", "Node-1@127.0.0.1:9000, Node-2@swarm.example:9010, Node-3@[::1]:9020");
        var seeds = GossipConfig.fromProperties(properties, "Self").seeds();
        assertEquals(3, seeds.size());
        assertEquals(new GossipConfig.Seed("Node-1", "127.0.0.1", 9000), seeds.get(0));
        assertEquals(new GossipConfig.Seed("Node-2", "swarm.example", 9010), seeds.get(1));
        assertEquals(new GossipConfig.Seed("Node-3", "::1", 9020), seeds.get(2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"swarm.id", "advertise.host"})
    void requiresIdentityAndAdvertisedHost(String key) {
        var properties = minimal();
        properties.remove(key);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> GossipConfig.fromProperties(properties, "Self")).getMessage().contains(key));
    }

    @Test void millisecondSettingsGoUpToTheLargestThatStillConvertsToNanoseconds() {
        long largest = 9_223_372_036_854L; // Long.MAX_VALUE nanoseconds, in whole milliseconds.
        var properties = minimal();
        properties.setProperty("terminal.retention.millis", Long.toString(largest));
        assertEquals(largest, GossipConfig.fromProperties(properties, "Self").terminalRetentionMillis());
        properties.setProperty("terminal.retention.millis", Long.toString(largest + 1));
        assertThrows(IllegalArgumentException.class, () -> GossipConfig.fromProperties(properties, "Self"));
    }

    @ParameterizedTest
    @CsvSource({"round.millis,0", "heartbeat.millis,-1", "round.millis,1.5", "round.millis,+1",
            "round.millis,9223372036854775807", "round.millis,9223372036854775808",
            "max.members,0", "max.members,2147483648", "max.records,127", "max.batch.members,0",
            "max.batch.members,513", "fanout,0", "fanout,129", "max.inflight,0", "max.inflight,129",
            "max.exchange.bytes,1023", "max.exchange.bytes,65537", "suspect.after.millis,5000",
            "dead.after.millis,60000", "terminal.retention.millis,179999", "discovery.enabled,yes",
            "discovery.enabled,true", "swarm.id,bad id", "advertise.host,http://localhost",
            "advertise.host,localhost:9000", "advertise.host,[::1]", "advertise.host,localhost/path",
            "advertise.host,localhost?query", "advertise.host,user@localhost", "round.milis,1000",
            "server.Self.round.milis,1000", "server.swarm.id,invalid"})
    void rejectsInvalidValuesAndUnknownKeys(String key, String value) {
        var properties = minimal();
        properties.setProperty(key, value);
        assertThrows(IllegalArgumentException.class, () -> GossipConfig.fromProperties(properties, "Self"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost:9000", "Node@localhost", "Node@localhost:", "Node@localhost:0",
            "Node@localhost:65536", "Node@localhost:abc", "Node@localhost:9000/path", "Node@localhost:9000?x",
            "Node@localhost:9000#x", "Node@user@localhost:9000", "Node@::1:9000", "Node@[bad]:9000",
            "Node@localhost:9000,", "Node@localhost:9000,Node@localhost:9000", "@localhost:9000"})
    void rejectsMalformedOrDuplicateSeeds(String seeds) {
        var properties = minimal();
        properties.setProperty("seeds", seeds);
        assertThrows(IllegalArgumentException.class, () -> GossipConfig.fromProperties(properties, "Self"));
    }

    @Test void rejectsMissingFile() {
        assertThrows(java.io.IOException.class, () -> GossipConfig.load(temporary.resolve("missing"), "Self"));
    }

    @Test void seedConfigurationCannotExceedTheCandidateCapacity() {
        var properties = minimal();
        properties.setProperty("max.members", "2");
        properties.setProperty("seeds", "A@localhost:9000,B@localhost:9010,C@localhost:9020");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> GossipConfig.fromProperties(properties, "Self")).getMessage().contains("seeds"));
    }
}
