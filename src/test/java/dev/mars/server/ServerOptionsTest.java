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
package dev.mars.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ServerOptionsTest {
    @Test
    void omittedOptionsUseDefaultPortsAndGeneratedId() {
        var options = ServerOptions.parse();
        assertEquals(9000, options.port());
        assertEquals(9001, options.adminPort());
        assertTrue(options.pluginDirectories().isEmpty());
        assertDoesNotThrow(() -> UUID.fromString(options.serverId()));
    }

    @ParameterizedTest
    @MethodSource("validOptions")
    void parsesIndependentDefaultsAndExplicitPorts(String[] args, int port, int adminPort) {
        var options = ServerOptions.parse(args);
        assertEquals("Server-001", options.serverId());
        assertEquals(port, options.port());
        assertEquals(adminPort, options.adminPort());
    }

    static Stream<Arguments> validOptions() {
        return Stream.of(
                Arguments.of(new String[]{"Server-001"}, 9000, 9001),
                Arguments.of(new String[]{"Server-001", "--port", "9010"}, 9010, 9001),
                Arguments.of(new String[]{"--admin-port", "9011", "Server-001"}, 9000, 9011),
                Arguments.of(new String[]{"--server-id", "Server-001", "--port", "9010", "--admin-port", "9011"}, 9010, 9011),
                Arguments.of(new String[]{"Server-001", "--port", "0", "--admin-port", "0"}, 0, 0),
                Arguments.of(new String[]{"Server-001", "--port", "0"}, 0, 9001),
                Arguments.of(new String[]{"Server-001", "--admin-port", "0"}, 9000, 0),
                Arguments.of(new String[]{"Server-001", "--port", "1", "--admin-port", "65535"}, 1, 65535));
    }

    @Test
    void parsesPluginDirectoryIncludingSpaces() {
        assertEquals(java.util.List.of(java.nio.file.Path.of("custom plugins")),
                ServerOptions.parse("Server-001", "--plugins-dir", "custom plugins").pluginDirectories());
    }

    @Test
    void acceptsRepeatedPluginDirectoryOptionsInOrderAndKeepsAnImmutableSnapshot() {
        var options = ServerOptions.parse("--plugins-dir", "plugins/math", "Server-001",
                "--plugins-dir", "custom plugins", "--port", "0", "--admin-port", "0");
        assertEquals(java.util.List.of(java.nio.file.Path.of("plugins/math"), java.nio.file.Path.of("custom plugins")),
                options.pluginDirectories());
        assertThrows(UnsupportedOperationException.class, () -> options.pluginDirectories().clear());
        var supplied = new java.util.ArrayList<>(options.pluginDirectories());
        var copied = new ServerOptions("Server-001", 0, 0, supplied);
        supplied.clear();
        assertEquals(2, copied.pluginDirectories().size());
    }

    @Test
    void blankServerIdUsesDefaultWithConfiguredPorts() {
        var options = ServerOptions.parse(" ", "--port", "0", "--admin-port", "0");
        assertDoesNotThrow(() -> UUID.fromString(options.serverId()));
    }

    @ParameterizedTest
    @MethodSource("invalidOptions")
    void rejectsInvalidOptionsBeforeStartingListeners(String[] args) {
        assertThrows(IllegalArgumentException.class, () -> ServerOptions.parse(args));
    }

    static Stream<Arguments> invalidOptions() {
        return Stream.of(
                new String[]{"--plugins-dir"}, new String[]{"--plugins-dir", " "},
                new String[]{"--plugins-dir", "plugins", "--plugins-dir"},
                new String[]{"--port"}, new String[]{"--admin-port"}, new String[]{"--server-id"},
                new String[]{"--port", "--admin-port", "9011"},
                new String[]{"--port", "-1"}, new String[]{"--port", "65536"},
                new String[]{"--admin-port", "-1"}, new String[]{"--admin-port", "65536"},
                new String[]{"--port", "abc"}, new String[]{"--port", ""},
                new String[]{"--admin-port", "1.5"}, new String[]{"--port", "999999999999"},
                new String[]{"--port", "9010", "--port", "9012"},
                new String[]{"--admin-port", "9011", "--admin-port", "9013"},
                new String[]{"A", "B"}, new String[]{"A", "--server-id", "B"},
                new String[]{"--server-id", "A", "--server-id", "B"},
                new String[]{"--unknown", "value"}, new String[]{"bad id"},
                new String[]{"--port", "9001"}, new String[]{"--admin-port", "9000"},
                new String[]{"--port", "9010", "--admin-port", "9010"}
        ).map(args -> Arguments.of((Object) args));
    }
}
