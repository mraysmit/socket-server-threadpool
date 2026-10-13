package dev.mars.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ClientOptionsTest {
    @Test
    void defaultsToLocalhostAndPort9000() {
        var options = ClientOptions.parse("Hello", "server");
        assertEquals("localhost", options.host());
        assertEquals(9000, options.port());
        assertNull(options.expectedServerId());
        assertEquals("Hello server", options.message());
    }

    @Test
    void parsesTargetAndExpectedId() {
        var options = ClientOptions.parse("--host", "127.0.0.1", "--port", "9010",
                "--expected-server-id", "Server-002", "你好 server");
        assertEquals("127.0.0.1", options.host());
        assertEquals(9010, options.port());
        assertEquals("Server-002", options.expectedServerId());
        assertEquals("你好 server", options.message());
    }

    @Test
    void separatorAllowsMessageToStartWithOptionSyntax() {
        assertEquals("--port 123", ClientOptions.parse("--", "--port", "123").message());
    }

    @Test
    void blankExpectedIdDisablesServerIdentityCheck() {
        assertNull(ClientOptions.parse("--expected-server-id", " ", "Hello").expectedServerId());
    }

    @ParameterizedTest
    @MethodSource("invalidOptions")
    void rejectsInvalidClientOptions(String[] args) {
        assertThrows(IllegalArgumentException.class, () -> ClientOptions.parse(args));
    }

    static Stream<Arguments> invalidOptions() {
        return Stream.of(
                new String[]{}, new String[]{" "}, new String[]{"hello\nserver"},
                new String[]{"--host"}, new String[]{"--port"}, new String[]{"--expected-server-id"},
                new String[]{"--host", " ", "Hello"},
                new String[]{"--port", "0", "Hello"}, new String[]{"--port", "-1", "Hello"},
                new String[]{"--port", "65536", "Hello"}, new String[]{"--port", "NaN", "Hello"},
                new String[]{"--port", "", "Hello"},
                new String[]{"--port", "9010", "--port", "9011", "Hello"},
                new String[]{"--host", "a", "--host", "b", "Hello"},
                new String[]{"--expected-server-id", "A", "--expected-server-id", "B", "Hello"},
                new String[]{"--expected-server-id", "bad id", "Hello"},
                new String[]{"--host", "--port", "9010", "Hello"},
                new String[]{"--unknown", "Hello"}
        ).map(args -> Arguments.of((Object) args));
    }
}
