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

/**
 * Field names of the plugin's JSON messages: the exchange, the members page and the status reply. Peers and operators
 * read them, so each is written once, here. A member record's own fields are {@link MemberRecord}'s.
 */
final class GossipFields {
    // An exchange request and its reply.
    static final String OPERATION = "operation";
    static final String GOSSIP_VERSION = "gossipVersion";
    static final String SWARM_ID = "swarmId";
    static final String SENDER = "sender";
    static final String MEMBERS = "members";
    static final String SENDER_ADMISSION = "senderAdmission";
    /** The operation an exchange request names. */
    static final String EXCHANGE_OPERATION = "exchange";

    // A members page, and the capacity it and the status reply report.
    static final String SELF = "self";
    static final String NEXT_CURSOR = "nextCursor";
    static final String CAPACITY = "capacity";
    static final String ACTIVE_MEMBERS = "activeMembers";
    static final String RETAINED_IDENTITIES = "retainedIdentities";
    static final String TOMBSTONES = "tombstones";
    static final String ADMISSIONS_SKIPPED = "admissionsSkipped";

    // A members request's parameters.
    static final String LIMIT = "limit";
    static final String CURSOR = "cursor";

    private GossipFields() { }
}
