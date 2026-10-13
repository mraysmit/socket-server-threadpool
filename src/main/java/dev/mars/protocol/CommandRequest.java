package dev.mars.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record CommandRequest(int version, UUID messageId, String instruction, Map<String, Object> parameters) {
    public CommandRequest {
        if (version != 1) { throw new IllegalArgumentException("Unsupported protocol version"); }
        if (messageId == null) { throw new IllegalArgumentException("Message ID is required"); }
        if (instruction == null || !instruction.matches("[A-Za-z][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("Instruction must be a nonblank command name");
        }
        if (parameters == null) { throw new IllegalArgumentException("Parameters must be an object"); }
        parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    }
}
