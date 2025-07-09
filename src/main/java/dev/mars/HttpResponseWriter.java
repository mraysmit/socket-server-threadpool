package dev.mars;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;

/**
 * Service class responsible for writing HTTP responses to output streams.
 * This class follows the Single Responsibility Principle by focusing solely
 * on the task of writing HTTP responses to output streams.
 */
public class HttpResponseWriter {

    /**
     * Writes an HTTP response to the provided PrintWriter.
     * 
     * @param response the HttpResponse to write
     * @param writer the PrintWriter to write to
     * @throws IOException if an I/O error occurs during writing
     */
    public void writeResponse(HttpResponse response, PrintWriter writer) throws IOException {
        if (response == null) {
            throw new IllegalArgumentException("HttpResponse cannot be null");
        }
        if (writer == null) {
            throw new IllegalArgumentException("PrintWriter cannot be null");
        }

        try {
            // Write status line
            writer.println(response.getStatusLine());

            // Write headers
            Map<String, String> headers = response.getHeaders();
            for (Map.Entry<String, String> header : headers.entrySet()) {
                writer.println(header.getKey() + ": " + header.getValue());
            }

            // Write empty line to separate headers from body
            writer.println();

            // Write body
            if (response.getBody() != null) {
                writer.println(response.getBody());
            }

            // Ensure all data is sent
            writer.flush();

        } catch (Exception e) {
            throw new IOException("Failed to write HTTP response", e);
        }
    }

    /**
     * Convenience method to create and write a simple 200 OK response.
     * 
     * @param content the response content
     * @param writer the PrintWriter to write to
     * @throws IOException if an I/O error occurs during writing
     */
    public void writeOkResponse(String content, PrintWriter writer) throws IOException {
        HttpResponse response = HttpResponseBuilder.ok(content);
        writeResponse(response, writer);
    }

    /**
     * Convenience method to create and write a 404 Not Found response.
     * 
     * @param writer the PrintWriter to write to
     * @throws IOException if an I/O error occurs during writing
     */
    public void writeNotFoundResponse(PrintWriter writer) throws IOException {
        HttpResponse response = HttpResponseBuilder.notFound();
        writeResponse(response, writer);
    }

    /**
     * Convenience method to create and write a 500 Internal Server Error response.
     * 
     * @param writer the PrintWriter to write to
     * @throws IOException if an I/O error occurs during writing
     */
    public void writeInternalServerErrorResponse(PrintWriter writer) throws IOException {
        HttpResponse response = HttpResponseBuilder.internalServerError();
        writeResponse(response, writer);
    }
}
