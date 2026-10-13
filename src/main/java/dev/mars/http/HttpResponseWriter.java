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
            writer.print(response.getStatusLine() + "\r\n");

            // Write headers
            Map<String, String> headers = response.getHeaders();
            for (Map.Entry<String, String> header : headers.entrySet()) {
                writer.print(header.getKey() + ": " + header.getValue() + "\r\n");
            }

            // Write empty line to separate headers from body
            writer.print("\r\n");

            // Write body
            if (response.getBody() != null) {
                writer.print(response.getBody());
            }

            // Ensure all data is sent
            writer.flush();
            if (writer.checkError()) {
                throw new IOException("Output stream reported a write failure");
            }

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
