package dev.mars;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class ShutdownMonitor implements Runnable {
    private final int port;
    private final ThreadPooledServer server;
    private ServerSocket serverSocket;
    private boolean running = true;

    public ShutdownMonitor(int port, ThreadPooledServer server) {
        this.port = port;
        this.server = server;
    }

    @Override
    public void run() {
        try (ServerSocket socket = new ServerSocket(port)) {
            this.serverSocket = socket;
            System.out.println("Shutdown monitor listening on port " + port);

            while (running) {
                Socket client = socket.accept();
                System.out.println("Shutdown command received");
                client.close();
                server.stop();
                running = false;
            }
        } catch (IOException e) {
            if (running) {
                e.printStackTrace();
            }
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}