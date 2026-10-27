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

/**
 * Whether a server is running or frozen. A frozen server behaves like a process stopped by a debugger or a long pause:
 * it keeps its sockets and its state but does nothing, so its peers see it go silent without it closing anything. The
 * host stops accepting and answering while frozen, and a plugin with work of its own (rounds, timers, background sync)
 * calls {@link #awaitRunning()} before each piece of it. Thawing lets everything carry on where it stopped.
 */
public final class RunGate {
    private boolean frozen;

    public synchronized void freeze() { frozen = true; }

    public synchronized void thaw() {
        frozen = false;
        notifyAll();
    }

    public synchronized boolean frozen() { return frozen; }

    /** Returns at once while running; while frozen, waits until thawed. */
    public synchronized void awaitRunning() throws InterruptedException {
        while (frozen) { wait(); }
    }
}
