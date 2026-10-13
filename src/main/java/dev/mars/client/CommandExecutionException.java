package dev.mars.client;

import dev.mars.protocol.CommandReply;
import java.io.IOException;

/** A validated, correlated error reply returned by the server. */
public class CommandExecutionException extends IOException {
    private final CommandReply reply;

    public CommandExecutionException(CommandReply reply) {
        super("Server rejected message: " + reply);
        this.reply = reply;
    }

    public CommandReply reply() { return reply; }
}
