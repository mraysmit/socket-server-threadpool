package dev.mars.plugin;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

public final class PluginParameters {
    private PluginParameters() { }

    public static void requireKeys(Map<String, Object> parameters, String... names) throws InvalidParametersException {
        if (!parameters.keySet().equals(Set.of(names))) {
            throw new InvalidParametersException("Expected exactly these parameters: " + String.join(", ", names));
        }
    }

    public static String string(Map<String, Object> parameters, String name) throws InvalidParametersException {
        if (!(parameters.get(name) instanceof String value)) {
            throw new InvalidParametersException("Parameter '" + name + "' must be a string");
        }
        return value;
    }

    public static BigDecimal number(Map<String, Object> parameters, String name) throws InvalidParametersException {
        if (!(parameters.get(name) instanceof Number value)) {
            throw new InvalidParametersException("Parameter '" + name + "' must be a number");
        }
        try { return new BigDecimal(value.toString()); }
        catch (NumberFormatException e) { throw new InvalidParametersException("Parameter '" + name + "' must be finite"); }
    }
}
