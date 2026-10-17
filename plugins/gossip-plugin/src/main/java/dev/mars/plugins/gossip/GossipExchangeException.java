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

import java.io.IOException;

/** An exchange failure the transport detected itself. It carries its metrics category, so nothing parses its message. */
final class GossipExchangeException extends IOException {
    private final GossipFailure kind;

    GossipExchangeException(GossipFailure kind, String message) { this(kind, message, null); }

    GossipExchangeException(GossipFailure kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = java.util.Objects.requireNonNull(kind);
    }

    /** The metrics category this failure is counted under. */
    GossipFailure kind() { return kind; }
}
