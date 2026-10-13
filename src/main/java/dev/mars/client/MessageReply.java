package dev.mars.client;

import java.util.UUID;

/** A reply whose message ID has been checked against the request. */
public record MessageReply(UUID messageId, UUID serverId, long connectionId, String text) {
    @Override
    public String toString() {
        return "messageId=" + messageId + " | serverId=" + serverId
                + " | connectionId=" + connectionId + " | text=" + text;
    }
}
