package dev.mars;

import java.io.*;
import java.net.Socket;

public class WorkerRunnable implements Runnable {

    protected Socket clientSocket = null;
    protected String serverText = null;

    public WorkerRunnable(Socket clientSocket, String serverText) {
        this.clientSocket = clientSocket;
        this.serverText = serverText;
    }

    @Override
    public void run() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             PrintWriter writer = new PrintWriter(clientSocket.getOutputStream(), true)) {
            long time = System.currentTimeMillis();

            // Log the request processing
            System.out.println("Request processed: at " + time + " from " + serverText + " on thread " + Thread.currentThread().getName());

            // Write a proper HTTP response
            String responseBody = "WorkerRunnable: " + this.serverText + " - " + time;
            writer.println("HTTP/1.1 200 OK");
            writer.println("Content-Type: text/plain; charset=UTF-8");
            writer.println("Content-Length: " + responseBody.length());
            writer.println();
            writer.println(responseBody);

        } catch (IOException e) {
            // Log the exception with more context
            System.err.println("Error processing client request: " + e.getMessage());
            e.printStackTrace();
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