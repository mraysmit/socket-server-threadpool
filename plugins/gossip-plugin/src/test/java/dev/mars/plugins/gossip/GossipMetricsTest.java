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
package dev.mars.plugins.gossip;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GossipMetricsTest {
    @Test void agesAreUnknownUntilTheFirstRoundAndSuccessThenMeasuredFromThem() {
        var metrics = new GossipMetrics();
        assertTrue(metrics.isolated());
        assertNull(metrics.lastSuccessAgeMillis());
        assertTrue(metrics.runtime().containsKey("lastRoundAgeMillis"));
        assertNull(metrics.runtime().get("lastRoundAgeMillis"));
        metrics.success(true);
        metrics.lastRound = System.nanoTime();
        assertFalse(metrics.isolated());
        assertTrue(metrics.lastSuccessAgeMillis() >= 0);
        assertTrue((Long) metrics.runtime().get("lastRoundAgeMillis") >= 0);
    }

    @Test void agesAreReportedInWholeMilliseconds() throws InterruptedException {
        var metrics = new GossipMetrics();
        metrics.success(true);
        metrics.lastRound = System.nanoTime();
        Thread.sleep(25);
        long success = metrics.lastSuccessAgeMillis();
        long round = (Long) metrics.runtime().get("lastRoundAgeMillis");
        // At least the time slept, and nowhere near the 25,000 it would read if counted in microseconds.
        assertTrue(success >= 20 && success < 10_000, "success age " + success);
        assertTrue(round >= 20 && round < 10_000, "round age " + round);
    }
}
