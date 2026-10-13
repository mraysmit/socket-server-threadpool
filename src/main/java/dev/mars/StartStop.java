package dev.mars;

import dev.mars.client.TextMessageClient;
import dev.mars.server.ShutdownMonitor;
import dev.mars.server.ThreadPooledServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.UUID;

public class StartStop {

    /**
     * Main entry point - starts the server and shutdown monitor, then provides a console interface for commands.
     */
    public static void main(String[] args) {

        var threadPooledServer = new ThreadPooledServer(9000);
        var shutdownMonitor = new ShutdownMonitor(9001, threadPooledServer);

        System.out.println("Starting Main Server");
        new Thread(threadPooledServer).start();
        System.out.println("Starting Shutdown Monitor");
        new Thread(shutdownMonitor).start();

        System.out.println("To stop server, connect to port 9001");

        try (BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in))) {
            System.out.println("> Enter commands (send <text>, status, shutdown, or quit to exit):");
            String command;
            while (true) {
                System.out.print("> ");
                command = consoleReader.readLine();
                if (command == null || command.equalsIgnoreCase("quit")) {
                    break;
                }

                if (command.equalsIgnoreCase("status") || command.equalsIgnoreCase("shutdown")) {
                    checkServerStatus(command);
                } else if (command.regionMatches(true, 0, "send ", 0, 5)) {
                    try {
                        String message = command.substring(5);
                        UUID messageId = UUID.randomUUID();
                        System.out.println("Sending: messageId=" + messageId + " | text=" + message);
                        System.out.println("Server response: " + new TextMessageClient("localhost", 9000).send(messageId, message));
                    } catch (IOException | IllegalArgumentException e) {
                        System.out.println("Error sending message: " + e.getMessage());
                    }
                } else if (command.equalsIgnoreCase("send")) {
                    System.out.println("Usage: send <text>");
                } else {
                    System.out.println("Unknown command. Available commands: send <text>, status, shutdown, quit");
                }
            }
        } catch (IOException e) {
            System.out.println("Error reading from console: " + e.getMessage());
        }

        //checkServerStatus("status");

    }

    public static void checkServerStatus(String cmd) {
        try (Socket socket = new Socket("localhost", 9001);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            out.println(cmd);
            String response = in.readLine();

            System.out.println("Server response: " + response);

        } catch (IOException e) {
            System.out.println("Error connecting to server: " + e.getMessage());
        }
    }


}
