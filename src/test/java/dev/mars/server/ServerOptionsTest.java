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
