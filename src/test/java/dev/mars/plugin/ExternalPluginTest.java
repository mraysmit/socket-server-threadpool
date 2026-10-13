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
            assertEquals(BigInteger.valueOf(30), client.execute("math.add", Map.of("a", 10, "b", 20)).result());
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
            jar = providerJar(directory, scenario.equals("duplicate") ? "math.add" : "bad name", "return 42;");
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
        try (var server = ServerInstance.start(new ServerOptions("Server-001", 0, 0, directory))) {
            var reply = new TextMessageClient("localhost", server.getPort()).execute("external.startup", Map.of());
            assertEquals(Map.of("serverId", "Server-001", "port", BigInteger.valueOf(server.getPort()),
                    "adminPort", BigInteger.valueOf(server.getAdminPort())), reply.result());
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

    private Path compileJar(Path directory, String content, String... services) throws Exception {
        Path source = temporary.resolve("ExamplePlugin.java");
        Files.writeString(source, content);
        Path classes = Files.createDirectory(temporary.resolve("compiled"));
        Path api = Path.of(CommandPlugin.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "External plugin tests require a JDK");
        assertEquals(0, compiler.run(null, null, null, "-encoding", "UTF-8", "-classpath", api.toString(),
                "-d", classes.toString(), source.toString()));
        Path jar = directory.resolve("example.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("example/ExamplePlugin.class"));
            Files.copy(classes.resolve("example/ExamplePlugin.class"), output);
            output.closeEntry();
            for (String service : services) {
                output.putNextEntry(new JarEntry("META-INF/services/" + service));
                output.write("example.ExamplePlugin\n".getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return jar;
    }
}
