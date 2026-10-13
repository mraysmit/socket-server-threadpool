package dev.mars.protocol;

import java.util.UUID;

public record CommandReply(int version, UUID messageId, String serverId, long connectionId,
                           String status, Object result, CommandError error) {
    public static CommandReply success(CommandRequest request, String serverId, long connectionId, Object result) {
        return new CommandReply(1, request.messageId(), serverId, connectionId, "OK", result, null);
    }

    public static CommandReply failure(UUID messageId, String serverId, long connectionId,
                                       CommandError.Code code, String message) {
        return new CommandReply(1, messageId, serverId, connectionId, "ERROR", null, new CommandError(code, message));
    }

    @Override
    public String toString() {
        try {
            return "messageId=" + messageId + " | serverId=" + serverId + " | connectionId=" + connectionId
                    + " | status=" + status + (error == null ? " | result=" + Json.write(result)
                    : " | error=" + error.code() + ": " + error.message());
        } catch (Exception e) {
            throw new IllegalStateException("Reply contains an invalid JSON result", e);
        }
    }
}
