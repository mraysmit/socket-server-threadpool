package dev.mars.protocol;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

public final class CommandProtocol {
    private CommandProtocol() { }

    public static CommandRequest parseRequest(String text) throws InvalidRequestException {
        UUID id = null;
        try {
            JsonNode root = Json.read(text);
            if (root == null || !root.isObject()) { throw new IllegalArgumentException("Request must be a JSON object"); }
            if (root.path("messageId").isTextual()) { id = parseUuid(root.get("messageId").textValue()); }
            if (id == null) { throw new IllegalArgumentException("A valid message UUID is required"); }
            checkFields(root, Set.of("version", "messageId", "instruction", "parameters"));
            if (!root.path("version").isIntegralNumber() || !root.get("version").canConvertToInt()
                    || root.get("version").intValue() != 1) {
                throw new IllegalArgumentException("Protocol version must be 1");
            }
            if (!root.path("instruction").isTextual() || !root.path("parameters").isObject()) {
                throw new IllegalArgumentException("Instruction must be a string and parameters must be an object");
            }
            return new CommandRequest(1, id, root.get("instruction").textValue(), Json.parameters(root.get("parameters")));
        } catch (Exception e) {
            throw new InvalidRequestException(id, e instanceof IllegalArgumentException ? e.getMessage() : "Malformed JSON request");
        }
    }

    /** Adds transport metadata to the console's instruction/parameters object. */
    public static CommandRequest submission(UUID id, String text) throws IOException {
        try {
            JsonNode root = Json.read(text);
            if (root == null || !root.isObject()) { throw new IllegalArgumentException("Command must be a JSON object"); }
            checkFields(root, Set.of("instruction", "parameters"));
            if (!root.path("instruction").isTextual() || !root.path("parameters").isObject()) {
                throw new IllegalArgumentException("Command requires instruction and parameters");
            }
            return new CommandRequest(1, id, root.get("instruction").textValue(), Json.parameters(root.get("parameters")));
        } catch (Exception e) {
            throw new IOException("Invalid command: " + e.getMessage(), e);
        }
    }

    public static CommandReply parseReply(String text) throws IOException {
        try {
            JsonNode root = Json.read(text);
            if (root == null || !root.isObject() || !root.path("version").isIntegralNumber()
                    || !root.get("version").canConvertToInt() || root.get("version").intValue() != 1) {
                throw new IllegalArgumentException("Reply version must be 1");
            }
            UUID id = parseUuid(requiredText(root, "messageId"));
            String serverId = requiredText(root, "serverId");
            if (!serverId.matches("[A-Za-z0-9._-]+")) { throw new IllegalArgumentException("Invalid server ID"); }
            JsonNode connection = root.path("connectionId");
            if (!connection.isIntegralNumber() || !connection.canConvertToLong() || connection.longValue() <= 0) {
                throw new IllegalArgumentException("Connection ID must be a positive integer");
            }
            String status = requiredText(root, "status");
            if (status.equals("OK")) {
                if (!root.has("result") || (root.hasNonNull("error"))) { throw new IllegalArgumentException("Invalid success reply"); }
                return new CommandReply(1, id, serverId, connection.longValue(), status, Json.value(root.get("result")), null);
            }
            if (!status.equals("ERROR") || !root.path("error").isObject() || root.hasNonNull("result")) {
                throw new IllegalArgumentException("Invalid error reply");
            }
            var error = root.get("error");
            return new CommandReply(1, id, serverId, connection.longValue(), status, null,
                    new CommandError(CommandError.Code.valueOf(requiredText(error, "code")), requiredText(error, "message")));
        } catch (Exception e) {
            throw new IOException("Invalid message reply: " + e.getMessage(), e);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        if (!node.path(field).isTextual() || node.get(field).textValue().isBlank()) {
            throw new IllegalArgumentException("Missing or invalid " + field);
        }
        return node.get(field).textValue();
    }

    private static UUID parseUuid(String text) {
        UUID id = UUID.fromString(text);
        if (!id.toString().equalsIgnoreCase(text)) { throw new IllegalArgumentException("Invalid message UUID"); }
        return id;
    }

    private static void checkFields(JsonNode root, Set<String> allowed) {
        root.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) { throw new IllegalArgumentException("Unknown field: " + field); }
        });
    }
}
