package example;

import dev.mars.plugin.CommandPlugin;
import dev.mars.plugin.InvalidParametersException;
import dev.mars.plugin.PluginParameters;

import java.math.BigDecimal;
import java.util.Map;

/** Stateless provider loaded from this JAR by the server's plugin class loader. */
public final class MultiplyPlugin implements CommandPlugin {
    @Override
    public String instruction() { return "math.multiply"; }

    @Override
    public BigDecimal execute(Map<String, Object> parameters) throws InvalidParametersException {
        PluginParameters.requireKeys(parameters, "a", "b");
        return PluginParameters.number(parameters, "a")
                .multiply(PluginParameters.number(parameters, "b"));
    }
}
