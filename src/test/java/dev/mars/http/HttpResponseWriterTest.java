package dev.mars.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HttpResponseWriterTest {
    private final HttpResponseWriter responseWriter = new HttpResponseWriter();

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"hello", "你好 🌍", "line one\nline two\n"})
    void serializesExactBodyBytesWithHttpLineEndings(String body) throws IOException {
        var output = new ByteArrayOutputStream();
        var response = HttpResponseBuilder.ok(body);
        responseWriter.writeResponse(response, new PrintWriter(output, false, StandardCharsets.UTF_8));

        String wire = output.toString(StandardCharsets.UTF_8);
        int separator = wire.indexOf("\r\n\r\n");
        assertTrue(separator >= 0, "HTTP headers must end with CRLF CRLF");
        assertTrue(wire.startsWith("HTTP/1.1 200 OK\r\n"));
        String headers = wire.substring(0, separator);
        assertTrue(headers.contains("\r\nContent-Type: text/plain; charset=UTF-8"));
        assertTrue(headers.contains("\r\nContent-Length: " + response.getContentLength()));
        String actualBody = wire.substring(separator + 4);
        assertEquals(body == null ? "" : body, actualBody);
        assertEquals(response.getContentLength(), actualBody.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void rejectsNullArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> responseWriter.writeResponse(null, new PrintWriter(OutputStream.nullOutputStream())));
        assertThrows(IllegalArgumentException.class,
                () -> responseWriter.writeResponse(HttpResponseBuilder.ok("hello"), null));
    }

    @Test
    void reportsUnderlyingOutputFailure() {
        var brokenStream = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("output unavailable");
            }
        };
        assertThrows(IOException.class, () -> responseWriter.writeOkResponse("hello",
                new PrintWriter(brokenStream, false, StandardCharsets.UTF_8)));
    }

    @Test
    void writesResponseWithoutBody() throws IOException {
        var output = new ByteArrayOutputStream();
        responseWriter.writeResponse(new HttpResponse("HTTP/1.1 204 No Content", Map.of(), null),
                new PrintWriter(output, false, StandardCharsets.UTF_8));
        assertEquals("HTTP/1.1 204 No Content\r\n\r\n", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void convenienceMethodsWriteExpectedErrorResponses() throws IOException {
        var missing = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        responseWriter.writeNotFoundResponse(new PrintWriter(missing, false, StandardCharsets.UTF_8));
        responseWriter.writeInternalServerErrorResponse(new PrintWriter(error, false, StandardCharsets.UTF_8));

        assertTrue(missing.toString(StandardCharsets.UTF_8).startsWith("HTTP/1.1 404 Not Found\r\n"));
        assertTrue(missing.toString(StandardCharsets.UTF_8).endsWith("\r\n\r\n404 - Not Found"));
        assertTrue(error.toString(StandardCharsets.UTF_8).startsWith("HTTP/1.1 500 Internal Server Error\r\n"));
        assertTrue(error.toString(StandardCharsets.UTF_8).endsWith("\r\n\r\n500 - Internal Server Error"));
    }
}
