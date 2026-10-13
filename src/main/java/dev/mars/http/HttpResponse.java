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
 * Represents an HTTP response with status, headers, and body.
 * This class follows the Single Responsibility Principle by only handling HTTP response data.
 */
public class HttpResponse {
    private final String statusLine;
    private final Map<String, String> headers;
    private final String body;

    public HttpResponse(String statusLine, Map<String, String> headers, String body) {
        this.statusLine = statusLine;
        this.headers = new HashMap<>(headers);
        this.body = body;
    }

    public String getStatusLine() {
        return statusLine;
    }

    public Map<String, String> getHeaders() {
        return new HashMap<>(headers);
    }

    public String getBody() {
        return body;
    }

    /**
     * Calculates the content length of the response body.
     * @return the length of the body in bytes
     */
    public int getContentLength() {
        return body != null ? body.getBytes(StandardCharsets.UTF_8).length : 0;
    }
}
