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

import dev.mars.client.CommandExecutionException;
import dev.mars.client.TextMessageClient;
import dev.mars.server.ServerInstance;
import dev.mars.server.ServerOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static dev.mars.protocol.CommandError.Code.PLUGIN_ERROR;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class ExternalPluginTest {
    @TempDir Path temporary;

    @Test
    void externalJarExecutesOnVirtualThreadAndIsReleasedAtShutdown() throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("plugins"));
        Path jar = providerJar(directory, "external.multiply", """
                if (!Thread.currentThread().isVirtual()) throw new Exception("Not a virtual thread");
                if (Thread.currentThread().getContextClassLoader() != getClass().getClassLoader())
                    throw new Exception("Wrong context loader");
                var a = new java.math.BigDecimal(parameters.get("a").toString());
                var b = new java.math.BigDecimal(parameters.get("b").toString());
                return Map.of("product", a.multiply(b), "plugin", "external");
                """);
        try (var server = ServerInstance.start(new ServerOptions("Server-001", 0, 0, directory))) {
            var client = new TextMessageClient("localhost", server.getPort(), "Server-001");
            var result = client.execute("external.multiply", Map.of("a", 6, "b", 7));
            assertEquals(Map.of("product", BigInteger.valueOf(42), "plugin", "external"), result.result());
            assertEquals("Received: healthy", client.send("healthy").text());
            System.out.println("External JAR test PASSED | instruction=external.multiply | " + result);
        }
        Files.delete(jar); // Windows prevents deletion if the loader still owns an open JAR handle.
        assertFalse(Files.exists(jar));
    }

    @Test
    void pluginExceptionsAreStructuredAndDoNotStopOtherCommands() throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("plugins"));
        providerJar(directory, "external.fail", "throw new IllegalStateException(\"private detail\");");
        try (var server = ServerInstance.start(new ServerOptions("Server-001", 0, 0, directory))) {
            var client = new TextMessageClient("localhost", server.getPort(), "Server-001");
            var failure = assertThrows(CommandExecutionException.class,
                    () -> client.execute("external.fail", Map.of()));
            assertEquals(PLUGIN_ERROR, failure.reply().error().code());
            assertFalse(failure.getMessage().contains("private detail"));
            assertEquals("Received: alive", client.send("alive").text());
        }
    }

    @Test
    void externalPluginCanReturnNull() throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("plugins"));
        providerJar(directory, "external.null", "return null;");
        try (var server = ServerInstance.start(new ServerOptions("Server-001", 0, 0, directory))) {
            var reply = new TextMessageClient("localhost", server.getPort()).execute("external.null", Map.of());
            assertEquals("OK", reply.status());
            assertNull(reply.result());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate", "invalid-name", "missing-provider", "corrupt-jar"})
    void invalidPluginsFailStartupAndReleaseJar(String scenario) throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("plugins"));
        Path jar;
        if (scenario.equals("corrupt-jar")) {
            jar = Files.writeString(directory.resolve("broken.jar"), "not a jar");
        } else if (scenario.equals("missing-provider")) {
            jar = directory.resolve("missing.jar");
            try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
                output.putNextEntry(new JarEntry("META-INF/services/dev.mars.plugin.CommandPlugin"));
                output.write("missing.Provider\n".getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        } else {
            jar = providerJar(directory, scenario.equals("duplicate") ? "echo" : "bad name", "return 42;");
        }
        var error = assertThrows(IOException.class,
                () -> ServerInstance.start(new ServerOptions("Server-001", 0, 0, directory)));
        assertTrue(error.getMessage().contains("Cannot load plugins"), error.getMessage());
        Files.delete(jar);
        assertFalse(Files.exists(jar));
    }

    @Test
    void missingDirectoryFailsAndEmptyDirectoryKeepsBuiltins() throws Exception {
        assertThrows(IOException.class,
                () -> ServerInstance.start(new ServerOptions("Missing", 0, 0, temporary.resolve("missing"))));
        try (var server = ServerInstance.start(new ServerOptions("Empty", 0, 0, temporary))) {
            assertEquals("Received: hello", new TextMessageClient("localhost", server.getPort()).send("hello").text());
        }
    }

    @Test
    void externalCommandOptsIntoStartupAndReceivesResolvedPorts() throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("plugins"));
        Path jar = compileJar(directory, """
                package example;
                import dev.mars.plugin.*;
                import java.util.Map;
                public class ExamplePlugin implements CommandPlugin {
                    private static int instances;
                    private PluginContext context;
                    public ExamplePlugin() {
                        if (++instances != 1) throw new IllegalStateException("Constructed twice");
                    }
                    public String instruction() { return "external.startup"; }
                    public void start(PluginContext context) {
                        if (this.context != null) throw new IllegalStateException("Started twice");
                        this.context = context;
                    }
                    public Object execute(Map<String,Object> parameters) {
                        return Map.of("serverId", context.serverId(), "port", context.port(), "adminPort", context.adminPort());
                    }
                }
                """, "dev.mars.plugin.CommandPlugin");
        try (var first = ServerInstance.start(new ServerOptions("Server-001", 0, 0, directory));
             var second = ServerInstance.start(new ServerOptions("Server-002", 0, 0, directory))) {
            for (var server : List.of(first, second)) {
                var reply = new TextMessageClient("localhost", server.getPort(), server.getServerId())
                        .execute("external.startup", Map.of());
                assertEquals(Map.of("serverId", server.getServerId(), "port", BigInteger.valueOf(server.getPort()),
                        "adminPort", BigInteger.valueOf(server.getAdminPort())), reply.result());
            }
            first.close();
            var reply = new TextMessageClient("localhost", second.getPort(), "Server-002")
                    .execute("external.startup", Map.of());
            assertEquals("Server-002", ((Map<?, ?>) reply.result()).get("serverId"));
        }
        Files.delete(jar);
    }

    @Test
    void failingStartupOnlyExternalPluginReleasesBothBoundPortsAndJar() throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("plugins"));
        Path jar = compileJar(directory, """
                package example;
                import dev.mars.plugin.*;
                public class ExamplePlugin implements CommandPlugin {
                    public void start(PluginContext context) throws Exception {
                        try (var a = new java.net.ServerSocket(context.port())) {
                            throw new IllegalStateException("Main port was not bound");
                        } catch (java.net.BindException expected) { }
                        try (var b = new java.net.ServerSocket(context.adminPort())) {
                            throw new IllegalStateException("Admin port was not bound");
                        } catch (java.net.BindException expected) { }
                        throw new java.io.IOException("Startup failed after binding both ports");
                    }
                }
                """, "dev.mars.plugin.CommandPlugin");
        int main;
        int admin;
        try (var a = new java.net.ServerSocket(0); var b = new java.net.ServerSocket(0)) {
            main = a.getLocalPort(); admin = b.getLocalPort();
        }
        var error = assertThrows(IOException.class,
                () -> ServerInstance.start(new ServerOptions("Server-001", main, admin, directory)));
        assertTrue(error.getMessage().contains("Startup failed after binding both ports"), error.getMessage());
        try (var a = new java.net.ServerSocket(main); var b = new java.net.ServerSocket(admin)) {
            assertEquals(main, a.getLocalPort());
            assertEquals(admin, b.getLocalPort());
        }
        Files.delete(jar);
    }

    private Path providerJar(Path directory, String instruction, String body) throws Exception {
        return compileJar(directory, """
                package example;
                import dev.mars.plugin.CommandPlugin;
                import java.util.Map;
                public class ExamplePlugin implements CommandPlugin {
                    public String instruction() { return "%s"; }
                    public Object execute(Map<String, Object> parameters) throws Exception {
                        %s
                    }
                }
                """.formatted(instruction, body), "dev.mars.plugin.CommandPlugin");
    }

    @Test
    void loadsPluginsAndSharedDependenciesFromMultipleRuntimeDirectories() throws Exception {
        Path first = Files.createDirectory(temporary.resolve("first plugins"));
        Path second = Files.createDirectory(temporary.resolve("second plugins"));
        Path library = compileJar(second, """
                package example;
                public class ExampleLibrary {
                    public static int answer() { return 42; }
                }
                """);
        Path alpha = compileJar(first, """
                package example;
                import dev.mars.plugin.CommandPlugin;
                import java.util.Map;
                public class ExamplePlugin implements CommandPlugin {
                    public String instruction() { return "external.alpha"; }
                    public Object execute(Map<String,Object> parameters) { return ExampleLibrary.answer(); }
                }
                """, List.of(library), "dev.mars.plugin.CommandPlugin");
        Path beta = compileJar(second, """
                package example;
                import dev.mars.plugin.CommandPlugin;
                import java.util.Map;
                public class OtherPlugin implements CommandPlugin {
                    public String instruction() { return "external.beta"; }
                    public Object execute(Map<String,Object> parameters) { return "second folder"; }
                }
                """, "dev.mars.plugin.CommandPlugin");
        try (var server = ServerInstance.start(new ServerOptions("Server-001", 0, 0, List.of(first, second)))) {
            var client = new TextMessageClient("localhost", server.getPort(), "Server-001");
            assertEquals(BigInteger.valueOf(42), client.execute("external.alpha", Map.of()).result());
            assertEquals("second folder", client.execute("external.beta", Map.of()).result());
            System.out.println("Multiple plugin folders test PASSED | external.alpha=42 | external.beta=second folder");
        }
        for (Path jar : List.of(alpha, beta, library)) { Files.delete(jar); }
    }

    @Test
    void rejectsDuplicateInstructionsAcrossFoldersAndReleasesBothJars() throws Exception {
        Path first = Files.createDirectory(temporary.resolve("first"));
        Path second = Files.createDirectory(temporary.resolve("second"));
        Path alpha = providerJar(first, "external.same", "return 1;");
        Path beta = compileJar(second, """
                package example;
                import dev.mars.plugin.CommandPlugin;
                import java.util.Map;
                public class OtherPlugin implements CommandPlugin {
                    public String instruction() { return "external.same"; }
                    public Object execute(Map<String,Object> parameters) { return 2; }
                }
                """, "dev.mars.plugin.CommandPlugin");
        var error = assertThrows(IOException.class,
                () -> ServerInstance.start(new ServerOptions("Server-001", 0, 0, List.of(first, second))));
        assertTrue(error.getMessage().contains("Duplicate plugin instruction: external.same"), error.getMessage());
        Files.delete(alpha); Files.delete(beta);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "file", "corrupt"})
    void invalidLaterFolderFailsStartupAndKeepsEarlierJarReleased(String scenario) throws Exception {
        Path first = Files.createDirectory(temporary.resolve("valid"));
        Path jar = providerJar(first, "external.valid", "return 1;");
        Path invalid = temporary.resolve("invalid");
        switch (scenario) {
            case "file" -> Files.writeString(invalid, "not a directory");
            case "corrupt" -> Files.writeString(Files.createDirectory(invalid).resolve("broken.jar"), "not a jar");
            default -> { }
        }
        assertThrows(IOException.class,
                () -> ServerInstance.start(new ServerOptions("Server-001", 0, 0, List.of(first, invalid))));
        Files.delete(jar);
    }

    @Test
    void repeatedAndEquivalentDirectoryPathsLoadProviderOnce() throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("plugins"));
        Path jar = providerJar(directory, "external.once", "return 1;");
        try (var registry = PluginRegistry.loadDirectories(List.of(directory, directory.resolve("."), directory))) {
            assertTrue(registry.instructions().contains("external.once"));
        }
        Files.delete(jar);
    }

    private Path compileJar(Path directory, String content, String... services) throws Exception {
        return compileJar(directory, content, List.of(), services);
    }

    @ParameterizedTest
    @ValueSource(strings = {"constructor", "initializer", "private-constructor", "wrong-type", "abstract", "instruction"})
    void invalidProviderLifecycleFailsCleanlyAndAllowsJarReplacement(String scenario) throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("plugins"));
        String declaration = scenario.equals("abstract") ? "public abstract class" : "public class";
        String contract = scenario.equals("wrong-type") ? "" : "implements dev.mars.plugin.CommandPlugin";
        String extra = switch (scenario) {
            case "constructor" -> "public ExamplePlugin() { throw new IllegalStateException(\"Constructor failed\"); }";
            case "initializer" -> "static { if (true) throw new IllegalStateException(\"Initializer failed\"); }";
            case "private-constructor" -> "private ExamplePlugin() { }";
            default -> "";
        };
        String instruction = scenario.equals("instruction")
                ? "throw new IllegalStateException(\"Instruction failed\");" : "return \"external.invalid\";";
        Path jar = compileJar(directory, """
                package example;
                %s ExamplePlugin %s {
                    %s
                    public String instruction() { %s }
                }
                """.formatted(declaration, contract, extra, instruction), "dev.mars.plugin.CommandPlugin");
        assertThrows(IOException.class, () -> ServerInstance.start(new ServerOptions("Rejected", 0, 0, directory)));
        Files.delete(jar);
        Path corrected = providerJar(directory, "external.recovered", "return \"recovered\";");
        try (var server = ServerInstance.start(new ServerOptions("Recovered", 0, 0, directory))) {
            assertEquals("recovered", new TextMessageClient("localhost", server.getPort(), "Recovered")
                    .execute("external.recovered", Map.of()).result());
        }
        Files.delete(corrected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"instruction", "startup", "execute"})
    void missingRuntimeDependenciesReturnControlledFailuresAndReleaseResources(String phase) throws Exception {
        Path compileOnly = Files.createDirectory(temporary.resolve("compile-only"));
        Path directory = Files.createDirectory(temporary.resolve("runtime"));
        Path library = compileJar(compileOnly, """
                package example;
                public class MissingLibrary {
                    public static String name() { return "external.missing"; }
                    public static int value() { return 42; }
                }
                """);
        Path jar = compileJar(directory, """
                package example;
                import dev.mars.plugin.*;
                import java.util.Map;
                public class ExamplePlugin implements CommandPlugin {
                    public String instruction() { %s }
                    public void start(PluginContext context) { %s }
                    public Object execute(Map<String,Object> parameters) { %s }
                }
                """.formatted(phase.equals("instruction") ? "return MissingLibrary.name();" : "return \"external.missing\";",
                phase.equals("startup") ? "MissingLibrary.value();" : "",
                phase.equals("execute") ? "return MissingLibrary.value();" : "return 1;"),
                List.of(library), "dev.mars.plugin.CommandPlugin");
        int main;
        int admin;
        try (var a = new java.net.ServerSocket(0); var b = new java.net.ServerSocket(0)) {
            main = a.getLocalPort(); admin = b.getLocalPort();
        }
        var options = new ServerOptions("Server-001", main, admin, directory);
        if (phase.equals("execute")) {
            try (var server = ServerInstance.start(options)) {
                var client = new TextMessageClient("localhost", server.getPort(), "Server-001");
                var failure = assertThrows(CommandExecutionException.class, () -> client.execute("external.missing", Map.of()));
                assertEquals(PLUGIN_ERROR, failure.reply().error().code());
                assertTrue(failure.reply().error().message().contains("NoClassDefFoundError"));
                assertEquals("Received: healthy", client.send("healthy").text());
            }
        } else {
            var failure = assertThrows(IOException.class, () -> ServerInstance.start(options));
            assertInstanceOf(NoClassDefFoundError.class, failure.getCause());
        }
        try (var a = new java.net.ServerSocket(main); var b = new java.net.ServerSocket(admin)) {
            assertEquals(main, a.getLocalPort()); assertEquals(admin, b.getLocalPort());
        }
        Files.delete(jar);
    }

    @Test
    void ignoresNonJarFilesAndNestedJarsButAcceptsUppercaseJarExtension() throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("plugins"));
        Files.writeString(directory.resolve("notes.txt"), "Not an archive");
        Files.createDirectory(directory.resolve("ignored.jar"));
        Path nested = Files.createDirectory(directory.resolve("nested"));
        Path nestedJar = providerJar(nested, "external.nested", "return 1;");
        Path top = providerJar(directory, "external.top", "return 2;");
        Path uppercase = Files.move(top, directory.resolve("PLUGIN.JAR"));
        try (var registry = PluginRegistry.load(directory)) {
            assertTrue(registry.instructions().contains("external.top"));
            assertFalse(registry.instructions().contains("external.nested"));
        }
        Files.delete(uppercase); Files.delete(nestedJar);
    }

    @ParameterizedTest
    @ValueSource(strings = {"comments-and-duplicates", "empty", "invalid-class-name"})
    void handlesServiceDescriptorEdgeCases(String scenario) throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("plugins"));
        Path jar = compileJar(directory, """
                package example;
                import dev.mars.plugin.CommandPlugin;
                public class ExamplePlugin implements CommandPlugin {
                    private static int instances;
                    public ExamplePlugin() { if (++instances != 1) throw new IllegalStateException("Constructed twice"); }
                    public String instruction() { return "external.descriptor"; }
                }
                """, "dev.mars.plugin.CommandPlugin");
        String descriptor = switch (scenario) {
            case "comments-and-duplicates" -> "# Providers\n example.ExamplePlugin # Plugin\n\nexample.ExamplePlugin\n";
            case "empty" -> "# No external providers\n\n";
            default -> "invalid provider name\n";
        };
        rewriteDescriptor(jar, descriptor);
        if (scenario.equals("invalid-class-name")) {
            assertThrows(IOException.class, () -> PluginRegistry.load(directory));
        } else {
            try (var registry = PluginRegistry.load(directory)) {
                assertEquals(!scenario.equals("empty"), registry.instructions().contains("external.descriptor"));
            }
        }
        Files.delete(jar);
    }

    private void rewriteDescriptor(Path jar, String descriptor) throws IOException {
        var entries = new java.util.LinkedHashMap<String, byte[]>();
        try (var input = new java.util.jar.JarFile(jar.toFile())) {
            for (var entry : input.stream().toList()) {
                try (var content = input.getInputStream(entry)) { entries.put(entry.getName(), content.readAllBytes()); }
            }
        }
        entries.put("META-INF/services/dev.mars.plugin.CommandPlugin", descriptor.getBytes(StandardCharsets.UTF_8));
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (var entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey())); output.write(entry.getValue()); output.closeEntry();
            }
        }
    }

    private Path compileJar(Path directory, String content, List<Path> dependencies, String... services) throws Exception {
        var match = java.util.regex.Pattern.compile("public (?:abstract )?class ([A-Za-z0-9_]+)").matcher(content);
        assertTrue(match.find(), "Fixture must declare a public class");
        String name = match.group(1);
        Path source = temporary.resolve(name + ".java");
        Files.writeString(source, content);
        Path classes = Files.createTempDirectory(temporary, "compiled-");
        Path api = Path.of(CommandPlugin.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String classpath = api + dependencies.stream().map(path -> java.io.File.pathSeparator + path)
                .collect(java.util.stream.Collectors.joining());
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "External plugin tests require a JDK");
        assertEquals(0, compiler.run(null, null, null, "-encoding", "UTF-8", "-classpath", classpath,
                "-d", classes.toString(), source.toString()));
        Path jar = directory.resolve(name + ".jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("example/" + name + ".class"));
            Files.copy(classes.resolve("example/" + name + ".class"), output);
            output.closeEntry();
            for (String service : services) {
                output.putNextEntry(new JarEntry("META-INF/services/" + service));
                output.write(("example." + name + "\n").getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return jar;
    }
}
