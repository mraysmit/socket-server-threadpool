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

import dev.mars.plugin.InvalidParametersException;
import dev.mars.protocol.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MathAddPluginTest {
    @Test
    void addsDecimalsAndLargeIntegersExactly() throws Exception {
        var plugin = new MathAddPlugin();
        assertEquals(new BigDecimal("0.3"),
                plugin.execute(Map.of("a", new BigDecimal("0.1"), "b", new BigDecimal("0.2"))));
        assertEquals(new BigDecimal("9223372036854775809"),
                plugin.execute(Map.of("a", new BigInteger("9223372036854775808"), "b", 1)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"a\":1}", "{\"a\":\"1\",\"b\":2}",
            "{\"a\":null,\"b\":2}", "{\"a\":1,\"b\":2,\"c\":3}"})
    void rejectsInvalidParameters(String input) throws Exception {
        var parameters = Json.parameters(Json.read(input));
        assertThrows(InvalidParametersException.class, () -> new MathAddPlugin().execute(parameters));
    }
}
