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

import java.util.UUID;

import static gossip.MembershipException.Reason.INVALID_RECORD;

/** A process identity. UUID ordering carries no restart ordering semantics. */
public record MemberKey(String serverId, UUID bootId) {
    public MemberKey {
        if (!GossipValidation.validId(serverId) || bootId == null) {
            throw new MembershipException(INVALID_RECORD, "Member requires a valid serverId and bootId");
        }
    }

    public static MemberKey fromWire(String serverId, String bootId) {
        UUID parsed;
        try { parsed = UUID.fromString(bootId); }
        catch (IllegalArgumentException | NullPointerException e) {
            throw new MembershipException(INVALID_RECORD, "bootId must be a canonical UUID");
        }
        if (!parsed.toString().equals(bootId)) {
            throw new MembershipException(INVALID_RECORD, "bootId must be a canonical lowercase UUID");
        }
        return new MemberKey(serverId, parsed);
    }
}
