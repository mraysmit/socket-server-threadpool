package dev.mars.worker;

import dev.mars.http.HttpResponse;
import dev.mars.http.HttpResponseBuilder;
import dev.mars.http.HttpResponseWriter;

import java.io.*;
import java.net.Socket;

/**
 * Worker class that handles individual client requests.
 * This class now follows SOLID principles by delegating HTTP response creation
 * to specialized classes, adhering to the Single Responsibility Principle.
 */
public class WorkerRunnable implements Runnable {

    protected Socket clientSocket = null;
    protected String serverText = null;
    private final HttpResponseWriter responseWriter;

    public WorkerRunnable(Socket clientSocket, String serverText) {
        this.clientSocket = clientSocket;
        this.serverText = serverText;
        this.responseWriter = new HttpResponseWriter();
    }

    /**
     * Constructor that allows dependency injection of HttpResponseWriter.
     * This follows the Dependency Inversion Principle.
     */
    public WorkerRunnable(Socket clientSocket, String serverText, HttpResponseWriter responseWriter) {
        this.clientSocket = clientSocket;
        this.serverText = serverText;
        this.responseWriter = responseWriter;
    }

    @Override
    public void run() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             PrintWriter writer = new PrintWriter(clientSocket.getOutputStream(), true)) {

            long time = System.currentTimeMillis();

            // Log the request processing
            System.out.println("Request processed: at " + time + " from " + serverText + " on thread " + Thread.currentThread().getName());

            // Create response content
            String responseBody = "WorkerRunnable: " + this.serverText + " - " + time;

            // Use the HttpResponseBuilder and HttpResponseWriter to create and send response
            HttpResponse response = new HttpResponseBuilder()
                    .status(200, "OK")
                    .contentType("text/plain; charset=UTF-8")
                    .body(responseBody)
                    .build();

            responseWriter.writeResponse(response, writer);

        } catch (IOException e) {
            // Log the exception with more context
            System.err.println("Error processing client request: " + e.getMessage());
            e.printStackTrace();

            // Try to send an error response
            try (PrintWriter errorWriter = new PrintWriter(clientSocket.getOutputStream(), true)) {
                responseWriter.writeInternalServerErrorResponse(errorWriter);
            } catch (IOException errorResponseException) {
                System.err.println("Failed to send error response: " + errorResponseException.getMessage());
            }
        } finally {
            try {
                if (clientSocket != null) {
                    clientSocket.close();
                }
            } catch (IOException e) {
                System.err.println("Error closing client socket: " + e.getMessage());
            }
        }
    }
}