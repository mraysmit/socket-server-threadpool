package dev.mars.http;

import java.util.HashMap;
import java.util.Map;

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
        return body != null ? body.getBytes().length : 0;
    }
}
