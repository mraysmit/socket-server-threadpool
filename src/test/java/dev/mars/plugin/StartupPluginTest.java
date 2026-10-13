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

import dev.mars.plugin.builtin.AddressBroadcastPlugin;
import dev.mars.protocol.CommandRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StartupPluginTest {
    @Test
    void discoversStartupOnlyBuiltin() {
        assertTrue(ServiceLoader.load(CommandPlugin.class).stream()
                .anyMatch(provider -> provider.type() == AddressBroadcastPlugin.class));
    }

    @Test
    void callsOptedInCommandInstanceOnceWithResolvedContextAndCorrectLoader() throws Exception {
        var plugin = new BothCapabilities();
        var context = new PluginContext("Server-001", 9010, 9011);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (var registry = PluginRegistry.of(List.of(plugin))) {
            registry.start(context);
            assertEquals(1, plugin.calls);
            assertSame(context, plugin.context);
            assertSame(previous, Thread.currentThread().getContextClassLoader());
            assertThrows(IOException.class, () -> registry.start(context));
            var reply = registry.dispatch(new CommandRequest(1, UUID.randomUUID(), "test.startup", Map.of()), "Server-001", 1);
            assertEquals("ready", reply.result());
        }
    }

    @Test
    void failedHookRestoresLoaderAndIsNotRetried() throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        var plugin = new CommandPlugin() {
            @Override public void start(PluginContext context) throws IOException { throw new IOException("Failed hook"); }
        };
        try (var registry = PluginRegistry.of(List.of(plugin))) {
            var context = new PluginContext("Server-001", 9010, 9011);
            var error = assertThrows(IOException.class, () -> registry.start(context));
            assertTrue(error.getMessage().contains("Failed hook"));
            assertSame(previous, Thread.currentThread().getContextClassLoader());
            assertThrows(IOException.class, () -> registry.start(context));
        }
    }

    @Test
    void commandPluginsWithoutStartupCapabilityRemainUsable() throws Exception {
        try (var registry = PluginRegistry.of(List.of(new dev.mars.plugin.builtin.MathAddPlugin()))) {
            registry.start(new PluginContext("Server-001", 9010, 9011));
            var reply = registry.dispatch(new CommandRequest(1, UUID.randomUUID(), "math.add", Map.of("a", 10, "b", 20)),
                    "Server-001", 1);
            assertEquals("30", reply.result().toString());
        }
    }

    @Test
    void closedRegistryCannotStart() throws Exception {
        var registry = PluginRegistry.of(List.of(new CommandPlugin() {
            @Override public void start(PluginContext context) { fail("Should not start"); }
        }));
        registry.close();
        assertThrows(IOException.class, () -> registry.start(new PluginContext("Server-001", 9010, 9011)));
    }

    @Test
    void startupOnlyProviderHasNoInstructionAndIsNotDispatched() throws Exception {
        var plugin = new CommandPlugin() { };
        assertNull(plugin.instruction());
        assertThrows(UnsupportedOperationException.class, () -> plugin.execute(Map.of()));
        try (var registry = PluginRegistry.of(List.of(plugin))) {
            registry.start(new PluginContext("Server-001", 9010, 9011));
            assertTrue(registry.instructions().isEmpty());
            var reply = registry.dispatch(new CommandRequest(1, UUID.randomUUID(), "missing", Map.of()), "Server-001", 1);
            assertEquals(dev.mars.protocol.CommandError.Code.UNKNOWN_INSTRUCTION, reply.error().code());
        }
    }

    private static final class BothCapabilities implements CommandPlugin {
        int calls;
        PluginContext context;
        public String instruction() { return "test.startup"; }
        public Object execute(Map<String, Object> parameters) { return context == null ? "not started" : "ready"; }
        public void start(PluginContext context) {
            assertSame(getClass().getClassLoader(), Thread.currentThread().getContextClassLoader());
            calls++;
            this.context = context;
        }
    }
}
