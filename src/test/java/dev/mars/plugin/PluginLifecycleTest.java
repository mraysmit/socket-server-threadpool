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

import dev.mars.protocol.CommandRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class PluginLifecycleTest {
    private final PluginContext context = new PluginContext("Lifecycle-001", 9010, 9011);

    @Test
    void stopsAllEnteredProvidersExactlyOnceAfterStartupSettles() throws Exception {
        var events = events();
        var registry = PluginRegistry.of(List.of(new RecordingPlugin("first", events),
                new RecordingPlugin("second", events), new RecordingPlugin("third", events)));
        try (registry) {
            registry.start(context);
            registry.stop();
            registry.stop();
        }
        registry.close();
        assertAllStopped(events, Set.of("first", "second", "third"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closeStopsPartiallyInitializedProviderAndAllConcurrentPeers(boolean assertionFails) throws Exception {
        var events = events();
        var failing = new RecordingPlugin("failing", events);
        failing.startFailure = assertionFails ? new AssertionError("Partially initialized") : new IOException("Partially initialized");
        try (var registry = PluginRegistry.of(List.of(new RecordingPlugin("first", events), failing,
                new RecordingPlugin("peer", events)))) {
            var error = assertThrows(IOException.class, () -> registry.start(context));
            assertSame(failing.startFailure, error.getCause());
        }
        assertAllStopped(events, Set.of("first", "failing", "peer"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"linkage", "assertion", "other-error"})
    void stopAttemptsEveryHookAggregatesFailuresAndRestoresContextLoader(String errorType) throws Exception {
        var events = events();
        var first = new RecordingPlugin("first", events);
        var second = new RecordingPlugin("second", events);
        var third = new RecordingPlugin("third", events);
        second.stopFailure = switch (errorType) {
            case "linkage" -> new NoClassDefFoundError("Missing shutdown dependency");
            case "assertion" -> new AssertionError("Shutdown assertion failed");
            default -> new Error("Other shutdown error");
        };
        third.stopFailure = new IOException("Shutdown failed");
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader sentinel = new ClassLoader(original) { };
        Thread.currentThread().setContextClassLoader(sentinel);
        try (var registry = PluginRegistry.of(List.of(first, second, third))) {
            registry.start(context);
            var error = assertThrows(IOException.class, registry::stop);
            assertEquals(1, error.getSuppressed().length);
            assertEquals(Set.of(second.stopFailure, third.stopFailure),
                    Set.of(error.getCause(), error.getSuppressed()[0].getCause()));
            assertSame(sentinel, Thread.currentThread().getContextClassLoader());
            registry.stop();
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
        assertAllStopped(events, Set.of("first", "second", "third"));
    }

    @Test
    void closeFallbackStopsProvidersAndPropagatesStopFailureOnce() throws Exception {
        var events = events();
        var plugin = new RecordingPlugin("failing", events);
        plugin.stopFailure = new IOException("Close failed");
        var registry = PluginRegistry.of(List.of(plugin));
        registry.start(context);
        assertSame(plugin.stopFailure, assertThrows(IOException.class, registry::close).getCause());
        assertDoesNotThrow(registry::close);
        assertEquals(List.of("start:failing", "stop:failing"), events);
    }

    @Test
    void interruptedHookStillAllowsRemainingCleanupAndRestoresInterruptStatus() throws Exception {
        var events = events();
        var interrupted = new RecordingPlugin("interrupted", events);
        interrupted.stopFailure = new InterruptedException("Interrupted during shutdown");
        try (var registry = PluginRegistry.of(List.of(new RecordingPlugin("first", events), interrupted))) {
            registry.start(context);
            try {
                var failure = assertThrows(IOException.class, registry::stop);
                assertSame(interrupted.stopFailure, failure.getCause());
                assertTrue(Thread.currentThread().isInterrupted());
                assertAllStopped(events, Set.of("first", "interrupted"));
            } finally { Thread.interrupted(); }
        }
    }

    @Test
    void stopBeforeStartupDoesNotCallHooksAndPreventsLaterStartup() throws Exception {
        var events = events();
        try (var registry = PluginRegistry.of(List.of(new RecordingPlugin("unstarted", events)))) {
            registry.stop();
            assertThrows(IOException.class, () -> registry.start(context));
        }
        assertTrue(events.isEmpty());
    }

    @Test
    void closeWithoutStartupDoesNotCallStopHook() throws Exception {
        var events = events();
        var registry = PluginRegistry.of(List.of(new RecordingPlugin("unstarted", events)));
        registry.close();
        registry.close();
        assertTrue(events.isEmpty());
    }

    @Test
    void concurrentStopAndCloseCallsInvokeHookOnce() throws Exception {
        var events = events();
        try (var registry = PluginRegistry.of(List.of(new RecordingPlugin("shared", events)));
             var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            registry.start(context);
            var go = new CountDownLatch(1);
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 12; i++) {
                boolean close = i % 2 == 0;
                tasks.add(callers.submit(() -> {
                    assertTrue(go.await(3, TimeUnit.SECONDS));
                    if (close) { registry.close(); } else { registry.stop(); }
                    return null;
                }));
            }
            go.countDown();
            for (var task : tasks) { task.get(5, TimeUnit.SECONDS); }
        }
        assertEquals(List.of("start:shared", "stop:shared"), events);
    }

    @Test
    void defaultStopIsOptionalAndQuiescingRetainsCommandStateUntilDrained() throws Exception {
        var plugin = new CommandPlugin() {
            @Override public String instruction() { return "test.lifecycle"; }
            @Override public Object execute(Map<String, Object> parameters) { return "retained state"; }
        };
        try (var registry = PluginRegistry.of(List.of(plugin))) {
            registry.start(context);
            registry.stop();
            var reply = registry.dispatch(new CommandRequest(1, UUID.randomUUID(), "test.lifecycle", Map.of()),
                    context.serverId(), 1);
            assertEquals("OK", reply.status());
            assertEquals("retained state", reply.result());
        }
    }

    private static List<String> events() { return Collections.synchronizedList(new ArrayList<>()); }

    private static void assertAllStopped(List<String> events, Set<String> names) {
        var starts = events.stream().filter(event -> event.startsWith("start:")).map(event -> event.substring(6)).toList();
        var stops = events.stream().filter(event -> event.startsWith("stop:")).map(event -> event.substring(5)).toList();
        assertEquals(names.size(), starts.size());
        assertEquals(names, Set.copyOf(starts));
        assertEquals(names.size(), stops.size());
        assertEquals(names, Set.copyOf(stops));
        assertTrue(events.subList(0, names.size()).stream().allMatch(event -> event.startsWith("start:")),
                "Stop hooks must wait for all startup tasks");
    }

    private static final class RecordingPlugin implements CommandPlugin {
        private final String name;
        private final List<String> events;
        private Throwable startFailure;
        private Throwable stopFailure;

        RecordingPlugin(String name, List<String> events) { this.name = name; this.events = events; }

        @Override public void start(PluginContext context) throws Exception {
            assertTrue(Thread.currentThread().isVirtual());
            assertSame(getClass().getClassLoader(), Thread.currentThread().getContextClassLoader());
            events.add("start:" + name);
            if (startFailure instanceof Error error) { throw error; }
            if (startFailure instanceof Exception exception) { throw exception; }
        }

        @Override public void stop() throws Exception {
            assertSame(getClass().getClassLoader(), Thread.currentThread().getContextClassLoader());
            events.add("stop:" + name);
            if (stopFailure instanceof Error error) { throw error; }
            if (stopFailure instanceof Exception exception) { throw exception; }
        }
    }
}
