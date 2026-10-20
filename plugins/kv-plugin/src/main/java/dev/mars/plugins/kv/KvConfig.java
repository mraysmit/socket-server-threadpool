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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;

/**
 * The key-value plugin's settings: the properties file bundled with the plugin, overridden by an optional file, which can
 * in turn be overridden for one server with {@code server.<serverId>.} entries.
 */
record KvConfig(int maxKeyLength, int maxValueBytes, int maxRecords, boolean replicate) {
    private static final String DEFAULTS = "/kv-defaults.properties";
    private static final String REPLICATION = "replication";
    private static final String MAX_KEY_LENGTH = "namespace.kv.max.key.length";
    private static final String MAX_VALUE_BYTES = "namespace.kv.max.value.bytes";
    private static final String MAX_RECORDS = "namespace.kv.max.records";
    private static final Set<String> KNOWN = Set.of(REPLICATION, MAX_KEY_LENGTH, MAX_VALUE_BYTES, MAX_RECORDS);

    static KvConfig defaults() {
        try { return load(null, ""); }
        catch (IOException e) { throw new UncheckedIOException("Cannot read " + DEFAULTS, e); }
    }

    /** The settings for one server. A null file means the bundled defaults alone. */
    static KvConfig load(Path file, String serverId) throws IOException {
        var settings = new Properties();
        try (InputStream in = KvConfig.class.getResourceAsStream(DEFAULTS)) { settings.load(in); }
        if (file != null) {
            var overrides = new Properties();
            try (var in = Files.newInputStream(file)) { overrides.load(in); }
            for (var name : overrides.stringPropertyNames()) { requireKnown(name); }
            String own = "server." + serverId + ".";
            for (var name : overrides.stringPropertyNames()) {
                if (!name.startsWith("server.")) { settings.setProperty(name, overrides.getProperty(name)); }
            }
            for (var name : overrides.stringPropertyNames()) {
                if (name.startsWith(own)) { settings.setProperty(name.substring(own.length()), overrides.getProperty(name)); }
            }
        }
        return new KvConfig(positive(settings, MAX_KEY_LENGTH), positive(settings, MAX_VALUE_BYTES),
                positive(settings, MAX_RECORDS), onOff(settings, REPLICATION));
    }

    /** A setting must be a known one, or a known one for a particular server, so a misspelling is caught and not ignored. */
    private static void requireKnown(String name) {
        boolean known = KNOWN.contains(name)
                || (name.startsWith("server.") && KNOWN.stream().anyMatch(key -> name.endsWith("." + key)));
        if (!known) { throw new IllegalArgumentException("Unknown setting: " + name); }
    }

    private static int positive(Properties settings, String key) {
        String value = settings.getProperty(key);
        try {
            int number = Integer.parseInt(value.trim());
            if (number > 0) { return number; }
        } catch (NumberFormatException | NullPointerException notANumber) { /* reported below */ }
        throw new IllegalArgumentException("Setting " + key + " must be a positive whole number, not '" + value + "'");
    }

    private static boolean onOff(Properties settings, String key) {
        String value = settings.getProperty(key);
        if ("on".equals(value)) { return true; }
        if ("off".equals(value)) { return false; }
        throw new IllegalArgumentException("Setting " + key + " must be on or off, not '" + value + "'");
    }
}
