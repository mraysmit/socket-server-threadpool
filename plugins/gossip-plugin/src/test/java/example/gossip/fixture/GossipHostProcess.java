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
package example.gossip.fixture;

import dev.mars.server.ServerInstance;
import dev.mars.server.ServerOptions;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Child-JVM fixture with only host classes and test fixtures on its parent classpath. */
public final class GossipHostProcess {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        if (args.length == 2 && args[1].equals("check-startup-failure")) {
            checkStartupFailure(directory);
            return;
        }
        try (var first = ServerInstance.start(new ServerOptions("Server-A", 0, 0, directory));
             var second = ServerInstance.start(new ServerOptions("Server-B", 0, 0, directory));
             var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            System.out.println("GOSSIP_READY " + first.getPort() + " " + second.getPort());
            String line;
            while ((line = input.readLine()) != null && !line.equals("quit")) {
                if (line.equals("close-first")) {
                    first.close();
                    System.out.println("FIRST_CLOSED");
                } else if (line.equals("close-all")) {
                    first.close();
                    second.close();
                    System.out.println("ALL_CLOSED");
                } else { throw new IllegalArgumentException("Unknown fixture command: " + line); }
            }
        }
    }

    private static void checkStartupFailure(Path directory) throws Exception {
        int mainPort;
        int adminPort;
        try (var main = new ServerSocket(0); var admin = new ServerSocket(0)) {
            mainPort = main.getLocalPort();
            adminPort = admin.getLocalPort();
        }
        try (var unexpected = ServerInstance.start(new ServerOptions("Server-A", mainPort, adminPort, directory))) {
            throw new AssertionError("Invalid gossip configuration unexpectedly started");
        } catch (IOException expected) {
            // Rebind both listeners before signaling success; process exit must not supply cleanup.
            try (var main = new ServerSocket(mainPort); var admin = new ServerSocket(adminPort)) {
                System.out.println("STARTUP_FAILURE " + expected.getMessage());
            }
        }
        try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            if (!"quit".equals(input.readLine())) { throw new AssertionError("Expected quit after rollback"); }
        }
    }
}
