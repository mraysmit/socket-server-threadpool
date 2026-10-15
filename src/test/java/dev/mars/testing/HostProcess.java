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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.StartStop;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A host JVM in a separate process, for tests that need real plugin-directory discovery, a classpath without
 * the code under test, or the console entry point. By default the child classpath holds only the host and its
 * JSON dependencies, so a plugin can reach the child only through a {@code --plugins-dir} JAR. All output is
 * captured line by line and echoed to this JVM's standard output.
 */
public final class HostProcess implements AutoCloseable {
    /** Bound listener ports reported by a started server. */
    public record Ports(int port, int adminPort) { }

    private static final Duration REPLY_TIMEOUT = Duration.ofSeconds(15);
    // Distinct instance compared by identity, so no child output can be mistaken for end of output.
    private static final String ENDED = new String("ended");
    private final Process process;
    private final List<String> lines = new ArrayList<>();
    private final BlockingQueue<String> replies = new LinkedBlockingQueue<>();
    private final Thread reader;
    private boolean ended;
    private boolean inputClosed;

    private HostProcess(Process process) {
        this.process = process;
        reader = Thread.ofVirtual().name("host-process-output").start(this::readOutput);
    }

    /** Runs the real console ({@code dev.mars.StartStop}) with automatic ports and the given plugin directories. */
    public static Builder console(String serverId, Path... pluginDirectories) {
        var arguments = new ArrayList<>(List.of(serverId, "--port", "0", "--admin-port", "0"));
        for (Path directory : pluginDirectories) { arguments.add("--plugins-dir"); arguments.add(directory.toString()); }
        return new Builder(StartStop.class.getName(), arguments);
    }

    /** Runs {@link HostProcessMain}, which starts and stops any number of servers on command. */
    public static Builder controller() {
        return new Builder(HostProcessMain.class.getName(), List.of()).addToClasspath(HostProcessMain.class);
    }

    /** Runs any main class, for example a command-line client. */
    public static Builder of(String mainClass, String... arguments) {
        return new Builder(mainClass, List.of(arguments));
    }

    /** The host and its JSON dependencies only; never test classes or plugin classes. */
    public static String hostClasspath() {
        return codeSources(StartStop.class, ObjectMapper.class, JsonFactory.class, JsonProperty.class);
    }

    public static Path javaExecutable() {
        Path windows = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        return Files.exists(windows) ? windows : Path.of(System.getProperty("java.home"), "bin", "java");
    }

