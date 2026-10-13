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
