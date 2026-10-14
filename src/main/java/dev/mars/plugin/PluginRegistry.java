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
package dev.mars.plugin;

import dev.mars.protocol.CommandReply;
import dev.mars.protocol.CommandRequest;
import dev.mars.protocol.Json;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;

import static dev.mars.protocol.CommandError.Code.*;

/** Immutable registry, eagerly discovered before any listener starts. */
public final class PluginRegistry implements AutoCloseable {
    private final Map<String, CommandPlugin> plugins;
    private final URLClassLoader loader;
    private final List<CommandPlugin> loadedPlugins;
    private boolean startupAttempted;
    private boolean closed;

    private PluginRegistry(Iterable<CommandPlugin> providers, URLClassLoader loader) {
        var selected = new LinkedHashMap<String, CommandPlugin>();
        var discovered = new java.util.ArrayList<CommandPlugin>();
        for (var provider : providers) {
            discovered.add(java.util.Objects.requireNonNull(provider, "provider"));
            String instruction = provider.instruction();
            if (instruction == null) { continue; }
            if (!instruction.matches("[A-Za-z][A-Za-z0-9._-]*")) {
                throw new IllegalArgumentException("Invalid plugin instruction: " + instruction);
            }
            if (selected.putIfAbsent(instruction, provider) != null) {
                throw new IllegalArgumentException("Duplicate plugin instruction: " + instruction);
            }
        }
        plugins = Map.copyOf(selected);
        loadedPlugins = discovered.stream()
                .sorted(java.util.Comparator.comparing(plugin -> plugin.getClass().getName())).toList();
        this.loader = loader;
    }

    public static PluginRegistry of(Collection<CommandPlugin> providers) {
        return new PluginRegistry(providers, null);
    }

    public static PluginRegistry load(Path directory) throws IOException {
        return loadDirectories(directory == null ? List.of() : List.of(directory));
    }

    /** JARs from all supplied folders share a loader; only immediate .jar files are scanned. */
    public static PluginRegistry loadDirectories(List<Path> directories) throws IOException {
        URLClassLoader loader = null;
        try {
            directories = List.copyOf(directories);
            ClassLoader parent = CommandPlugin.class.getClassLoader();
            ClassLoader discovery = parent;
            if (!directories.isEmpty()) {
                var folders = new java.util.LinkedHashSet<Path>();
                var paths = new java.util.LinkedHashSet<Path>();
                for (Path supplied : directories) {
                    if (!Files.isDirectory(supplied)) { throw new IOException("Plugin directory does not exist: " + supplied); }
                    Path directory = supplied.toRealPath();
                    if (!folders.add(directory)) { continue; }
                    try (var files = Files.list(directory)) {
                        for (Path path : files.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                            .sorted().toList()) {
                            paths.add(path.toRealPath());
                        }
                    }
                }
                var jars = new URL[paths.size()];
                int index = 0;
                for (Path path : paths) {
                    try (var jar = new JarFile(path.toFile())) { jars[index++] = path.toUri().toURL(); }
                }
                loader = new URLClassLoader(jars, parent);
                discovery = loader;
                for (Path path : paths) { System.out.println("Loading plugin JAR | path=" + path); }
            }
            var registry = new PluginRegistry(ServiceLoader.load(CommandPlugin.class, discovery), loader);
            if (registry.loadedPlugins.isEmpty()) { throw new IllegalArgumentException("No plugins discovered"); }
            return registry;
        } catch (IOException | RuntimeException | ServiceConfigurationError | LinkageError e) {
            if (loader != null) {
                try { loader.close(); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            }
            throw new IOException("Cannot load plugins: " + e.getMessage(), e);
        }
    }

    public Set<String> instructions() { return java.util.Collections.unmodifiableSet(new TreeSet<>(plugins.keySet())); }

    /** Each registry belongs to one server instance; failed hooks are never retried automatically. */
    public synchronized void start(PluginContext context) throws IOException {
        if (closed || startupAttempted) { throw new IOException("Plugin startup has already run or registry is closed"); }
        java.util.Objects.requireNonNull(context, "context");
        startupAttempted = true;
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        for (var plugin : loadedPlugins) {
            try {
                thread.setContextClassLoader(plugin.getClass().getClassLoader());
                plugin.start(context);
            } catch (Exception | LinkageError e) {
                throw new IOException("Cannot start plugin " + plugin.getClass().getName() + ": " + e.getMessage(), e);
            } finally {
                thread.setContextClassLoader(previous);
            }
        }
    }

    public CommandReply dispatch(CommandRequest request, String serverId, long connectionId) {
        var plugin = plugins.get(request.instruction());
        if (plugin == null) {
            return CommandReply.failure(request.messageId(), serverId, connectionId,
                    UNKNOWN_INSTRUCTION, "Unknown instruction: " + request.instruction());
        }
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(plugin.getClass().getClassLoader());
            Object result = Json.normalizeResult(plugin.execute(request.parameters()));
            return CommandReply.success(request, serverId, connectionId, result);
        } catch (InvalidParametersException e) {
            String message = e.getMessage();
            if (message == null || message.isBlank()) { message = "Invalid parameters for instruction: " + request.instruction(); }
            return CommandReply.failure(request.messageId(), serverId, connectionId, INVALID_PARAMETERS, message);
        } catch (Exception | LinkageError e) {
            return CommandReply.failure(request.messageId(), serverId, connectionId, PLUGIN_ERROR,
                    "Plugin '" + request.instruction() + "' failed: " + e.getClass().getSimpleName());
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Override public synchronized void close() throws IOException {
        closed = true;
        if (loader != null) { loader.close(); }
    }
}
