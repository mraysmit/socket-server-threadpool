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

import static org.junit.jupiter.api.Assertions.*;

class HttpResponseBuilderTest {
    @Test
    void defaultsProduceAnEmptyOkResponse() {
        var response = new HttpResponseBuilder().build();
        assertEquals("HTTP/1.1 200 OK", response.getStatusLine());
        assertEquals("text/plain; charset=UTF-8", response.getHeaders().get("Content-Type"));
        assertEquals("0", response.getHeaders().get("Content-Length"));
        assertEquals("", response.getBody());
    }

    @Test
    void customResponsePreservesStatusHeadersAndBody() {
        var response = new HttpResponseBuilder().status(201, "Created")
                .contentType("application/json").header("Location", "/items/1")
                .body("{\"id\":1}").build();

        assertEquals("HTTP/1.1 201 Created", response.getStatusLine());
        assertEquals("application/json", response.getHeaders().get("Content-Type"));
        assertEquals("/items/1", response.getHeaders().get("Location"));
        assertEquals("{\"id\":1}", response.getBody());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"hello", "你好 🌍"})
    void calculatedLengthOverridesCallerSuppliedLength(String body) {
        var response = new HttpResponseBuilder().header("Content-Length", "999").body(body).build();
        int expected = body == null ? 0 : body.getBytes(StandardCharsets.UTF_8).length;
        assertEquals(Integer.toString(expected), response.getHeaders().get("Content-Length"));
    }

    @Test
    void reusingBuilderDoesNotModifyEarlierResponse() {
        var builder = new HttpResponseBuilder().body("first");
        var first = builder.build();
        builder.statusLine("HTTP/1.0 202 Accepted").header("X-Test", "changed").body("second").build();

        assertEquals("HTTP/1.1 200 OK", first.getStatusLine());
        assertEquals("first", first.getBody());
        assertEquals("5", first.getHeaders().get("Content-Length"));
        assertFalse(first.getHeaders().containsKey("X-Test"));
    }

    @Test
    void factoriesProduceExpectedStatusesAndBodies() {
        var ok = HttpResponseBuilder.ok("success");
        var notFound = HttpResponseBuilder.notFound();
        var error = HttpResponseBuilder.internalServerError();

        assertEquals("HTTP/1.1 200 OK", ok.getStatusLine());
        assertEquals("success", ok.getBody());
        assertEquals("HTTP/1.1 404 Not Found", notFound.getStatusLine());
        assertEquals("404 - Not Found", notFound.getBody());
        assertEquals("HTTP/1.1 500 Internal Server Error", error.getStatusLine());
        assertEquals("500 - Internal Server Error", error.getBody());
    }
}
