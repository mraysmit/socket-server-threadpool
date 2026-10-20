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

import dev.mars.plugin.InvalidParametersException;
import dev.mars.plugin.PluginContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static dev.mars.plugins.kv.KvPluginApplyTest.apply;
import static dev.mars.plugins.kv.KvPluginApplyTest.record;
import static dev.mars.plugins.kv.KvPluginTest.delete;
import static dev.mars.plugins.kv.KvPluginTest.put;
import static org.junit.jupiter.api.Assertions.*;

/** The plugin pushes its local writes to its peers, with fakes standing in for gossip and the network. */
class KvPluginReplicationTest {
    private static final Peer TWO = new Peer("Server-002", "127.0.0.1", 9100);
    private static final Peer THREE = new Peer("Server-003", "127.0.0.1", 9200);

    @TempDir Path folder;
    private final List<Map.Entry<Peer, List<KvRecord>>> sent = Collections.synchronizedList(new ArrayList<>());
    private final PeerSource peers = () -> List.of(TWO, THREE);
    private final PeerLink link = (peer, records) -> sent.add(Map.entry(peer, records));

    private KvPlugin started(String... settings) throws Exception {
        var file = Files.writeString(folder.resolve("kv.properties"), String.join(System.lineSeparator(), settings) + System.lineSeparator());
        var kv = new KvPlugin(file, peers, link, Runnable::run);
        kv.start(new PluginContext("Server-001", 9000, 9001));
        return kv;
    }

    @Test void aLocalPutIsPushedToEveryActivePeer() throws Exception {
        var kv = started("replication=on");
        kv.execute(put("colour", "blue"));
        assertEquals(List.of(TWO, THREE), sent.stream().map(Map.Entry::getKey).toList());
        var pushed = sent.getFirst().getValue();
        assertEquals(1, pushed.size());
        assertEquals("colour", pushed.getFirst().key());
        assertEquals("\"blue\"", pushed.getFirst().valueJson());
        assertEquals("Server-001", pushed.getFirst().version().n());
        assertFalse(pushed.getFirst().deleted());
    }

    @Test void aLocalDeleteIsPushedAsADeletionRecord() throws Exception {
        var kv = started("replication=on");
        kv.execute(put("a", "1"));
        sent.clear();
        kv.execute(delete("a"));
        assertEquals(2, sent.size());
        assertTrue(sent.getFirst().getValue().getFirst().deleted());
        assertEquals("a", sent.getFirst().getValue().getFirst().key());
    }

    @Test void aWriteThatIsRefusedPushesNothing() throws Exception {
        var kv = started("replication=on", "namespace.kv.max.key.length=3");
        assertThrows(InvalidParametersException.class, () -> kv.execute(put("toolong", "1")));
        assertEquals(0, sent.size());
    }

    @Test void withReplicationOffNothingIsPushed() throws Exception {
        var kv = started("replication=off");
        kv.execute(put("a", "1"));
        kv.execute(delete("a"));
        assertEquals(0, sent.size());
    }

    @Test void aRecordReceivedFromAPeerIsNotPushedOnYet() throws Exception {
        var kv = started("replication=on");
        kv.execute(apply(record("a", "1", 5_000, "Server-002")));
        assertEquals(0, sent.size(), "Forwarding is a later phase");
    }

    @Test void statusCountsThePushes() throws Exception {
        var kv = started("replication=on");
        kv.execute(put("a", "1"));
        var counters = (Map<?, ?>) kv.execute(Map.of("operation", "status")).get("counters");
        assertEquals(2L, counters.get("pushed"));
        assertEquals(0L, counters.get("pushFailures"));
    }

    @Test void aPeerThatFailsDoesNotMakeTheWriteFail() throws Exception {
        var file = Files.writeString(folder.resolve("down.properties"), "replication=on" + System.lineSeparator());
        PeerLink broken = (peer, records) -> { throw new IOException("connection refused"); };
        var kv = new KvPlugin(file, peers, broken, Runnable::run);
        kv.start(new PluginContext("Server-001", 9000, 9001));
        assertEquals(Map.of("key", "a", "created", true), kv.execute(put("a", "1")));
        var counters = (Map<?, ?>) kv.execute(Map.of("operation", "status")).get("counters");
        assertEquals(2L, counters.get("pushFailures"));
    }
}
