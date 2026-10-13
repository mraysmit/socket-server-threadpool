package dev.mars.protocol;

public record CommandError(Code code, String message) {
    public enum Code { INVALID_REQUEST, UNKNOWN_INSTRUCTION, INVALID_PARAMETERS, PLUGIN_ERROR }
}
