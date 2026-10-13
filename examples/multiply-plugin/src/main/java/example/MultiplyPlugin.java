/*
 * Copyright 2026 Mark Andrew Ray-Smith
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
