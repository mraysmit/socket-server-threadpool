package dev.mars.server;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ThreadPooledServerTest {
    @ParameterizedTest
    @ValueSource(strings = {"Server-001", "123", "eu.server_1", "11111111-1111-4111-8111-111111111111", "  Server-001  "})
    void usesTheProvidedServerId(String id) {
        var server = new ThreadPooledServer(0, id);
        try {
            assertEquals(id.strip(), server.getServerId());
        } finally {
            server.requestExecutor.close();
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    void generatesDistinctUuidDefaultsForMissingOrBlankIds(String id) {
        var first = new ThreadPooledServer(0, id);
        var second = new ThreadPooledServer(0, id);
        var omitted = new ThreadPooledServer(0);
        try {
            assertDoesNotThrow(() -> UUID.fromString(first.getServerId()));
            assertDoesNotThrow(() -> UUID.fromString(second.getServerId()));
            assertDoesNotThrow(() -> UUID.fromString(omitted.getServerId()));
            assertNotEquals(first.getServerId(), second.getServerId());
            assertNotEquals(first.getServerId(), omitted.getServerId());
        } finally {
            first.requestExecutor.close();
            second.requestExecutor.close();
            omitted.requestExecutor.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"server A", "server\nA", "server\tA", "server|A", "server/A"})
    void rejectsIdsThatCannotBeUsedInTheProtocol(String id) {
        assertThrows(IllegalArgumentException.class, () -> new ThreadPooledServer(0, id));
    }
}
