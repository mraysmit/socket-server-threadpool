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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class KvConfigTest {
    @Test void theBundledDefaultsGiveTheShippedLimitsAndReplicationOff() {
        var defaults = KvConfig.defaults();
        assertEquals(256, defaults.maxKeyLength());
        assertEquals(16384, defaults.maxValueBytes());
        assertEquals(1000, defaults.maxRecords());
        assertFalse(defaults.replicate());
    }

    @TempDir Path folder;

    private Path file(String... lines) throws IOException {
        return Files.writeString(folder.resolve("kv.properties"), String.join(System.lineSeparator(), lines) + System.lineSeparator());
    }

    @Test void aFileOverridesTheDefaultsAndLeavesTheRestAlone() throws Exception {
        var config = KvConfig.load(file("namespace.kv.max.records=5", "replication=on"), "Server-001");
        assertEquals(5, config.maxRecords());
        assertTrue(config.replicate());
        assertEquals(256, config.maxKeyLength());
        assertEquals(16384, config.maxValueBytes());
    }

    @Test void aServerEntryOverridesTheFileForThatServerOnly() throws Exception {
        var path = file("namespace.kv.max.records=5", "server.Server-002.namespace.kv.max.records=7",
                "server.Server-002.replication=on");
        assertEquals(5, KvConfig.load(path, "Server-001").maxRecords());
        assertFalse(KvConfig.load(path, "Server-001").replicate());
        assertEquals(7, KvConfig.load(path, "Server-002").maxRecords());
        assertTrue(KvConfig.load(path, "Server-002").replicate());
    }

    @Test void noFileMeansTheDefaults() throws Exception {
        assertEquals(KvConfig.defaults(), KvConfig.load(null, "Server-001"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "namespace.kv.max.records=lots           | namespace.kv.max.records",
            "namespace.kv.max.records=0              | namespace.kv.max.records",
            "namespace.kv.max.key.length=-1          | namespace.kv.max.key.length",
            "namespace.kv.max.value.bytes=            | namespace.kv.max.value.bytes",
            "namespace.kv.max.recordz=5              | namespace.kv.max.recordz",
            "bogus=1                                 | bogus",
            "replication=maybe                       | replication",
            "server.Server-001.namespace.kv.bogus=1  | namespace.kv.bogus"})
    void aBadSettingIsRejectedWithItsKeyNamed(String line, String key) throws Exception {
        var path = file(line.trim());
        var refused = assertThrows(IllegalArgumentException.class, () -> KvConfig.load(path, "Server-001"));
        assertTrue(refused.getMessage().contains(key), refused.getMessage());
    }
}
