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
package dev.mars.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HttpResponseTest {
    @Test
    void headersAreDefensivelyCopiedOnConstructionAndAccess() {
        var headers = new HashMap<>(Map.of("Content-Type", "text/plain"));
        var response = new HttpResponse("HTTP/1.1 200 OK", headers, "hello");

        headers.put("Content-Type", "application/json");
        response.getHeaders().clear();

        assertEquals(Map.of("Content-Type", "text/plain"), response.getHeaders());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"hello", "你好 🌍"})
    void contentLengthCountsUtf8Bytes(String body) {
        var response = new HttpResponse("HTTP/1.1 200 OK", Map.of(), body);
        assertEquals(body == null ? 0 : body.getBytes(StandardCharsets.UTF_8).length,
                response.getContentLength());
    }
}
