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
package dev.mars;

import dev.mars.client.TextMessageClient;
import dev.mars.server.ServerInstance;
import dev.mars.server.ServerOptions;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class StartStop {
    public static void main(String[] args) throws IOException {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println(ServerOptions.USAGE);
            return;
        }
        var options = ServerOptions.parse(args);
        try (var instance = ServerInstance.start(options);
            var console = new BufferedReader(new InputStreamReader(System.in))) {
            var client = new TextMessageClient("localhost", instance.getPort(), instance.getServerId());
            System.out.println("To stop this server, connect to admin port " + instance.getAdminPort());
            System.out.println("> Enter commands (send <text or JSON command>, status, shutdown, or quit to exit):");
            while (true) {
                System.out.print("> ");
                String command = console.readLine();
                if (command == null || command.equalsIgnoreCase("quit")) { break; }
                if (command.equalsIgnoreCase("status") || command.equalsIgnoreCase("shutdown")) {
                    checkServerStatus(command, instance.getAdminPort());
                } else if (command.regionMatches(true, 0, "send ", 0, 5)) {
                    try {
                        String message = command.substring(5);
                        UUID messageId = UUID.randomUUID();
                        System.out.println("Sending: messageId=" + messageId + " | text=" + message);
                        System.out.println("Server response: " + client.executeInput(messageId, message));
                    } catch (IOException | IllegalArgumentException e) {
                        System.out.println("Error sending message: " + e.getMessage());
                    }
                } else if (command.equalsIgnoreCase("send")) {
                    System.out.println("Usage: send <text or JSON command>");
                } else {
                    System.out.println("Unknown command. Available commands: send <text or JSON command>, status, shutdown, quit");
                }
            }
        }
    }

    public static void checkServerStatus(String command) {
        checkServerStatus(command, ServerOptions.DEFAULT_ADMIN_PORT);
    }

    public static void checkServerStatus(String command, int port) {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 5000);
            socket.setSoTimeout(10000);
            try (var out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
                 var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                out.println(command);
                System.out.println("Server response: " + in.readLine());
            }
        } catch (IOException e) {
            System.out.println("Error connecting to admin port " + port + ": " + e.getMessage());
        }
    }
}
