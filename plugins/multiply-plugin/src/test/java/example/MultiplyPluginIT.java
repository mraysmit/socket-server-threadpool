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
package example;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.StartStop;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the packaged artifact through a server JVM with no example classes on its classpath. */
@Timeout(25)
class MultiplyPluginIT {
    @TempDir Path additionalDirectory;
    @Test
    void packagedJarLoadsAndReturnsResultThroughServerConsole() throws Exception {
        Path plugin = Path.of(System.getProperty("example.plugin.jar")).toAbsolutePath();
        assertTrue(Files.isRegularFile(plugin), "Run mvn verify to package the plugin before testing");
        Path hostJar = Path.of(StartStop.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (var host = new JarFile(hostJar.toFile())) {
            assertNull(host.getEntry("dev/mars/plugin/builtin/MathAddPlugin.class"));
            assertNull(host.getEntry("dev/mars/plugin/builtin/TextConcatPlugin.class"));
            assertTrue(host.stream().noneMatch(entry -> entry.getName().startsWith("dev/mars/plugin/fixtures/")),
                    "Test plugin classes must not be packaged in the server JAR");
        }
        try (var jar = new JarFile(plugin.toFile())) {
            assertNotNull(jar.getEntry("example/MultiplyPlugin.class"));
            var provider = jar.getEntry("META-INF/services/dev.mars.plugin.CommandPlugin");
            assertNotNull(provider);
            try (var content = jar.getInputStream(provider)) {
                assertEquals("example.MultiplyPlugin", new String(content.readAllBytes(), StandardCharsets.UTF_8).strip());
            }
            assertNull(jar.getEntry("dev/mars/plugin/CommandPlugin.class"), "Server API must not be bundled");
        }

        // Include only the server and its JSON dependencies. The provider must come from --plugins-dir.
        String classpath = List.of(StartStop.class, ObjectMapper.class, JsonFactory.class, JsonProperty.class)
                .stream().map(type -> {
                    try { return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
                    catch (Exception e) { throw new IllegalStateException(e); }
                }).distinct().collect(Collectors.joining(File.pathSeparator));
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        if (!Files.exists(java)) { java = Path.of(System.getProperty("java.home"), "bin", "java"); }
        Process process = new ProcessBuilder(java.toString(), "-cp", classpath, "dev.mars.StartStop",
                "Server-001", "--port", "0", "--admin-port", "0", "--plugins-dir", plugin.getParent().toString(),
                "--plugins-dir", additionalDirectory.toString())
                .redirectErrorStream(true).start();
        try (var reader = Executors.newVirtualThreadPerTaskExecutor()) {
            var output = reader.submit(() -> new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            try {
                try (var input = process.getOutputStream()) {
                    input.write("""
                            send {"instruction":"math.multiply","parameters":{"a":6}}
                            send {"instruction":"math.multiply","parameters":{"a":6,"b":7}}
                            status
                            quit
                            """.getBytes(StandardCharsets.UTF_8));
                }
                assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Server did not exit");
                String actual = output.get(3, TimeUnit.SECONDS);
                System.out.print(actual);
                assertEquals(0, process.exitValue(), actual);
                assertTrue(actual.contains("instructions=[echo, math.multiply]"), actual);
                assertTrue(actual.contains("INVALID_PARAMETERS"), actual);
                assertTrue(actual.contains("serverId=Server-001"), actual);
                assertTrue(actual.contains("status=OK | result=42"), actual);
                assertTrue(actual.contains("Server status: ALIVE"), actual);
                System.out.println("Standalone plugin JAR test PASSED | instruction=math.multiply | result=42 | jar=" + plugin);
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    assertTrue(process.waitFor(5, TimeUnit.SECONDS));
                }
            }
        }
    }
}
