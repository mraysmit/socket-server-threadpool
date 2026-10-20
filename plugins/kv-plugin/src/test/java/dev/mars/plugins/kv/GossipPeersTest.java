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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GossipPeersTest {
    private final List<Map<String, Object>> calls = new ArrayList<>();
    private final List<Object> replies = new ArrayList<>();

    /** Answers each call with the next canned reply, and fails the call if the reply is an exception. */
    private final Gossip gossip = parameters -> {
        calls.add(parameters);
        var next = replies.remove(0);
        if (next instanceof IOException failure) { throw failure; }
        @SuppressWarnings("unchecked") var reply = (Map<String, Object>) next;
        return reply;
    };
    private final GossipPeers peers = new GossipPeers(gossip, "Server-001");

    private static Map<String, Object> member(String id, int port, String assessment) {
        var row = new LinkedHashMap<String, Object>();
        row.put("serverId", id);
        row.put("host", "127.0.0.1");
        row.put("port", BigInteger.valueOf(port));
        row.put("assessment", assessment);
        return row;
    }

    private static Map<String, Object> page(String nextCursor, Map<String, Object>... members) {
        var page = new LinkedHashMap<String, Object>();
        page.put("members", List.of(members));
        page.put("nextCursor", nextCursor);
        return page;
    }

    @SafeVarargs private void reply(String nextCursor, Map<String, Object>... members) { replies.add(page(nextCursor, members)); }

    @Test void itKeepsTheAliveMembersOtherThanItself() throws Exception {
        reply(null, member("Server-001", 9000, "ALIVE"), member("Server-002", 9100, "ALIVE"), member("Server-003", 9200, "SUSPECT"),
                member("Server-004", 9300, "DEAD"), member("Server-005", 9400, "LEFT"), member("Server-006", 9500, "CANDIDATE"),
                member("Server-007", 9600, "ALIVE"));
        assertEquals(List.of(new Peer("Server-002", "127.0.0.1", 9100), new Peer("Server-007", "127.0.0.1", 9600)), peers.activePeers());
        assertEquals(List.of(Map.of("operation", "members")), calls);
    }

    @Test void itFollowsTheCursorAcrossPages() throws Exception {
        reply("c1", member("Server-002", 9100, "ALIVE"));
        reply("c2", member("Server-003", 9200, "ALIVE"));
        reply(null, member("Server-004", 9300, "ALIVE"));
        assertEquals(List.of("Server-002", "Server-003", "Server-004"), peers.activePeers().stream().map(Peer::serverId).toList());
        assertEquals(List.of(Map.of("operation", "members"), Map.of("operation", "members", "cursor", "c1"),
                Map.of("operation", "members", "cursor", "c2")), calls);
    }

    @Test void aGossipThatCannotAnswerIsReportedToTheCaller() {
        replies.add(new IOException("Unknown instruction: gossip"));
        var failure = assertThrows(IOException.class, peers::activePeers);
        assertTrue(failure.getMessage().contains("gossip"), failure.getMessage());
    }

    @Test void aReplyWithoutAMemberListIsReportedAsABadReply() {
        replies.add(Map.of("swarmId", "alpha"));
        assertThrows(IOException.class, peers::activePeers);
    }

    @Test void aMemberRowWithoutAnEndpointIsReportedAsABadReply() {
        var row = member("Server-002", 9100, "ALIVE");
        row.remove("port");
        reply(null, row);
        var failure = assertThrows(IOException.class, peers::activePeers);
        assertTrue(failure.getMessage().contains("Server-002"), failure.getMessage());
    }

    @Test void aCursorThatNeverEndsStopsTheSearchInsteadOfLoopingForever() {
        for (int i = 0; i < 100; i++) { reply("again", member("Server-002", 9100, "ALIVE")); }
        var failure = assertThrows(IOException.class, peers::activePeers);
        assertTrue(failure.getMessage().contains("pages"), failure.getMessage());
        assertTrue(calls.size() <= 50, "stopped after " + calls.size() + " calls");
    }
}
