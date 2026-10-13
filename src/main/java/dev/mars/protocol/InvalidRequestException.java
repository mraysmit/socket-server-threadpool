package dev.mars.protocol;

import java.util.UUID;

public class InvalidRequestException extends Exception {
    private final UUID messageId;

    public InvalidRequestException(UUID messageId, String message) {
        super(message);
        this.messageId = messageId;
    }

    public UUID messageId() { return messageId; }
}
