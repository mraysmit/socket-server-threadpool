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
package dev.mars.plugin.builtin;

import dev.mars.plugin.InvalidParametersException;
import dev.mars.protocol.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class EchoPluginTest {
    @Test
    void advertisesEchoInstruction() {
        assertEquals("echo", new EchoPlugin().instruction());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("messages")
    void returnsReceivedPrefixAndPreservesExactText(String description, String text, String expected) throws Exception {
        var result = new EchoPlugin().execute(Map.of("text", text));
        assertEquals(expected, result);
        System.out.println("Echo plugin test PASSED | case=" + description + " | result=" + Json.write(result));
    }

    static Stream<Arguments> messages() {
        return Stream.of(
                Arguments.of("ordinary text", "Hello server", "Received: Hello server"),
                Arguments.of("Unicode and emoji", "你好 🌍", "Received: 你好 🌍"),
                Arguments.of("surrounding whitespace", "  hello\t ", "Received:   hello\t "),
                Arguments.of("line breaks", "first\nsecond\r\nthird", "Received: first\nsecond\r\nthird"),
                Arguments.of("empty text", "", "Received: "),
                Arguments.of("whitespace-only text", " \t\n", "Received:  \t\n"),
                Arguments.of("JSON-looking text stays literal", "{\"instruction\":\"other.command\"}",
                        "Received: {\"instruction\":\"other.command\"}"));
    }

    @Test
    void repeatedCallsDoNotReusePreviousMessages() throws Exception {
        var plugin = new EchoPlugin();
        assertEquals("Received: first", plugin.execute(Map.of("text", "first")));
        assertEquals("Received: second", plugin.execute(Map.of("text", "second")));
        assertEquals("Received: first", plugin.execute(Map.of("text", "first")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"text\":null}", "{\"text\":3}"})
    void rejectsInvalidParameters(String input) throws Exception {
        var parameters = Json.parameters(Json.read(input));
        assertThrows(InvalidParametersException.class, () -> new EchoPlugin().execute(parameters));
    }
}
