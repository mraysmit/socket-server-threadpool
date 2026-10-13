package dev.mars.plugin;

import java.util.Map;

/** Startup-discovered provider. Instances are shared: implementations must be thread-safe. */
public interface CommandPlugin {
    String instruction();
    Object execute(Map<String, Object> parameters) throws Exception;
}
