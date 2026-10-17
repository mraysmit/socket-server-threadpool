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

/** Distinguishes malformed records, version conflicts, and bounded-table admission failures. */
public final class MembershipException extends IllegalArgumentException {
    public enum Reason { INVALID_RECORD, CONFLICT, CAPACITY }

    private final Reason reason;
    private final GossipFailure kind;

    public MembershipException(Reason reason, String message) { this(reason, defaultKind(reason), message); }

    /** For a failure whose metrics category is more specific than its reason implies, such as a version mismatch. */
    MembershipException(Reason reason, GossipFailure kind, String message) {
        super(message);
        this.reason = reason;
        this.kind = java.util.Objects.requireNonNull(kind);
    }

    public Reason reason() { return reason; }

    /** The metrics category this failure is counted under. */
    GossipFailure kind() { return kind; }

    /** A record or message that breaks the gossip format, counted as a protocol failure. */
    static MembershipException invalidRecord(String message) {
        return new MembershipException(Reason.INVALID_RECORD, message);
    }

    /**
     * As {@link #invalidRecord(String)}, counted under a more specific metrics category, such as a version mismatch.
     */
    static MembershipException invalidRecord(GossipFailure kind, String message) {
        return new MembershipException(Reason.INVALID_RECORD, kind, message);
    }

    private static GossipFailure defaultKind(Reason reason) {
        return switch (reason) {
            case INVALID_RECORD -> GossipFailure.PROTOCOL;
            case CONFLICT -> GossipFailure.IDENTITY;
            case CAPACITY -> GossipFailure.CAPACITY;
        };
    }
}
