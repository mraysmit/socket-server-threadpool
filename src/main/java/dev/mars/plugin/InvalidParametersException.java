package dev.mars.plugin;

/** A plugin's explicit rejection of caller-supplied parameters. */
public class InvalidParametersException extends Exception {
    public InvalidParametersException(String message) { super(message); }
}
