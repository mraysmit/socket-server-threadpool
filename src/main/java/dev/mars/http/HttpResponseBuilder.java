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

import java.util.HashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;

/**
 * Builder class for creating HTTP responses.
 * This class follows the Builder pattern and Single Responsibility Principle
 * by focusing solely on constructing HTTP response objects.
 */
public class HttpResponseBuilder {
    private String statusLine = "HTTP/1.1 200 OK";
    private Map<String, String> headers = new HashMap<>();
    private String body = "";

    public HttpResponseBuilder() {
        // Set default headers
        headers.put("Content-Type", "text/plain; charset=UTF-8");
    }

    /**
     * Sets the HTTP status line.
     * @param statusLine the status line (e.g., "HTTP/1.1 200 OK")
     * @return this builder for method chaining
     */
    public HttpResponseBuilder statusLine(String statusLine) {
        this.statusLine = statusLine;
        return this;
    }

    /**
     * Sets the HTTP status with a standard format.
     * @param statusCode the HTTP status code (e.g., 200, 404, 500)
     * @param reasonPhrase the reason phrase (e.g., "OK", "Not Found", "Internal Server Error")
     * @return this builder for method chaining
     */
    public HttpResponseBuilder status(int statusCode, String reasonPhrase) {
        this.statusLine = "HTTP/1.1 " + statusCode + " " + reasonPhrase;
        return this;
    }

    /**
     * Adds a header to the response.
     * @param name the header name
     * @param value the header value
     * @return this builder for method chaining
     */
    public HttpResponseBuilder header(String name, String value) {
        this.headers.put(name, value);
        return this;
    }

    /**
     * Sets the content type header.
     * @param contentType the content type (e.g., "text/plain", "application/json")
     * @return this builder for method chaining
     */
    public HttpResponseBuilder contentType(String contentType) {
        this.headers.put("Content-Type", contentType);
        return this;
    }

    /**
     * Sets the response body.
     * @param body the response body content
     * @return this builder for method chaining
     */
    public HttpResponseBuilder body(String body) {
        this.body = body;
        return this;
    }

    /**
     * Builds the HTTP response.
     * Automatically sets the Content-Length header based on the body.
     * @return the constructed HttpResponse
     */
    public HttpResponse build() {
        // Automatically set Content-Length header
        headers.put("Content-Length", String.valueOf(
                body == null ? 0 : body.getBytes(StandardCharsets.UTF_8).length));
        
        return new HttpResponse(statusLine, headers, body);
    }

    /**
     * Creates a simple 200 OK response with plain text content.
     * @param content the response content
     * @return the constructed HttpResponse
     */
    public static HttpResponse ok(String content) {
        return new HttpResponseBuilder()
                .status(200, "OK")
                .contentType("text/plain; charset=UTF-8")
                .body(content)
                .build();
    }

    /**
     * Creates a simple 404 Not Found response.
     * @return the constructed HttpResponse
     */
    public static HttpResponse notFound() {
        return new HttpResponseBuilder()
                .status(404, "Not Found")
                .body("404 - Not Found")
                .build();
    }

    /**
     * Creates a simple 500 Internal Server Error response.
     * @return the constructed HttpResponse
     */
    public static HttpResponse internalServerError() {
        return new HttpResponseBuilder()
                .status(500, "Internal Server Error")
                .body("500 - Internal Server Error")
                .build();
    }
}
