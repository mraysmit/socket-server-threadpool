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
