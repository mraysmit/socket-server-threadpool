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

    public MembershipException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
