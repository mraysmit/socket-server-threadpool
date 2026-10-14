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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class ConcurrentPluginStartupTest {
    private final PluginContext context = new PluginContext("Concurrent-001", 9010, 9011);

    @Test
    void hooksOverlapOnVirtualThreadsAndPreserveCallerContextLoader() throws Exception {
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var active = new AtomicInteger();
        Action start = () -> {
            active.incrementAndGet();
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            finally { active.decrementAndGet(); }
        };
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader sentinel = new ClassLoader(original) { };
        Thread.currentThread().setContextClassLoader(sentinel);
        try (var registry = PluginRegistry.of(List.of(plugin(start, () -> { }), plugin(start, () -> { })));
             var caller = Executors.newVirtualThreadPerTaskExecutor()) {
            var startup = caller.submit(() -> {
                registry.start(context);
                assertSame(sentinel, Thread.currentThread().getContextClassLoader());
                return null;
            });
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS), "Both hooks must enter before either returns");
                assertEquals(2, active.get());
                assertFalse(startup.isDone(), "Startup must wait for both hooks");
            } finally { release.countDown(); }
            startup.get(5, TimeUnit.SECONDS);
            assertEquals(0, active.get());
            assertSame(sentinel, Thread.currentThread().getContextClassLoader());
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"stop", "close"})
    void failuresAreAggregatedAfterAllHooksSettleAndBeforeCleanup(String operation) throws Exception {
        var failed = new CountDownLatch(2);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var active = new AtomicBoolean();
        var stops = new AtomicInteger();
        Action stop = () -> { assertFalse(active.get(), "Startup and shutdown must not overlap"); stops.incrementAndGet(); };
        var first = plugin(() -> { failed.countDown(); throw new IOException("First startup failure"); }, stop);
        var slow = plugin(() -> {
            active.set(true);
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            finally { active.set(false); }
        }, stop);
        var third = plugin(() -> { failed.countDown(); throw new IOException("Second startup failure"); }, stop);
        try (var registry = PluginRegistry.of(List.of(first, slow, third));
             var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var startup = callers.submit(() -> { registry.start(context); return null; });
            try {
                assertTrue(failed.await(3, TimeUnit.SECONDS));
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                var cleanupIssued = new CountDownLatch(1);
                var cleanup = callers.submit(() -> {
                    cleanupIssued.countDown();
                    if (operation.equals("stop")) { registry.stop(); } else { registry.close(); }
                    return null;
                });
                assertTrue(cleanupIssued.await(3, TimeUnit.SECONDS));
                assertFalse(startup.isDone(), "A failed peer must not let startup abandon a running hook");
                assertFalse(cleanup.isDone());
                assertEquals(0, stops.get());
                release.countDown();
                var error = assertThrows(ExecutionException.class, () -> startup.get(5, TimeUnit.SECONDS));
                var failure = assertInstanceOf(IOException.class, error.getCause());
                assertEquals(1, failure.getSuppressed().length);
                assertEquals(Set.of("First startup failure", "Second startup failure"),
                        Set.of(failure.getCause().getMessage(), failure.getSuppressed()[0].getCause().getMessage()));
                cleanup.get(5, TimeUnit.SECONDS);
                assertEquals(3, stops.get());
            } finally { release.countDown(); }
        }
        assertEquals(3, stops.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"stop", "close"})
    void shutdownDuringSuccessfulInitializationWaitsForEveryHook(String operation) throws Exception {
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var active = new AtomicInteger();
        var stops = new AtomicInteger();
        Action start = () -> {
            active.incrementAndGet();
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            finally { active.decrementAndGet(); }
        };
        Action stop = () -> { assertEquals(0, active.get()); stops.incrementAndGet(); };
        try (var registry = PluginRegistry.of(List.of(plugin(start, stop), plugin(start, stop)));
             var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var startup = callers.submit(() -> { registry.start(context); return null; });
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                var cleanupIssued = new CountDownLatch(1);
                var cleanup = callers.submit(() -> {
                    cleanupIssued.countDown();
                    if (operation.equals("stop")) { registry.stop(); } else { registry.close(); }
                    return null;
                });
                assertTrue(cleanupIssued.await(3, TimeUnit.SECONDS));
                assertFalse(cleanup.isDone());
                assertEquals(0, stops.get());
                release.countDown();
                startup.get(5, TimeUnit.SECONDS);
                cleanup.get(5, TimeUnit.SECONDS);
                assertEquals(2, stops.get());
            } finally { release.countDown(); }
        }
        assertEquals(2, stops.get());
    }

    @Test
    void interruptedCallerCancelsStartupButWaitsForActualWorkerExitBeforeRollback() throws Exception {
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var active = new AtomicBoolean();
        var stops = new AtomicInteger();
        var failure = new AtomicReference<IOException>();
        var restoredInterrupt = new AtomicBoolean();
        var plugin = plugin(() -> {
            active.set(true);
            entered.countDown();
            try {
                try { release.await(); }
                catch (InterruptedException e) { interrupted.countDown(); release.await(); }
            } finally { active.set(false); }
        }, () -> { assertFalse(active.get()); stops.incrementAndGet(); });
        try (var registry = PluginRegistry.of(List.of(plugin))) {
            var caller = Thread.ofVirtual().start(() -> {
                try { registry.start(context); }
                catch (IOException e) {
                    failure.set(e);
                    restoredInterrupt.set(Thread.currentThread().isInterrupted());
                }
            });
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                caller.interrupt();
                assertTrue(interrupted.await(3, TimeUnit.SECONDS));
                assertTrue(caller.isAlive(), "Future cancellation must not be mistaken for hook completion");
                assertTrue(active.get());
                assertEquals(0, stops.get());
                release.countDown();
                caller.join(5000);
                assertFalse(caller.isAlive());
                assertNotNull(failure.get());
                assertInstanceOf(InterruptedException.class, failure.get().getCause());
                assertTrue(restoredInterrupt.get());
                registry.stop();
                assertEquals(1, stops.get());
            } finally {
                release.countDown();
                caller.join(5000);
            }
        }
        assertEquals(1, stops.get());
    }

    @Test
    void emptyRegistryCanStartAndStop() throws Exception {
        try (var registry = PluginRegistry.of(List.of())) {
            registry.start(context);
            registry.stop();
            assertTrue(registry.instructions().isEmpty());
        }
    }

    private CommandPlugin plugin(Action start, Action stop) {
        return new CommandPlugin() {
            @Override public void start(PluginContext supplied) throws Exception {
                assertTrue(Thread.currentThread().isVirtual());
                assertSame(getClass().getClassLoader(), Thread.currentThread().getContextClassLoader());
                assertSame(context, supplied);
                start.run();
            }
            @Override public void stop() throws Exception {
                assertSame(getClass().getClassLoader(), Thread.currentThread().getContextClassLoader());
                stop.run();
            }
        };
    }

    @FunctionalInterface
    private interface Action { void run() throws Exception; }
}
