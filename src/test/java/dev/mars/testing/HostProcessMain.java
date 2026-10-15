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
package dev.mars.testing;

import dev.mars.StartStop;
import dev.mars.server.ServerInstance;
import dev.mars.server.ServerOptions;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Child-JVM entry point driven by {@link HostProcess}. Without arguments it reads tab-separated commands
 * from standard input ({@code start}, {@code stop}, {@code stop-all}, {@code quit}) and answers each with one
 * line prefixed by {@value #REPLY}. With {@code --console-hold <file>} it runs the real console and then keeps
 * the JVM alive until that file exists, so callers can inspect cleanup before the process exits.
 */
public final class HostProcessMain {
    static final String REPLY = "@host ";

    private HostProcessMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length >= 2 && args[0].equals("--console-hold")) {
            consoleThenHold(Path.of(args[1]), Arrays.copyOfRange(args, 2, args.length));
        } else if (args.length == 0) {
            control();
        } else {
            throw new IllegalArgumentException("Usage: HostProcessMain [--console-hold <file> <console arguments>...]");
        }
    }

    private static void consoleThenHold(Path release, String[] consoleArguments) throws Exception {
        StartStop.main(consoleArguments);
        reply("console-exited");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!Files.exists(release)) {
            if (System.nanoTime() >= deadline) { throw new IllegalStateException("Release file was not created: " + release); }
            Thread.sleep(20);
        }
    }

    private static void control() throws IOException {
        var servers = new LinkedHashMap<String, ServerInstance>();
        try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null && !line.equals("quit")) {
                String[] fields = line.split("\t", -1);
                try {
                    switch (fields[0]) {
                        case "start" -> start(servers, fields);
                        case "stop" -> stop(servers, fields);
                        case "stop-all" -> { stopAll(servers); reply("stopped-all"); }
                        default -> reply("error", "Unknown command: " + fields[0]);
                    }
                } catch (RuntimeException e) { reply("error", describe(e)); }
            }
        } finally { stopAll(servers); }
    }

    private static void start(Map<String, ServerInstance> servers, String[] fields) {
        if (fields.length < 4) { throw new IllegalArgumentException("start requires serverId, port and admin port"); }
        String serverId = fields[1];
        if (servers.containsKey(serverId)) { throw new IllegalArgumentException("Server is already running: " + serverId); }
        var directories = Arrays.stream(fields, 4, fields.length).map(Path::of).toList();
        try {
            var server = ServerInstance.start(new ServerOptions(serverId, Integer.parseInt(fields[2]),
                    Integer.parseInt(fields[3]), directories));
            servers.put(serverId, server);
            reply("started", serverId, Integer.toString(server.getPort()), Integer.toString(server.getAdminPort()));
        } catch (IOException e) { reply("failed", serverId, describe(e)); }
    }

    private static void stop(Map<String, ServerInstance> servers, String[] fields) {
        var server = fields.length == 2 ? servers.remove(fields[1]) : null;
        if (server == null) { throw new IllegalArgumentException("No running server: " + String.join(" ", fields)); }
        server.close();
        reply("stopped", fields[1]);
    }

    private static void stopAll(Map<String, ServerInstance> servers) {
        RuntimeException failure = null;
        for (var server : servers.values()) {
            try { server.close(); }
            catch (RuntimeException e) { if (failure == null) { failure = e; } else { failure.addSuppressed(e); } }
        }
        servers.clear();
        if (failure != null) { throw failure; }
    }

    private static String describe(Exception failure) {
        String message = failure.getMessage() == null ? failure.getClass().getName() : failure.getMessage();
        return message.replaceAll("[\\t\\r\\n]+", " ");
    }

    private static void reply(String... fields) {
        System.out.println(REPLY + String.join("\t", fields));
        System.out.flush();
    }
}