    private static String codeSources(Class<?>... types) {
        return Arrays.stream(types).map(type -> {
            try { return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
            catch (URISyntaxException e) { throw new IllegalStateException("Cannot locate " + type.getName(), e); }
        }).distinct().collect(Collectors.joining(File.pathSeparator));
    }

    public static final class Builder {
        private final String mainClass;
        private final List<String> arguments;
        private final Map<String, String> properties = new LinkedHashMap<>();
        private String classpath = hostClasspath();
        private Path holdUntil;

        private Builder(String mainClass, List<String> arguments) {
            this.mainClass = mainClass;
            this.arguments = List.copyOf(arguments);
        }

        public Builder systemProperty(String name, String value) { properties.put(name, value); return this; }

        /** Replaces the default {@link #hostClasspath()}. */
        public Builder classpath(String classpath) { this.classpath = classpath; return this; }

        public Builder addToClasspath(Class<?>... types) {
            classpath = classpath + File.pathSeparator + codeSources(types);
            return this;
        }

        /** Console only: after the console exits, keep the JVM alive until {@code release} exists. */
        public Builder holdAfterExitUntil(Path release) {
            if (!mainClass.equals(StartStop.class.getName())) { throw new IllegalStateException("Only the console can be held"); }
            holdUntil = release;
            return this;
        }

        public HostProcess start() throws IOException {
            String main = mainClass;
            var mainArguments = new ArrayList<>(arguments);
            String path = classpath;
            if (holdUntil != null) {
                main = HostProcessMain.class.getName();
                mainArguments.addAll(0, List.of("--console-hold", holdUntil.toString()));
                path = path + File.pathSeparator + codeSources(HostProcessMain.class);
            }
            var command = new ArrayList<>(List.of(javaExecutable().toString(), "-Ddev.mars.announce=false"));
            properties.forEach((name, value) -> command.add("-D" + name + "=" + value));
            command.addAll(List.of("-cp", path, main));
            // Preserve embedded JSON quotes through Windows' native command-line parser.
            boolean windows = System.getProperty("os.name").startsWith("Windows");
            command.addAll(mainArguments.stream().map(argument -> windows ? argument.replace("\"", "\\\"") : argument).toList());
            return new HostProcess(new ProcessBuilder(command).redirectErrorStream(true).start());
        }
    }

    private void readOutput() {
        try (var output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = output.readLine()) != null) {
                System.out.println(line);
                synchronized (lines) { lines.add(line); lines.notifyAll(); }
                if (line.startsWith(HostProcessMain.REPLY)) { replies.add(line.substring(HostProcessMain.REPLY.length())); }
            }
        } catch (IOException closed) {
            // The process was destroyed; whatever was read remains available.
        } finally {
            synchronized (lines) { ended = true; lines.notifyAll(); }
            replies.add(ENDED);
        }
    }

    /** Writes one line to the child's standard input. */
    public void send(String line) throws IOException { input(line + "\n"); }

    /** Writes text verbatim to the child's standard input. */
    public void input(String text) throws IOException {
        var stdin = process.getOutputStream();
        stdin.write(text.getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    /** Closes standard input, which the console and the controller treat as quit. */
    public void closeInput() throws IOException {
        if (!inputClosed) { inputClosed = true; process.getOutputStream().close(); }
    }

    /** Waits for the first output line, from the start of the process, that contains {@code pattern}. */
    public MatchResult awaitLine(Pattern pattern, Duration timeout) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        int scanned = 0;
        synchronized (lines) {
            while (true) {
                for (; scanned < lines.size(); scanned++) {
                    var match = pattern.matcher(lines.get(scanned));
                    if (match.find()) { return match.toMatchResult(); }
                }
                if (ended) { throw new IOException("Process output ended before " + pattern + ":\n" + outputText()); }
                long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remaining <= 0) { throw new IOException("Timed out waiting for " + pattern + ":\n" + outputText()); }
                try { lines.wait(remaining); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
            }
        }
    }

    /** Waits for the host's readiness line for {@code serverId}. */
    public Ports awaitReady(String serverId, Duration timeout) throws IOException {
        var match = awaitLine(Pattern.compile("Server ready \\| serverId=" + Pattern.quote(serverId)
                + " \\| main=localhost:(\\d+) \\| admin=localhost:(\\d+)"), timeout);
        return new Ports(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)));
    }

    /** Controller only. Throws {@link IOException} with the host's message when startup fails. */
    public Ports startServer(String serverId, int port, int adminPort, Path... pluginDirectories) throws IOException {
        var command = new ArrayList<>(List.of("start", serverId, Integer.toString(port), Integer.toString(adminPort)));
        for (Path directory : pluginDirectories) { command.add(directory.toString()); }
        send(String.join("\t", command));
        String[] reply = reply(REPLY_TIMEOUT);
        if (reply[0].equals("started") && reply[1].equals(serverId)) {
            return new Ports(Integer.parseInt(reply[2]), Integer.parseInt(reply[3]));
        }
        if (reply[0].equals("failed") && reply[1].equals(serverId)) { throw new IOException(reply[2]); }
        throw unexpected(reply);
    }

    /** Controller only. */
    public void stopServer(String serverId) throws IOException {
        send("stop\t" + serverId);
        expect(REPLY_TIMEOUT, "stopped", serverId);
    }

    /** Controller only. Servers stop but the JVM stays alive, so callers can inspect plugin cleanup. */
    public void stopAllServers() throws IOException {
        send("stop-all");
        expect(REPLY_TIMEOUT, "stopped-all");
    }

    /** Held console only: waits until the console has returned while the JVM is still alive. */
    public void awaitConsoleExit(Duration timeout) throws IOException { expect(timeout, "console-exited"); }

    private void expect(Duration timeout, String... expected) throws IOException {
        String[] reply = reply(timeout);
        if (!Arrays.equals(reply, expected)) { throw unexpected(reply); }
    }

    private String[] reply(Duration timeout) throws IOException {
        String reply;
        try { reply = replies.poll(timeout.toNanos(), TimeUnit.NANOSECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
        if (reply == null) { throw new IOException("Timed out waiting for a host reply:\n" + outputText()); }
        if (reply == ENDED) { replies.add(ENDED); throw new IOException("Host process ended:\n" + outputText()); }
        return reply.split("\t", -1);
    }

    private IOException unexpected(String[] reply) {
        String detail = reply[0].equals("error") && reply.length > 1 ? reply[1] : String.join(" ", reply);
        return new IOException("Unexpected host reply: " + detail + "\n" + outputText());
    }

    public List<String> output() { synchronized (lines) { return List.copyOf(lines); } }

    public String outputText() { synchronized (lines) { return String.join("\n", lines); } }

    public boolean isAlive() { return process.isAlive(); }

    /** Waits for exit and for all output to be captured; returns the exit code. */
    public int awaitExit(Duration timeout) throws IOException {
        try {
            if (!process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                throw new IOException("Host process did not exit within " + timeout + ":\n" + outputText());
            }
            reader.join(Duration.ofSeconds(5));
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
        return process.exitValue();
    }

    /** Sends {@code quit}, closes standard input, and waits for exit; returns the exit code. */
    public int stop(Duration timeout) throws IOException {
        if (process.isAlive() && !inputClosed) {
            try { send("quit"); closeInput(); }
            catch (IOException exited) { /* The process closed its input first; waiting reports the outcome. */ }
        }
        return awaitExit(timeout);
    }

    /** Safety net: stops gracefully if still running, then forcibly. Never asserts the exit code. */
    @Override public void close() {
        try {
            if (process.isAlive()) {
                try { stop(Duration.ofSeconds(10)); }
                catch (IOException e) { /* Fall through to forced termination. */ }
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                try {
                    if (!process.waitFor(5, TimeUnit.SECONDS)) { throw new IllegalStateException("Host process did not terminate"); }
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
    }
}
