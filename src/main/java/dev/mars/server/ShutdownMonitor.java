package dev.mars.server;

        import java.io.*;
        import java.net.ServerSocket;
        import java.net.Socket;
        import java.util.concurrent.atomic.AtomicBoolean;

        public class ShutdownMonitor implements Runnable {
            protected final int port;
            protected final ThreadPooledServer server;
            protected ServerSocket serverSocket;
            protected final AtomicBoolean running = new AtomicBoolean(true);

            // Command constants
            private static final String SHUTDOWN_COMMAND = "shutdown";
            private static final String STATUS_COMMAND = "status";

            public ShutdownMonitor(int port, ThreadPooledServer server) {
                this.port = port;
                this.server = server;
            }

            @Override
            public void run() {
                try (ServerSocket socket = new ServerSocket(port)) {
                    this.serverSocket = socket;
                    System.out.println("Shutdown monitor listening on port " + port);

                    while (running.get()) {
                        try (Socket client = socket.accept();
                             BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                             PrintWriter out = new PrintWriter(client.getOutputStream(), true)) {

                            String command = in.readLine();
                            System.out.println("Command received: " + command);

                            if (SHUTDOWN_COMMAND.equalsIgnoreCase(command)) {
                                out.println("Shutting down server...");
                                stop();
                                server.stop();
                                System.out.println("Server stopped by ShutdownMonitor");
                            } else if (STATUS_COMMAND.equalsIgnoreCase(command)) {
                                boolean isAlive = !server.isStopped();
                                out.println("Server status: " + (isAlive ? "ALIVE" : "STOPPED"));
                                System.out.println("Status check: Server is " + (isAlive ? "alive" : "stopped"));
                            } else {
                                out.println("Unknown command. Available commands: status, shutdown");
                            }
                        } catch (IOException e) {
                            if (running.get()) {
                                e.printStackTrace();
                            }
                        }
                    }
                } catch (IOException e) {
                    if (running.get()) {
                        e.printStackTrace();
                    }
                }
            }

            public synchronized void stop() {
                if (running.compareAndSet(true, false)) {
                    try {
                        if (serverSocket != null && !serverSocket.isClosed()) {
                            serverSocket.close();
                            System.out.println("ServerSocket closed");
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }