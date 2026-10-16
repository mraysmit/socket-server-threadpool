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

import java.util.UUID;

import static dev.mars.plugins.gossip.MembershipException.Reason.INVALID_RECORD;

/** A process identity. UUID ordering carries no restart ordering semantics. */
public record MemberKey(String serverId, UUID bootId) {
    public MemberKey {
        if (!GossipValidation.validId(serverId) || bootId == null) {
            throw new MembershipException(INVALID_RECORD, "Member requires a valid serverId and bootId");
        }
    }

    public static MemberKey fromWire(String serverId, String bootId) {
        if (!canonical(bootId)) { throw new MembershipException(INVALID_RECORD, "bootId must be a canonical lowercase UUID"); }
        return new MemberKey(serverId, UUID.fromString(bootId));
    }

    /** The only form UUID.toString prints: lowercase hex digits with hyphens at 8, 13, 18 and 23, 36 characters long. */
    private static boolean canonical(String uuid) {
        if (uuid == null || uuid.length() != 36) { return false; }
        for (int i = 0; i < 36; i++) {
            char c = uuid.charAt(i);
            boolean valid = i == 8 || i == 13 || i == 18 || i == 23 ? c == '-' : (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!valid) { return false; }
        }
        return true;
    }
}
