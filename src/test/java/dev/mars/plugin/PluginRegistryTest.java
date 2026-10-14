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
package dev.mars.plugin;

import dev.mars.protocol.CommandError;
import dev.mars.protocol.CommandRequest;
import dev.mars.protocol.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PluginRegistryTest {
    @Test
    void discoversProductionAndTestServiceProviders() throws Exception {
        try (var registry = PluginRegistry.load(null)) {
            assertEquals(Set.of("math.add", "text.concat", "echo"), registry.instructions());
            assertThrows(UnsupportedOperationException.class, () -> registry.instructions().clear());
        }
    }

    @Test
    void addsDecimalsAndLargeIntegersExactly() throws Exception {
        try (var registry = PluginRegistry.load(null)) {
            var decimal = registry.dispatch(request("math.add", "{\"a\":0.1,\"b\":0.2}"), "Server-001", 1);
            assertEquals(new BigDecimal("0.3"), decimal.result());
            var large = registry.dispatch(request("math.add", "{\"a\":9223372036854775808,\"b\":1}"), "Server-001", 2);
            assertEquals("9223372036854775809", large.result().toString());
        }
    }

    @Test
    void concatenatesUnicodeAndPreservesWhitespace() throws Exception {
        try (var registry = PluginRegistry.load(null)) {
            var reply = registry.dispatch(request("text.concat", "{\"a\":\"你好 \",\"b\":\"🌍\\n\"}"), "Server-001", 1);
            assertEquals("你好 🌍\n", reply.result());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"math.add|{}", "math.add|{\"a\":1}", "math.add|{\"a\":\"1\",\"b\":2}",
            "math.add|{\"a\":null,\"b\":2}", "math.add|{\"a\":1,\"b\":2,\"c\":3}",
            "text.concat|{\"a\":1,\"b\":\"x\"}", "text.concat|{\"a\":\"x\"}",
            "echo|{}", "echo|{\"text\":null}", "echo|{\"text\":3}"})
    void pluginsValidateTheirOwnParameters(String input) throws Exception {
        String[] parts = input.split("\\|", 2);
        try (var registry = PluginRegistry.load(null)) {
            var reply = registry.dispatch(request(parts[0], parts[1]), "Server-001", 1);
            assertEquals("ERROR", reply.status());
            assertEquals(CommandError.Code.INVALID_PARAMETERS, reply.error().code());
        }
    }

    @Test
    void unknownInstructionPreservesCorrelation() throws Exception {
        var request = request("missing.command", "{}");
        try (var registry = PluginRegistry.load(null)) {
            var reply = registry.dispatch(request, "Server-001", 9);
            assertEquals(request.messageId(), reply.messageId());
            assertEquals("Server-001", reply.serverId());
            assertEquals(9, reply.connectionId());
            assertEquals(CommandError.Code.UNKNOWN_INSTRUCTION, reply.error().code());
        }
    }

    @Test
    void duplicateInstructionsFailRegistration() {
        assertThrows(IllegalArgumentException.class, () -> PluginRegistry.of(List.of(
                plugin("duplicate", parameters -> 1), plugin("duplicate", parameters -> 2))));
    }

    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = {" ", "bad name", "bad|name"})
    void rejectsInvalidInstructionNames(String name) {
        assertThrows(IllegalArgumentException.class, () -> PluginRegistry.of(List.of(plugin(name, parameters -> 1))));
    }

    @Test
    void executionFailureDoesNotBreakOtherPluginsAndRestoresContextLoader() throws Exception {
        ClassLoader before = Thread.currentThread().getContextClassLoader();
        try (var registry = PluginRegistry.of(List.of(
                plugin("broken", parameters -> { throw new Exception("Failure"); }),
                plugin("healthy", parameters -> Map.of("ok", true))))) {
            var failed = registry.dispatch(request("broken", "{}"), "Server-001", 1);
            assertEquals(CommandError.Code.PLUGIN_ERROR, failed.error().code());
            assertSame(before, Thread.currentThread().getContextClassLoader());
            var success = registry.dispatch(request("healthy", "{}"), "Server-001", 2);
            assertEquals(Map.of("ok", true), success.result());
        }
    }

    @Test
    void invalidAndNonFiniteResultsBecomePluginErrors() throws Exception {
        for (Object result : List.of(new Object(), Double.NaN, Double.POSITIVE_INFINITY)) {
            try (var registry = PluginRegistry.of(List.of(plugin("invalid", parameters -> result)))) {
                var reply = registry.dispatch(request("invalid", "{}"), "Server-001", 1);
                assertEquals(CommandError.Code.PLUGIN_ERROR, reply.error().code());
            }
        }
    }

    private CommandRequest request(String name, String parameters) throws Exception {
        return new CommandRequest(1, UUID.randomUUID(), name, Json.parameters(Json.read(parameters)));
    }

    private CommandPlugin plugin(String name, Execution execution) {
        return new CommandPlugin() {
            public String instruction() { return name; }
            public Object execute(Map<String, Object> parameters) throws Exception { return execution.run(parameters); }
        };
    }

    @FunctionalInterface
    private interface Execution { Object run(Map<String, Object> parameters) throws Exception; }
}
