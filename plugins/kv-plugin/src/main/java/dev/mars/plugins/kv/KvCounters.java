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
package dev.mars.plugins.kv;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** What replication has done on this server so far. */
final class KvCounters {
    final AtomicLong pushed = new AtomicLong();
    final AtomicLong pushFailures = new AtomicLong();
    final AtomicLong applied = new AtomicLong();
    final AtomicLong ignored = new AtomicLong();
    final AtomicLong rejected = new AtomicLong();
    /** Of the rejected records, those stamped too far ahead of this server's clock. */
    final AtomicLong tooFarAhead = new AtomicLong();
    /** Records announced to a peer by key and version instead of sent whole, and of those, the ones it asked for. */
    final AtomicLong announced = new AtomicLong();
    final AtomicLong fetched = new AtomicLong();

    /** The counters as a status reply shows them, in a fixed order. */
    Map<String, Object> toMap() {
        var counters = new LinkedHashMap<String, Object>();
        counters.put("pushed", pushed.get());
        counters.put("pushFailures", pushFailures.get());
        counters.put("applied", applied.get());
        counters.put("ignored", ignored.get());
        counters.put("rejected", rejected.get());
        counters.put("tooFarAhead", tooFarAhead.get());
        counters.put("announced", announced.get());
        counters.put("fetched", fetched.get());
        return counters;
    }
}
