package dev.mars.plugin;

import java.util.Map;

/** Startup-discovered provider. Instances are shared: implementations must be thread-safe. */
public interface CommandPlugin {
    /** Optional initialization after both TCP ports bind. */
    default void start(PluginContext context) throws Exception { }

    /** A null instruction identifies a startup-only provider. */
    default String instruction() { return null; }

    /** Command handlers override this along with instruction(). */
    default Object execute(Map<String, Object> parameters) throws Exception {
        throw new UnsupportedOperationException("This plugin has no command handler");
    }
}
