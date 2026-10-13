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
package dev.mars.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.core.json.JsonWriteFeature;

import java.util.Map;

/** Shared, immutable-after-configuration JSON codec; never enables Java type metadata. */
public final class Json {
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonWriteFeature.WRITE_NAN_AS_STRINGS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .build();

    private Json() { }

    public static JsonNode read(String text) throws JsonProcessingException {
        return MAPPER.readTree(text);
    }

    public static String write(Object value) throws JsonProcessingException {
        return MAPPER.writeValueAsString(value);
    }

    public static Map<String, Object> parameters(JsonNode node) {
        return MAPPER.convertValue(node, new TypeReference<>() { });
    }

    public static Object value(JsonNode node) {
        return MAPPER.convertValue(node, Object.class);
    }

    public static Object normalizeResult(Object value) throws JsonProcessingException {
        return MAPPER.readValue(write(value), Object.class);
    }
}
