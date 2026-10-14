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
package dev.mars.plugin.fixtures;

import dev.mars.plugin.CommandPlugin;
import dev.mars.plugin.InvalidParametersException;
import dev.mars.plugin.PluginParameters;
import java.util.Map;

public final class MathAddPlugin implements CommandPlugin {
    @Override public String instruction() { return "math.add"; }

    @Override
    public Object execute(Map<String, Object> parameters) throws InvalidParametersException {
        PluginParameters.requireKeys(parameters, "a", "b");
        return PluginParameters.number(parameters, "a").add(PluginParameters.number(parameters, "b"));
    }
}
