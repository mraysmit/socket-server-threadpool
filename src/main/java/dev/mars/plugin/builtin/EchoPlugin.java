package dev.mars.plugin.builtin;

import dev.mars.plugin.CommandPlugin;
import dev.mars.plugin.InvalidParametersException;
import dev.mars.plugin.PluginParameters;
import java.util.Map;

public final class EchoPlugin implements CommandPlugin {
    @Override public String instruction() { return "echo"; }

    @Override
    public Object execute(Map<String, Object> parameters) throws InvalidParametersException {
        PluginParameters.requireKeys(parameters, "text");
        return "Received: " + PluginParameters.string(parameters, "text");
    }
}
