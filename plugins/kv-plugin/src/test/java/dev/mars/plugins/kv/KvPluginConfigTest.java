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
import java.util.Map;

import static dev.mars.plugins.kv.KvPluginTest.put;
import static org.junit.jupiter.api.Assertions.*;

/** The plugin applies the limits it is configured with, not fixed ones. */
class KvPluginConfigTest {
    @TempDir Path folder;

    private Path file(String... lines) throws IOException {
        return Files.writeString(folder.resolve("kv.properties"), String.join(System.lineSeparator(), lines) + System.lineSeparator());
    }

    private static KvPlugin started(KvPlugin plugin, String serverId) throws Exception {
        plugin.start(new PluginContext(serverId, 9000, 9001));
        return plugin;
    }

    @Test void aConfiguredKeyLengthIsEnforced() throws Exception {
        var kv = started(new KvPlugin(file("namespace.kv.max.key.length=10")), "Server-001");
        kv.execute(put("k".repeat(10), "ok"));
        var refused = assertThrows(InvalidParametersException.class, () -> kv.execute(put("k".repeat(11), "too long")));
        assertTrue(refused.getMessage().contains("10"), refused.getMessage());
    }

    @Test void aConfiguredValueSizeIsEnforced() throws Exception {
        var kv = started(new KvPlugin(file("namespace.kv.max.value.bytes=20")), "Server-001");
        kv.execute(put("a", "small"));
        var refused = assertThrows(InvalidParametersException.class, () -> kv.execute(put("b", "x".repeat(30))));
        assertTrue(refused.getMessage().contains("20"), refused.getMessage());
    }

    @Test void aConfiguredPairCountIsEnforced() throws Exception {
        var kv = started(new KvPlugin(file("namespace.kv.max.records=2")), "Server-001");
        kv.execute(put("a", "1"));
        kv.execute(put("b", "2"));
        var refused = assertThrows(InvalidParametersException.class, () -> kv.execute(put("c", "3")));
        assertTrue(refused.getMessage().contains("2"), refused.getMessage());
        assertEquals(Map.of("key", "a", "created", false), kv.execute(put("a", "replaced")));
    }

    @Test void aServerEntryAppliesToThatServerOnly() throws Exception {
        var path = file("server.Server-002.namespace.kv.max.records=1");
        var other = started(new KvPlugin(path), "Server-001");
        other.execute(put("a", "1"));
        other.execute(put("b", "2"));
        var limited = started(new KvPlugin(path), "Server-002");
        limited.execute(put("a", "1"));
        assertThrows(InvalidParametersException.class, () -> limited.execute(put("b", "2")));
    }

    @Test void theFileNamedByTheKvConfigPropertyIsUsed() throws Exception {
        var path = file("namespace.kv.max.records=1");
        System.setProperty("kv.config", path.toString());
        try {
            var kv = started(new KvPlugin(), "Server-001");
            kv.execute(put("a", "1"));
            assertThrows(InvalidParametersException.class, () -> kv.execute(put("b", "2")));
        } finally {
            System.clearProperty("kv.config");
        }
    }

    @Test void aBadConfigurationFileStopsThePluginStartingAndNamesTheSetting() throws Exception {
        var plugin = new KvPlugin(file("namespace.kv.max.records=none"));
        var failure = assertThrows(IllegalArgumentException.class, () -> plugin.start(new PluginContext("Server-001", 9000, 9001)));
        assertTrue(failure.getMessage().contains("namespace.kv.max.records"), failure.getMessage());
    }
}
