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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds peers by asking this server's own gossip plugin for its members: the ones it rates alive, other than this
 * server. The members come a page at a time, and the cursor on each page leads to the next.
 */
final class GossipPeers implements PeerSource {
    private final Gossip gossip;
    private final String selfId;

    GossipPeers(Gossip gossip, String selfId) {
        this.gossip = gossip;
        this.selfId = selfId;
    }

    @Override public List<Peer> activePeers() throws IOException {
        var peers = new ArrayList<Peer>();
        var seenCursors = new HashSet<String>();
        String cursor = null;
        int pages = 0;
        do {
            var request = new LinkedHashMap<String, Object>();
            request.put("operation", "members");
            if (cursor != null) { request.put("cursor", cursor); }
            var page = gossip.call(request);
            pages++;
            if (!(page.get("members") instanceof List<?> rows)) { throw new IOException("Gossip's members reply has no member list"); }
            for (var row : rows) { addIfAlive(peers, row); }
            cursor = page.get("nextCursor") instanceof String next ? next : null;
            if (cursor != null && !seenCursors.add(cursor)) {
                throw new IOException("Gossip repeated a members cursor, so the search stopped after " + pages + " pages");
            }
        } while (cursor != null);
        return List.copyOf(peers);
    }

    private void addIfAlive(List<Peer> peers, Object row) throws IOException {
        if (!(row instanceof Map<?, ?> member) || !(member.get("serverId") instanceof String id)) {
            throw new IOException("Gossip returned a member row without a server ID");
        }
        if (!"ALIVE".equals(member.get("assessment")) || id.equals(selfId)) { return; }
        if (!(member.get("host") instanceof String host) || !(member.get("port") instanceof Number port)) {
            throw new IOException("Gossip returned member " + id + " without a host and port");
        }
        peers.add(new Peer(id, host, port.intValue()));
    }
}
