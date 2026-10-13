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

    private PluginRegistry(Iterable<CommandPlugin> providers, URLClassLoader loader) {
        var selected = new LinkedHashMap<String, CommandPlugin>();
        for (var provider : providers) {
            String instruction = provider.instruction();
            if (instruction == null || !instruction.matches("[A-Za-z][A-Za-z0-9._-]*")) {
                throw new IllegalArgumentException("Invalid plugin instruction: " + instruction);
            }
            if (selected.putIfAbsent(instruction, provider) != null) {
                throw new IllegalArgumentException("Duplicate plugin instruction: " + instruction);
            }
        }
        plugins = Map.copyOf(selected);
        this.loader = loader;
    }

    public static PluginRegistry of(Collection<CommandPlugin> providers) {
        return new PluginRegistry(providers, null);
    }

    public static PluginRegistry load(Path directory) throws IOException {
        URLClassLoader loader = null;
        try {
            ClassLoader parent = CommandPlugin.class.getClassLoader();
            ClassLoader discovery = parent;
            if (directory != null) {
                if (!Files.isDirectory(directory)) { throw new IOException("Plugin directory does not exist: " + directory); }
                URL[] jars;
                try (var files = Files.list(directory)) {
                    var paths = files.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                            .sorted().toList();
                    jars = new URL[paths.size()];
                    for (int i = 0; i < paths.size(); i++) {
                        try (var jar = new JarFile(paths.get(i).toFile())) {
                            jars[i] = paths.get(i).toUri().toURL();
                        }
                    }
                }
                loader = new URLClassLoader(jars, parent);
                discovery = loader;
            }
            var registry = new PluginRegistry(ServiceLoader.load(CommandPlugin.class, discovery), loader);
            if (registry.plugins.isEmpty()) { throw new IllegalArgumentException("No command plugins discovered"); }
            return registry;
        } catch (IOException | RuntimeException | ServiceConfigurationError e) {
            if (loader != null) {
                try { loader.close(); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            }
            throw new IOException("Cannot load plugins: " + e.getMessage(), e);
        }
    }

    public Set<String> instructions() { return java.util.Collections.unmodifiableSet(new TreeSet<>(plugins.keySet())); }

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
            return CommandReply.failure(request.messageId(), serverId, connectionId, INVALID_PARAMETERS, e.getMessage());
        } catch (Exception e) {
            return CommandReply.failure(request.messageId(), serverId, connectionId, PLUGIN_ERROR,
                    "Plugin '" + request.instruction() + "' failed: " + e.getClass().getSimpleName());
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Override public void close() throws IOException { if (loader != null) { loader.close(); } }
}
