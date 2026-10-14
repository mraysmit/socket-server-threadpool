# Execution Plugin Model

| Document metadata | Details |
| --- | --- |
| Author | Mark Andrew Ray-Smith, Cityline Ltd |
| Status | Implemented plugin architecture and design |
| Last updated | 2026-10-14 |
| Applies to | Socket Client Server 1.0-SNAPSHOT |

See [README.md](../README.md) for startup and test commands.

Make the server domain-independent by moving instruction handling into Java plugins.
The server owns sockets, message correlation, dispatch, and reply serialization;
each plugin owns its parameter validation and business logic.

The console accepts a structured command, for example:

```text
send {"instruction":"echo","parameters":{"text":"Hello server"}}
```

The client adds a message UUID and protocol version. The command protocol
uses one UTF-8 JSON request line and one JSON reply line per connection on the main
port. A successful reply contains:

```json
{
  "version": 1,
  "messageId": "<original-request-uuid>",
  "serverId": "Server-001",
  "connectionId": 42,
  "status": "OK",
  "result": "Received: Hello server"
}
```

Named parameters support two inputs today and other shapes as plugins require.
Results may be JSON numbers, strings, booleans, objects, arrays, or null.

A minimal plugin contract is:

```java
public interface CommandPlugin {
    default void start(PluginContext context) throws Exception { }
    default void stop() throws Exception { }
    default String instruction() { return null; }
    default Object execute(Map<String, Object> parameters) throws Exception {
        throw new UnsupportedOperationException("This plugin has no command handler");
    }
}
```

Returned values must be JSON-serializable. Plugins are trusted Java code running
inside the server process.

- **Discovery:** Built-in and classpath providers load by default. Add JARs with
  repeated `--plugins-dir <directory>` arguments; load JARs from all supplied
  folders at startup through one shared class loader and
  discover implementations with Java's [ServiceLoader](https://docs.oracle.com/en/java/javase/27/docs/api/java.base/java/util/ServiceLoader.html).
  Each JAR declares providers in `META-INF/services/dev.mars.plugin.CommandPlugin`.
  Dependency JARs can reside in any supplied folder. Only immediate JAR files are
  scanned; equivalent paths are deduplicated and every directory must exist.
- **Registry:** Build an immutable instruction-to-plugin map before reporting ready.
  Duplicate instruction names or invalid providers fail startup with resource cleanup.
- **Execution:** Dispatch on the existing per-connection virtual thread. Shared
  plugin instances must be thread-safe; begin with stateless implementations.
- **Optional startup:** `CommandPlugin.start(PluginContext)` defaults to no-op and runs once after both
  listeners bind and before requests are accepted. Independent providers initialize
  concurrently on virtual threads with their own context class loaders. Discovery
  and validation remain sequential; the server waits for all hooks before reporting
  ready or rolling back failures. Startup and shutdown hooks never overlap. The context supplies the server
  ID and actual main/admin ports. All providers share the same interface and
  `CommandPlugin` service file. Startup-only providers inherit a null instruction;
  command handlers override `instruction()` and `execute()`. Existing command
  plugins require no changes. Hook failures abort startup with listener cleanup.
- **Optional shutdown:** `stop()` defaults to no-op. Stop entered providers once
  in reverse recorded startup-entry order, including a provider whose startup failed. Quiesce
  background work before closing listeners; retain handler state while accepted
  requests drain, then release the loader. Administrative shutdown requests this
  stop without joining its own task; instance close completes joins and cleanup.
  Continue cleanup after hook exceptions and aggregate failures. Hooks must close
  their sockets and join their own workers; the host cannot force a hook deadline.
- **Errors:** Return `status: "ERROR"` with an error code and message, using
  `INVALID_REQUEST`, `UNKNOWN_INSTRUCTION`, `INVALID_PARAMETERS`, or `PLUGIN_ERROR`.
  Preserve the request ID whenever valid, plus server and connection IDs. A plugin
  failure must leave other connections operational.
- **Client:** Validate the message ID and optional expected server ID, then return
  the result or structured error, without requiring an echoed-text match.

The production command built-in is `echo` (string `text`, returning
`Received: <text>`). `math.add` and `text.concat` are test-only providers in
`src/test/java/dev/mars/plugin/fixtures`, with registration under test resources;
neither is packaged in the server JAR. Plain `send <text>` invokes
`echo`; the legacy text wire format remains supported. HTTP and administrative
commands keep their existing roles. Plugins load once per startup, and their
class loader closes after accepted work finishes during shutdown.
Message IDs provide correlation; duplicate-request suppression is a separate feature.

The built-in startup-only `AddressBroadcastPlugin` sends the server ID, interface
IPv4 address, and actual TCP ports as JSON to each active subnet's UDP broadcast
address on port 9002, and logs the endpoint. It runs once per interface and closes
its UDP sockets. Announcement failures are logged without preventing TCP startup.

Tests cover external-JAR discovery, duplicate instructions, numeric and text
results, Unicode, invalid parameters, unknown instructions, malformed requests,
plugin exceptions, reply-ID mismatches, concurrent calls, and multiple server instances.
Use the existing Maven/JUnit workflow: `mvn test` and `mvn clean verify`.
