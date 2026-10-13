package dev.mars.plugin.builtin;

import dev.mars.plugin.CommandPlugin;
import dev.mars.plugin.InvalidParametersException;
import dev.mars.plugin.PluginParameters;
import java.util.Map;

public final class TextConcatPlugin implements CommandPlugin {
    @Override public String instruction() { return "text.concat"; }

    @Override
    public Object execute(Map<String, Object> parameters) throws InvalidParametersException {
        PluginParameters.requireKeys(parameters, "a", "b");
        return PluginParameters.string(parameters, "a") + PluginParameters.string(parameters, "b");
    }
}
