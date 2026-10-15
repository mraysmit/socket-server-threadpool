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
package gossip;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Per-instance, bounded console diagnostics. Never log member metadata or raw requests/replies. */
final class GossipLog {
    private enum Repeated { IDENTITY_CONFLICT, ROUND_FAILED }
    private static final long INTERVAL_NANOS = Duration.ofSeconds(30).toNanos();
    private static final int FIELD_LIMIT = 512;
    private final String context;
    private final LongSupplier clock;
    private final Consumer<String> sink;
    private final Map<Repeated, Window> windows = new EnumMap<>(Repeated.class);

    GossipLog(GossipConfig config, MemberRecord self) {
        this(config, self, System::nanoTime, line -> System.err.println(line));
    }

    GossipLog(GossipConfig config, MemberRecord self, LongSupplier clock, Consumer<String> sink) {
        this.clock = clock;
        this.sink = sink;
        context = " | serverId=" + field(self.key().serverId()) + " | bootId=" + self.key().bootId()
                + " | swarmId=" + field(config.swarmId());
        for (var event : Repeated.values()) { windows.put(event, new Window()); }
    }

    void identity(String direction, GossipConfig.Seed peer, UUID messageId, String reason) {
        long suppressed = permit(Repeated.IDENTITY_CONFLICT);
        if (suppressed < 0) { return; }
        emit("WARN", "IDENTITY_CONFLICT", " | direction=" + field(direction)
                + " | peerServerId=" + field(peer.serverId()) + " | peerHost=" + field(peer.host())
                + " | peerPort=" + peer.port() + " | messageId=" + (messageId == null ? "-" : messageId)
                + " | reason=" + field(reason) + " | suppressed=" + suppressed);
    }

    void roundFailure(RuntimeException failure) {
        long suppressed = permit(Repeated.ROUND_FAILED);
        if (suppressed >= 0) { emit("WARN", "ROUND_FAILED", " | error=" + error(failure) + " | suppressed=" + suppressed); }
    }

    void cleanupFailure(String resource, Throwable failure) {
        emit("ERROR", "CLEANUP_FAILED", " | resource=" + field(resource) + " | error=" + error(failure));
    }

    void departure(Map<String, Object> outcome) {
        String state = (String) outcome.get("state");
        boolean completedCleanly = state.equals("COMPLETED") && ((Number) outcome.get("failures")).intValue() == 0;
        String level = completedCleanly || state.equals("CANCELLED") ? "INFO" : "WARN";
        emit(level, "DEPARTURE_FINISHED", " | state=" + field(state) + " | targets=" + outcome.get("targets")
                + " | attempts=" + outcome.get("attempts") + " | successes=" + outcome.get("successes")
                + " | failures=" + outcome.get("failures") + " | skipped=" + outcome.get("skipped")
                + " | cancelled=" + outcome.get("cancelled"));
    }

    /** Only the constant-size limiter state is locked; console output occurs after releasing it. */
    private synchronized long permit(Repeated event) {
        var window = windows.get(event);
        long now = clock.getAsLong();
        if (window.emitted && now - window.last < INTERVAL_NANOS) {
            if (window.suppressed < Long.MAX_VALUE) { window.suppressed++; }
            return -1;
        }
        long suppressed = window.suppressed;
        window.emitted = true;
        window.last = now;
        window.suppressed = 0;
        return suppressed;
    }

    private void emit(String level, String event, String details) {
        try { sink.accept("[" + Instant.now() + "] " + level + " | plugin=gossip | event=" + event + context + details); }
        catch (RuntimeException unavailable) { /* Diagnostic output must not replace protocol or cleanup failures. */ }
    }

    private static String error(Throwable failure) {
        return field(failure.getClass().getSimpleName() + ": " + failure.getMessage());
    }

    private static String field(String value) {
        String text = value == null ? "-" : value;
        var escaped = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (escaped.length() >= FIELD_LIMIT) { escaped.append("..."); break; }
            char character = text.charAt(i);
            if (character == '\\') { escaped.append("\\\\"); }
            else if (character == '|') { escaped.append("\\u007c"); }
            else if (Character.isISOControl(character) || character == '\u2028' || character == '\u2029') {
                escaped.append("\\u%04x".formatted((int) character));
            } else { escaped.append(character); }
        }
        return escaped.toString();
    }

    private static final class Window {
        boolean emitted;
        long last;
        long suppressed;
    }
}
