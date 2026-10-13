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
