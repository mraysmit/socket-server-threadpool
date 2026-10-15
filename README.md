# Socket Server with Virtual Threads

| Document metadata | Details |
| --- | --- |
| Author | Mark Andrew Ray-Smith, Cityline Ltd |
| Status | Maintained project and usage guide |
| Last updated | 2026-10-13 |
| Applies to | Socket Client Server 1.0-SNAPSHOT |

A Java-based socket server that handles each client connection on its own virtual thread, with graceful shutdown and administrative monitoring.

## 🚀 Features

- **Virtual Threads**: Main/admin listeners, client connections, and plugin startup use virtual threads
- **HTTP Response Support**: Returns proper HTTP responses using SOLID design principles
- **Text Messages**: Sends a UTF-8 text message and receives an echo reply over the main socket
- **Execution Plugins**: Dispatches JSON instructions to built-in or external JAR plugins and returns typed results
- **Startup Discovery**: A built-in startup plugin announces the server's IPv4 addresses and bound ports over UDP and in terminal logs
- **Graceful Shutdown**: Administrative interface for controlled server shutdown
- **Status Monitoring**: Real-time server status checking capabilities
- **Console Management**: Interactive command-line interface for server administration
- **Thread-Safe Operations**: Uses atomic operations for safe concurrent access

## 📋 Project Structure

```
src/
├── main/java/dev/mars/
│   ├── StartStop.java
│   ├── client/
│   │   ├── ClientOptions.java
│   │   ├── TextMessageClient.java
│   │   ├── CommandExecutionException.java
│   │   └── MessageReply.java
│   ├── plugin/                  # CommandPlugin SPI, registry, validation, built-ins
│   ├── protocol/                # JSON request/reply records and validation
│   ├── server/
│   │   ├── ServerOptions.java
│   │   ├── ServerInstance.java
│   │   ├── ThreadPooledServer.java
│   │   └── ShutdownMonitor.java
│   ├── worker/
│   │   └── WorkerRunnable.java
│   └── http/
│       ├── HttpResponse.java
│       ├── HttpResponseBuilder.java
│       └── HttpResponseWriter.java
├── main/resources/META-INF/services/dev.mars.plugin.CommandPlugin
├── test/resources/META-INF/services/dev.mars.plugin.CommandPlugin
└── test/java/dev/mars/
    ├── StartStopTest.java
    ├── plugin/
    │   ├── fixtures/              # Test-only MathAddPlugin and TextConcatPlugin
    │   ├── PluginRegistryTest.java
    │   ├── PluginExecutionTest.java
    │   ├── StartupPluginTest.java
    │   ├── builtin/AddressBroadcastPluginTest.java
    │   └── ExternalPluginTest.java
    ├── client/
    │   ├── ClientOptionsTest.java
    │   └── TextMessageClientTest.java
    ├── server/
    │   ├── ServerOptionsTest.java
    │   ├── ServerInstanceTest.java
    │   ├── ServerLifecycleTest.java
    │   └── ThreadPooledServerTest.java
    └── http/
        ├── HttpResponseTest.java
        ├── HttpResponseBuilderTest.java
        └── HttpResponseWriterTest.java
```

## 🏗️ Architecture Overview

### Core Components

1. **ThreadPooledServer**: The main server that listens for client connections on port 9000
   - Uses a virtual thread per accepted connection
   - Accepts incoming socket connections
   - Delegates client handling to worker threads

2. **WorkerRunnable**: Handles individual client requests
   - Processes each client connection in a separate virtual thread
   - Reads a JSON command, legacy text-message command, or HTTP request headers
   - Dispatches instructions through the plugin registry and returns correlated results
   - Uses HTTP response classes for clean separation of concerns
   - Logs request processing details

3. **HttpResponse**: Data model for HTTP responses
   - Encapsulates status line, headers, and body
   - Provides content length calculation

4. **HttpResponseBuilder**: Builder pattern for creating HTTP responses
   - Fluent API for constructing responses
   - Provides common response factory methods
   - Automatically handles content-length calculation

5. **HttpResponseWriter**: Service for writing HTTP responses
   - Handles the serialization of HTTP responses to output streams
   - Provides convenience methods for common response types

6. **ShutdownMonitor**: Administrative service running on port 9001
   - Provides server status checking
   - Enables graceful server shutdown
   - Accepts administrative commands

7. **StartStop**: Main application launcher
   - Accepts a server ID and optional main/admin port arguments
   - Initializes and starts both servers
   - Provides console-based command interface
   - Manages application lifecycle

8. **TextMessageClient**: Sends a message and validates its reply
   - Generates a message UUID or accepts one supplied by the caller
   - Checks protocol fields, message ID, and optional expected server ID
   - Returns generic JSON results or throws `CommandExecutionException` with the structured error reply
   - Provides a command-line client with configurable host, port, and expected server ID

9. **MessageReply**: Reply record containing the message ID, server ID, connection ID, and text

10. **ServerOptions / ClientOptions**: Parse and validate command-line settings and defaults

11. **ServerInstance**: Binds both listeners before reporting ready and closes all resources on failure or exit

12. **CommandPlugin / PluginRegistry**: Discover providers with `ServiceLoader`, validate unique instruction names, and dispatch through an immutable registry

## 🛠️ Technical Specifications

- **Java Version**: 27
- **Build Tool**: Maven
- **Main Server Port**: 9000
- **Admin Port**: 9001
- **Request Executor**: One virtual thread per connection
- **Listener Executor**: One named virtual thread per main/admin listener task
- **Command Protocol**: One UTF-8 JSON request line and reply line per connection, version 1
- **JSON Library**: Jackson Databind 2.21.5
- **HTTP Response Format**: HTTP/1.1 status, headers, and UTF-8 body
- **Test Framework**: JUnit Jupiter 6.0.2 with Maven Surefire 3.5.4

## 🚦 Getting Started

### Prerequisites

- Java 27 or higher
- Maven (verified with 3.9.16)

Run the commands below from the project root, where `pom.xml` is located.
Check that both Java and Maven use JDK 27 or higher:

```powershell
java -version
mvn -version
```

If Maven reports an older Java version, set `JAVA_HOME` to your JDK 27 installation
and make sure its `bin` directory is on `PATH` before opening a new terminal.
The first Maven build requires internet access to download dependencies and plugins.

### Building the Project

```bash
mvn clean compile
```

### Running the Server

From the project root in PowerShell, compile and start the interactive application
with the server ID `Server-001`:

```powershell
mvn compile exec:java "-Dexec.mainClass=dev.mars.StartStop" "-Dexec.args=Server-001"
```

This starts the main server on **9000** and the admin listener on **9001**.
Keep this terminal open to use the interactive console.

To choose a different port pair:

```powershell
mvn compile exec:java "-Dexec.mainClass=dev.mars.StartStop" "-Dexec.args=Server-001 --port 9010 --admin-port 9011"
```

Once the server reports `Server ready`, enter these commands at its console
prompt, one at a time:

```text
send {"instruction":"echo","parameters":{"text":"Hello server"}}
status
quit
```

The echo reply includes `status=OK | result="Received: Hello server"`.

`status` checks the running server, and `quit` stops the server and exits the
application. `echo` is built in, so no external JAR is required. `math.add` and
`text.concat` are test-only fixtures and are unavailable in a normal server launch.

The server ID is the first startup argument, or can be passed with `--server-id`.
To use a generated UUID default,
omit that argument:

```powershell
mvn compile exec:java "-Dexec.mainClass=dev.mars.StartStop"
```

The ID may contain ASCII letters, digits, dots, underscores, and hyphens. Leading and
trailing whitespace is removed. An omitted, empty, or whitespace-only ID defaults
to a generated UUID. The chosen ID appears in the startup log, request logs, and
text-message replies. Choose a distinct ID for each running server.

In PowerShell, keep the entire `-Dexec.mainClass=...` argument in quotes.
Quoting only the class name can cause an `Unknown lifecycle phase` error.

Alternatively, compile and copy runtime dependencies with Maven, then launch directly
with Java. The JSON library must be on the runtime classpath:

```powershell
mvn compile dependency:copy-dependencies "-DincludeScope=runtime"
java -cp "target/classes;target/dependency/*" dev.mars.StartStop Server-001
```

To use a generated UUID default when launching directly:

```powershell
java -cp "target/classes;target/dependency/*" dev.mars.StartStop
```

Keep the terminal open for console commands. Omitted port options default to
**9000** for HTTP/text messages and **9001** for administrative commands.
Each option defaults independently; supplying `--port` alone keeps admin port 9001.

### Running Multiple Servers

After the compile and dependency-copy command above, start each instance in its
own terminal with distinct port pairs. On Linux/macOS, use `:` instead of `;` in
the Java classpath:

```powershell
java -cp "target/classes;target/dependency/*" dev.mars.StartStop Server-001 --port 9000 --admin-port 9001
java -cp "target/classes;target/dependency/*" dev.mars.StartStop Server-002 --port 9010 --admin-port 9011
```

The equivalent Maven command for the second instance is:

```powershell
mvn compile exec:java "-Dexec.mainClass=dev.mars.StartStop" "-Dexec.args=Server-002 --port 9010 --admin-port 9011"
```

| Server option | Default | Meaning |
|---|---|---|
| `[serverId]` or `--server-id <id>` | Generated UUID | ID reported in logs and replies |
| `--port <number>` | `9000` | HTTP/text-message listening port |
| `--admin-port <number>` | `9001` | Administrative listening port |
| `--plugins-dir <directory>` | Omitted | Repeat to supply a list of runtime plugin/dependency JAR folders; built-ins are always available |

Ports must be integers from 0 through 65535. Nonzero main and admin ports must
differ. Unknown options, duplicate options other than `--plugins-dir`, missing values, and invalid ports fail before
startup. Use `--help` to display usage.

To let the operating system assign both ports automatically:

```powershell
java -cp "target/classes;target/dependency/*" dev.mars.StartStop Server-003 --port 0 --admin-port 0
```

The app keeps both bound sockets open and reports the actual ports:

```text
Server ready | serverId=Server-003 | main=localhost:52000 | admin=localhost:52001 | instructions=[echo]
```

Port numbers in this example are illustrative. The console automatically uses its
own instance's actual ports and expected server ID. External clients must use the
main port from the ready message.

### When a Port Is Already in Use

Startup binds both listeners before reporting ready. If either requested port is
occupied or unavailable, the process exits unsuccessfully with the port's role and
number, for example `Admin port 9011 is already in use or unavailable`.
Any listener opened by that attempt is closed; existing server instances keep running.
Choose another port pair or use `0` for automatic allocation. Fixed ports are never
silently replaced with other ports.

## 💻 Usage

### Starting the Server

When you run the application, it will:

1. Validate arguments, discover plugins, and bind both configured listening ports
2. Start the main server and shutdown monitor and print the actual endpoints
3. Display a console prompt routed to this instance's ports

### Console Commands

The application provides an interactive console with the following commands:

- `send <text>` - Send a text message to this instance's main port and display the verified reply
- `send <JSON command>` - Execute an instruction with named parameters and display its result
- `status` - Check if the server is running
- `shutdown` - Stop the main server and shutdown monitor
- `quit` - Close both listeners, wait for accepted work, and exit

Command keywords are case-insensitive. The configured admin socket (default 9001) accepts
`status` and `shutdown`; `send` and `quit` belong to the application console.

Enter `quit` to stop and exit cleanly. You can also enter `shutdown` followed by
`quit`. End-of-input closes the instance as well.

### Sending and Receiving Text Messages

Start the app, then enter a message at its console prompt:

```text
> send Hello server
Sending: messageId=11111111-1111-4111-8111-111111111111 | text=Hello server
Server response: messageId=11111111-1111-4111-8111-111111111111 | serverId=Server-001 | connectionId=1 | status=OK | result="Received: Hello server"
```

The client generates a UUID for each message and checks that the reply contains
the same ID. Replies also include the server instance's ID and connection ID.
Missing or malformed fields and mismatched message or expected server IDs cause
an error instead of being displayed as successful replies. Results are validated
as JSON; their contents belong to the selected plugin.
Each server keeps its selected ID for its lifetime. A provided ID can be reused
after restart; an automatically generated UUID changes on each startup.

The server logs the message ID, server ID, connection ID, and client address.
Messages support UTF-8, including non-English text. Each message is one nonblank
line. Message IDs correlate requests and replies; the server processes each request
independently and does not deduplicate repeated IDs.
The Java client has a 5-second connection timeout and a 10-second reply-read timeout.

To send from a separate terminal while the app is running:

```powershell
mvn compile exec:java "-Dexec.mainClass=dev.mars.client.TextMessageClient" "-Dexec.args=Hello server"
```

Or use the compiled client directly:

```powershell
java -cp "target/classes;target/dependency/*" dev.mars.client.TextMessageClient "Hello server"
```

To target a different instance and require its server ID:

```powershell
java -cp "target/classes;target/dependency/*" dev.mars.client.TextMessageClient --host localhost --port 9010 --expected-server-id Server-002 "Hello server"
```

With Maven:

```powershell
mvn compile exec:java "-Dexec.mainClass=dev.mars.client.TextMessageClient" "-Dexec.args=--host localhost --port 9010 --expected-server-id Server-002 Hello server"
```

Client defaults are host `localhost`, port `9000`, and no expected server ID.
Client ports must be from 1 through 65535; use an actual listening port, not `0`.
An expected server ID is checked exactly, including case. A mismatched ID fails
the request. Use `--` before message text that starts with an option such as `--port`.
Java callers can use `new TextMessageClient(host, port, expectedServerId)`.

The legacy text protocol is still supported for existing TCP clients. With telnet
or another TCP client, connect to the instance's main port (default **9000**) and send one UTF-8 line
in this format, using a new message UUID for each message:

```text
message <messageId> <text>
```

The successful reply format is:

```text
reply <messageId> <serverId> <connectionId> Received: <text>
```

For example, send `message 11111111-1111-4111-8111-111111111111 Hello server`
followed by Enter. The reply echoes that message ID. A rejected message with a
valid ID returns `error <messageId> <serverId> <connectionId> <reason>`.
Open a new connection for each message. The `message` keyword is case-insensitive;
message text is preserved. A missing or invalid message UUID receives an `Error: ...`
line. Use the client and server built from the same version of this project.
Administrative commands on the configured admin port remain `status` and `shutdown`.

### Executing Plugin Commands

At the application console, enter:

```text
send {"instruction":"echo","parameters":{"text":"Hello server"}}
```

The command prints a reply such as:

```text
Server response: messageId=11111111-1111-4111-8111-111111111111 | serverId=Server-001 | connectionId=1 | status=OK | result="Received: Hello server"
```

| Instruction | Required parameters | Result |
|---|---|---|
| `echo` | String `text` | `Received: <text>` |

The production command built-in is `echo`; the address broadcaster is startup-only.
`MathAddPlugin` (`math.add`) and `TextConcatPlugin` (`text.concat`) live under
`src/test/java/dev/mars/plugin/fixtures/` and are registered only in
`src/test/resources/META-INF/services/dev.mars.plugin.CommandPlugin`. Maven makes
them available during server tests, and excludes their classes and registration
from the production JAR and normal Java/Maven launches. Use the external
`math.multiply` example below for a command plugin that can be loaded at runtime.

The echo plugin rejects missing, extra, or incorrectly typed parameters. Instruction names
are case-sensitive and match `[A-Za-z][A-Za-z0-9._-]*`. Other plugins may accept
any number of named parameters and return JSON numbers, strings, booleans, objects,
arrays, or null. Plain text invokes `echo`; text beginning with `{` is interpreted
as a command object, so use explicit `echo` to send literal text beginning with `{`.
Use JSON escapes such as `\n` within strings; each request occupies one wire line.

Java callers can obtain generic results directly:

```java
CommandReply reply = client.execute("echo", Map.of("text", "Hello server"));
System.out.println(reply.result()); // Received: Hello server
// execute(UUID, instruction, parameters) accepts a caller-supplied correlation ID.
```

JSON integers arrive as `BigInteger`, decimal numbers as `BigDecimal`, objects as
maps, and arrays as lists. `send(String)` remains a convenience method returning
the existing `MessageReply` text record. The standalone client also accepts a JSON
command as its message argument; preserve embedded double quotes through your shell.

For a raw TCP client, send the complete versioned envelope on the main port:

```json
{"version":1,"messageId":"11111111-1111-4111-8111-111111111111","instruction":"echo","parameters":{"text":"Hello server"}}
```

Example replies (the server also includes null `error` or `result` fields):

```json
{"version":1,"messageId":"11111111-1111-4111-8111-111111111111","serverId":"Server-001","connectionId":1,"status":"OK","result":"Received: Hello server"}
{"version":1,"messageId":"11111111-1111-4111-8111-111111111111","serverId":"Server-001","connectionId":2,"status":"ERROR","error":{"code":"UNKNOWN_INSTRUCTION","message":"Unknown instruction: missing"}}
```

Error codes are `INVALID_REQUEST`, `UNKNOWN_INSTRUCTION`, `INVALID_PARAMETERS`,
and `PLUGIN_ERROR`. A valid parsed request UUID is retained on errors; an unreadable
request may have `messageId: null`. Java clients throw `CommandExecutionException`
for correlated command errors; `reply()` exposes the error and IDs. Message IDs
provide correlation only, so retries may execute the instruction again.

### Adding an External Plugin

#### Build and Run the Standalone Example

A complete plugin project is included at [`plugins/multiply-plugin`](plugins/multiply-plugin).
From the repository root, build and install the server API locally, then package
and test the example:

```powershell
mvn clean install
mvn -f plugins/multiply-plugin/pom.xml clean verify
```

The reusable JAR is:

```text
plugins/multiply-plugin/target/multiply-plugin-1.0-SNAPSHOT.jar
```

It stays on disk after the tests finish. Start a server that loads it directly
from the example's output directory:

```powershell
mvn compile exec:java "-Dexec.mainClass=dev.mars.StartStop" "-Dexec.args=Server-001 --plugins-dir plugins/multiply-plugin/target"
```

The ready log includes `math.multiply`. At the server console, enter:

```text
send {"instruction":"math.multiply","parameters":{"a":6,"b":7}}
```

The reply includes `status=OK | result=42`. Enter `quit` to stop the server.
You can also copy this JAR to your own plugin directory and select that directory
with `--plugins-dir`. No additional dependency JARs are needed for this example;
the server API dependency has Maven scope `provided` and is supplied by the host.
The example JAR contains its provider class and service registration, without
bundling the server classes.

The example's `mvn verify` checks the packaged plugin JAR, then uses
separate server JVMs whose classpaths exclude the example classes. It verifies
that `math.multiply` is unavailable without a runtime plugin folder and loads
from either of two supplied folders, including paths with spaces. Real TCP
requests check integer, decimal, large-number, negative, and zero results;
invalid parameters return correlated errors, and valid commands still work
afterward. Replies are checked for message ID, server ID, status, and connection
ID. Verified results print `Standalone plugin JAR test PASSED`. Reports are under
`plugins/multiply-plugin/target/failsafe-reports/`. Root `mvn test` runs the server
tests; use the separate example command above to test this standalone artifact.
Reinstall the server after API changes and rebuild the example after changes to
its source. The example's `clean` removes its previous JAR before rebuilding it.

The separate [`plugins/gossip-plugin`](plugins/gossip-plugin/pom.xml) project provides
the gossip provider, validated membership model, `exchange`, paginated `members`,
diagnostic `status`, and asynchronous `leave`, with bounded peer transport. Jittered rounds automatically
contact seeds, publish heartbeats, maintain membership, and retry unavailable peers.
Departure publishes terminal `LEFT` and reports its bounded attempts through status.
Shutdown cancels owned DNS/TCP work and joins runtime workers. See the
[gossip plugin document](docs/GOSSIP_PLUGIN.md) for configuration, build/run commands,
packaged-JAR tests, and the implementation checklist.

At membership capacity, gossip still applies updates and departures for known
members and returns a successful exchange reply; new records that cannot fit are
skipped. Invalid records and identity conflicts still reject the entire batch.
Healthy seeds participate in ordinary fanout, and verified hostname/IP aliases
share selection and retry state. A delayed DNS lookup cannot dial a destination
twice within the same round.

Run the capacity and seed-selection regression tests with terminal output:

```powershell
mvn -f plugins/gossip-plugin/pom.xml test "-Dtest=MembershipTableTest,GossipCapacityTest,PeerSelectorTest,GossipRuntimeTest,GossipCommandsTest,GossipTransportTest" "-Dmaven.test.redirectTestOutputToFile=false"
```

The socket capacity cases print `Capacity test PASSED` after verifying the results.

Gossip also writes one-line diagnostics to standard error for identity conflicts,
scheduler failures, cleanup failures, and final departure outcomes. Entries include
server ID, boot ID, swarm ID, and relevant peer/attempt details. Repeated identity
and scheduler warnings are limited to one entry per category per 30 seconds per
instance; the next entry reports the suppressed count. See
[operational logging](docs/GOSSIP_PLUGIN.md#operational-logging) for the format.

Run the logging and lifecycle regression tests with terminal output:

```powershell
mvn -f plugins/gossip-plugin/pom.xml test "-Dtest=GossipLogTest,GossipCommandsTest,GossipTransportTest,GossipRuntimeTest,GossipDepartureTest" "-Dmaven.test.redirectTestOutputToFile=false"
```

#### Loading Plugins from Runtime Folders

Supply the folder list at launch by repeating `--plugins-dir`, once per folder.
For example, after compiling and copying the host's runtime dependencies, launch
directly with Java in PowerShell:

```powershell
mvn compile dependency:copy-dependencies "-DincludeScope=runtime"
java -cp "target/classes;target/dependency/*" dev.mars.StartStop Server-001 --plugins-dir "C:\server plugins\math" --plugins-dir "C:\server plugins\text"
```

Replace those two folders with existing directories containing your plugin JARs
and their dependency JARs. Paths with spaces must be quoted. The directories are
application arguments after the main class; no individual plugin JAR needs to be
added to the Java classpath or to the server's Maven dependencies.

The equivalent Maven invocation uses the same repeated application option:

```powershell
mvn compile exec:java "-Dexec.mainClass=dev.mars.StartStop" "-Dexec.args=Server-001 --plugins-dir C:/server-plugins/math --plugins-dir C:/server-plugins/text"
```

The server scans immediate `.jar` files in every supplied directory at runtime,
in the order folders are supplied and with JARs sorted within each folder.
It creates one class loader for the complete list, so a plugin in one folder can
use dependency JARs from another. `ServiceLoader` discovers implementations using
their existing registration file. Terminal output includes
`Loading plugin JAR | path=<absolute-path>` for each JAR.

Repeated or equivalent directory/JAR paths are loaded once. Subdirectories are
not scanned recursively: for the included project, supply
`plugins/multiply-plugin/target`, where the built JAR resides. Every supplied
directory must exist; a bad folder, corrupt archive, invalid provider, or duplicate
instruction across folders fails startup before listeners open. An empty folder
is allowed. Omitting the options loads built-in and application-classpath providers.

Plugin discovery happens once during server startup. Change the command-line list
or replace a JAR and restart the server to use the new selection. Runtime loading
does not provide hot reload. The loader is closed after accepted work finishes.

Java callers can supply the same list through
`new ServerOptions("Server-001", 0, 0, List.of(firstFolder, secondFolder))`;
`pluginDirectories()` exposes an immutable snapshot. The single-`Path` constructor
remains available for existing callers.

To test multiple folders, including shared dependencies and cleanup:

```powershell
mvn test "-Dtest=ExternalPluginTest,ServerOptionsTest,StartStopTest" "-Dmaven.test.redirectTestOutputToFile=false"
```

#### Plugin Service Registrations

Each Maven project owns its service registration files and packages them into
its own JAR:

| Source file | Packaged in | Registered providers |
|---|---|---|
| `src/main/resources/META-INF/services/dev.mars.plugin.CommandPlugin` | Server JAR | `EchoPlugin` and startup-only `AddressBroadcastPlugin` |
| `src/test/resources/META-INF/services/dev.mars.plugin.CommandPlugin` | Test classpath only; excluded from the server JAR | `MathAddPlugin` and `TextConcatPlugin` from `dev.mars.plugin.fixtures` |
| `plugins/multiply-plugin/src/main/resources/META-INF/services/dev.mars.plugin.CommandPlugin` | Example plugin JAR | `example.MultiplyPlugin` (`math.multiply`) |

Inside each JAR, the registration has the same path:
`META-INF/services/dev.mars.plugin.CommandPlugin`. These files remain separate
resources in separate JARs. `ServiceLoader` discovers both and combines their
provider lists at startup.

During `mvn test`, the test registration also supplies the math and text fixtures.
That file remains under `target/test-classes` and is not included in the server JAR.

All plugins share the `CommandPlugin` interface and this single service-file
name. The optional `start(PluginContext)` method defaults to doing nothing.
Startup-only plugins such as the address broadcaster use the same registration
file and leave `instruction()` at its default `null`, so they add no command.

With `--plugins-dir plugins/multiply-plugin/target`, the server scans that
directory for JARs and creates a class loader for them. `ServiceLoader` discovers
the built-ins through the parent application class loader and the external
provider through the plugin loader. The registry creates an instruction-to-plugin
map using each provider's `instruction()` value, including `math.multiply`.
Requests execute the selected plugin on the connection's virtual thread.

Every external plugin JAR supplies its own service registration; adding one does
not require editing the server's registration file. Duplicate instruction names
fail startup. Discovery runs once per startup, so restart the server after adding
or replacing a plugin JAR.

#### Create Your Own Plugin

Implement the public SPI in a separate Java 27 project using this project's built
JAR as a compile-time dependency. For example:

```java
package example;

import dev.mars.plugin.CommandPlugin;
import dev.mars.plugin.InvalidParametersException;
import dev.mars.plugin.PluginParameters;
import java.util.Map;

public final class MultiplyPlugin implements CommandPlugin {
    public String instruction() { return "math.multiply"; }

    public Object execute(Map<String, Object> parameters) throws InvalidParametersException {
        PluginParameters.requireKeys(parameters, "a", "b");
        return PluginParameters.number(parameters, "a")
                .multiply(PluginParameters.number(parameters, "b"));
    }
}
```

In that plugin project, add
`src/main/resources/META-INF/services/dev.mars.plugin.CommandPlugin` containing:

```text
example.MultiplyPlugin
```

Package the plugin normally, create a `plugins` directory, and copy its JAR there
along with any dependency JARs it needs. Do not bundle copies of the server SPI
classes. Start the server with:

```powershell
mvn compile exec:java "-Dexec.mainClass=dev.mars.StartStop" "-Dexec.args=Server-001 --plugins-dir plugins"
```

Then enter `send {"instruction":"math.multiply","parameters":{"a":6,"b":7}}`.
The result is `42`. Repeat `--plugins-dir` to load more runtime folders. Every
directory must exist; an empty directory still enables
built-ins. Providers already on the application classpath are also discovered.
Invalid JAR archives/providers and duplicate or invalid instruction names fail
startup before listeners open. The ready log lists all registered instructions.

Plugins load once per startup. Instances are shared across concurrent virtual
threads and must be thread-safe. Each invocation runs with the plugin's context
class loader; returned values are normalized to JSON before replying. Plugins
validate their own parameters and throw `InvalidParametersException` for caller
errors; other exceptions become `PLUGIN_ERROR`. Plugins are trusted code inside
the server process. There is no sandbox, hot reload, or per-command execution
deadline. The class loader closes after accepted work completes when the instance
is closed. See [EXEC_PLUGIN.md](docs/EXEC_PLUGIN.md) for the one-page design.

#### Optional Plugin Startup

All plugins implement the same `dev.mars.plugin.CommandPlugin` interface.
Startup behavior is optional: override the default no-op `start(PluginContext)`
method when initialization is needed. There is one interface and one service
registration file per JAR:

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

`PluginContext` supplies `serverId()`, `port()`, and `adminPort()` from the running
instance. Both TCP ports are already bound and nonzero, including when startup
options requested automatic allocation with `0`.

A command plugin opts into startup behavior by overriding `start()`:

```java
public final class MultiplyPlugin implements CommandPlugin {
    @Override
    public void start(PluginContext context) throws Exception {
        // Optional load-time function, such as loading configuration or a cache.
        System.out.println("Plugin starting for " + context.serverId() + ":" + context.port());
    }

    // Implement the usual instruction() and execute(parameters) methods.
}
```

Register every provider, including startup-only providers, in
`META-INF/services/dev.mars.plugin.CommandPlugin`. The registry creates each
provider once and calls `start()` on that same instance. Existing command plugins
can keep their `instruction()` and `execute()` methods and inherit the no-op
startup method. A startup-only plugin overrides `start()` and inherits the default
`instruction()` value of `null`; it does not need to implement `execute()`.
Non-null instruction names must be valid and unique.

`ServerInstance` calls each plugin's startup method once after binding both listeners and before
starting request processing or reporting ready. Discovery, construction, and
instruction validation remain sequential. Initialization runs concurrently using
one virtual thread per provider, with that provider's context class loader.
Startup waits for every hook to finish. Providers must initialize independently;
there is no guaranteed hook execution or completion order.

Hook failures are collected, and startup waits for the other hooks to finish before
stopping every provider whose startup was entered (including failing providers),
releasing both listeners, and closing the external plugin loader. A shutdown or
registry close during initialization waits for startup tasks to settle. Within one
server instance, `start()` and `stop()` never overlap. Interrupting the startup caller cancels its startup
tasks, waits for actual worker exit, restores the caller's interrupt flag, and
fails startup. Hooks must cooperate with cancellation or finish independently;
there is no host-enforced initialization deadline. Command
plugins using the default no-op startup method load and handle requests normally and
require no changes. Requests continue to execute on virtual threads.

#### Optional Plugin Shutdown

Override the default no-op `stop()` when a plugin owns background tasks, sockets,
or other resources. The server invokes it at most once per entered provider,
in reverse recorded startup-entry order, with that provider's context class loader. A partially
initialized provider is included in cleanup; providers whose startup was never
entered are not stopped. Constructors should avoid allocating resources.

`ServerInstance.requestStop()` quiesces plugins before closing the admin and main
listeners. Administrative `shutdown` uses this path without joining its own
monitor task. `ServerInstance.close()` also requests a stop, then joins listeners
and drains accepted requests before closing the plugin class loader. Console
`quit` and end-of-input reach `close()`. After administrative shutdown, `quit` or
owner close completes the joins and loader cleanup.

A stop hook must prevent new background work, close sockets that unblock its
workers, and join those workers before returning. Keep state needed by already
accepted command handlers until they finish. Do not rely on daemon threads,
interrupts alone, or a JVM shutdown hook for per-instance resource cleanup, and
do not make synchronous departure calls to unreachable peers from `stop()`.
Stop hooks run synchronously; the host supplies no forced timeout for plugin code.

If a stop hook throws an exception or a nonfatal error, the registry still attempts
the remaining hooks and aggregates failures using suppressed exceptions. Listener
and loader cleanup still runs. Owner close reports the failure; admin shutdown
logs it and owner close reports it after completing cleanup. Repeated stops do
not retry hooks. Registry close supplies a fallback stop path for direct users.

Run the lifecycle and real external-JAR cleanup tests with:

```powershell
mvn test "-Dtest=ConcurrentPluginStartupTest,PluginLifecycleTest,ExternalPluginTest,ServerLifecycleTest,StartStopTest" "-Dmaven.test.redirectTestOutputToFile=false"
```

#### Built-in Address Broadcast

`AddressBroadcastPlugin` is a startup-only built-in, loaded automatically without
an external JAR or a `send` command. It sends one UTF-8 JSON UDP datagram per
distinct active IPv4 broadcast-capable interface to that subnet's broadcast
address on **UDP port 9002**, and prints the same advertised endpoint in the
terminal. This is a discovery destination port, independent of the server's main
and admin TCP ports. Multiple servers can all announce to UDP port 9002.

Start an instance normally, or with automatic TCP ports:

```powershell
mvn compile exec:java "-Dexec.mainClass=dev.mars.StartStop" "-Dexec.args=Server-001 --port 0 --admin-port 0"
```

Example terminal entry (addresses and allocated ports vary by machine):

```text
Server announcement sent | serverId=Server-001 | ip=192.168.1.10 | port=52000 | adminPort=52001 | udp=192.168.1.255:9002
```

The UDP payload is:

```json
{"version":1,"type":"SERVER_STARTED","serverId":"Server-001","ip":"192.168.1.10","port":52000,"adminPort":52001}
```

A discovery client listening on UDP port 9002 can use `ip` and `port` for the
main connection; `adminPort` identifies the administrative endpoint. On machines
with multiple active interfaces, each announcement contains that interface's
address. Loopback and IPv6 interfaces are excluded. There is one announcement per
interface at startup, with no periodic heartbeat or retry; listeners must already
be running to receive it. UDP delivery depends on the local network and firewall.

Announcements are best effort: an unavailable interface or failed UDP send is
logged and the TCP server continues starting. The plugin closes each UDP socket
after sending. It does not appear in the ready log's command instruction list
because it has no command handler.

To run the startup-hook and broadcaster tests with terminal output:

```powershell
mvn test "-Dtest=StartupPluginTest,AddressBroadcastPluginTest,ExternalPluginTest" "-Dmaven.test.redirectTestOutputToFile=false"
```

The broadcaster test receives a real UDP datagram using a controlled loopback
destination, checks the server ID and resolved ports, and prints
`Address broadcast test PASSED`. Other cases cover multiple interfaces, send
failures, missing interfaces, opt-in initialization, and failed-startup cleanup.

### Testing the Server

#### Automated Tests

Run all JUnit tests, including the socket tests:

```powershell
mvn test
```

For a clean build, tests, and JAR packaging:

```powershell
mvn clean verify
```

To run a single test class:

```powershell
mvn test "-Dtest=HttpResponseWriterTest"
mvn test "-Dtest=TextMessageClientTest"
mvn test "-Dtest=ThreadPooledServerTest"
mvn test "-Dtest=ServerLifecycleTest"
```

#### Plugin Tests

Run only the plugin tests with command-line output:

```powershell
mvn test "-Dtest=PluginRegistryTest,PluginExecutionTest,StartupPluginTest,ConcurrentPluginStartupTest,PluginLifecycleTest,AddressBroadcastPluginTest,ExternalPluginTest,MathAddPluginTest,TextConcatPluginTest,EchoPluginTest" "-Dmaven.test.redirectTestOutputToFile=false"
```

To include the JSON client's reply-validation tests as well:

```powershell
mvn test "-Dtest=PluginRegistryTest,PluginExecutionTest,StartupPluginTest,ConcurrentPluginStartupTest,PluginLifecycleTest,AddressBroadcastPluginTest,ExternalPluginTest,MathAddPluginTest,TextConcatPluginTest,EchoPluginTest,TextMessageClientTest" "-Dmaven.test.redirectTestOutputToFile=false"
```

These verify built-in and fixture results, parameter validation, request/reply validation,
correlation across two named servers, 100 concurrent commands, real external JAR
compilation/discovery/execution, plugin exceptions, invalid providers, duplicate
instructions, and release of JAR files at shutdown. The terminal includes
`Plugin test PASSED`, `Concurrent plugin test PASSED`, and `External JAR test PASSED`
with instruction names and verified results. The test fixtures (`math.add`,
`text.concat`) and production `echo` plugin print their inputs and result from both `Server-001` and
`Server-002`. The concurrency test sends 100 `math.add` calls, so its request logs
are more numerous. External plugin tests require a full JDK for Java compilation.

The registry and startup unit tests use explicitly supplied providers; each
plugin's arithmetic, text, and parameter rules are tested in its own test class.
The CLI registration test supplies its own provider JAR and checks discovery with
and without that JAR's service entry. These tests do not prescribe which other
plugins a server may load. Regression cases also cover malformed/empty/commented service descriptors,
duplicate provider entries, constructor and initializer failures, invalid provider
types, missing runtime dependencies during discovery/startup/execution, recovery
after replacing a bad JAR, uppercase JAR extensions, ignored files and nested
folders, concurrent startup attempts, independent plugin state across server
instances, context-class-loader restoration, and usable parameter-error replies.
The standalone plugin integration test also checks that the packaged host JAR
contains neither test fixture classes nor the old math/text built-in classes.

To see the two test fixtures and `echo` with their results from both servers:

```powershell
mvn test "-Dtest=PluginExecutionTest#commandsReturnResultsAndCorrelatedIdsFromEachServer" "-Dmaven.test.redirectTestOutputToFile=false"
```

This runs three parameterized cases, one per command under test. Each case checks both
servers, producing six `Plugin test PASSED` entries. Abridged examples:

```text
Plugin test PASSED | instruction=math.add | ... | serverId=Server-001 | ... | result=30
Plugin test PASSED | instruction=text.concat | ... | serverId=Server-001 | ... | result="你好\nworld 🌍"
Plugin test PASSED | instruction=echo | ... | serverId=Server-001 | ... | result="Received: Hello plugin"
```

The actual entries also include inputs, message ID, connection ID, and status.
The tests start their own servers on automatically allocated ports and close them
afterward; you do not need to start the app first. Registry unit tests call plugins
directly, so they do not produce socket request logs.

#### External Plugin JAR Test

Run only the external plugin tests:

```powershell
mvn test "-Dtest=ExternalPluginTest" "-Dmaven.test.redirectTestOutputToFile=false"
```

To run just the successful external-JAR execution and shutdown-cleanup test:

```powershell
mvn test "-Dtest=ExternalPluginTest#externalJarExecutesOnVirtualThreadAndIsReleasedAtShutdown" "-Dmaven.test.redirectTestOutputToFile=false"
```

This test compiles a separate `example.ExamplePlugin` class, packages it into a
real JAR with a `META-INF/services/dev.mars.plugin.CommandPlugin` provider entry,
and starts `Server-001` with that temporary plugin directory. It sends the
equivalent of:

```text
send {"instruction":"external.multiply","parameters":{"a":6,"b":7}}
```

The plugin verifies that it runs on a virtual thread with its own context class
loader. The client checks the returned object, and the terminal prints an entry
like this (field order may vary):

```text
External JAR test PASSED | instruction=external.multiply | messageId=<uuid> | serverId=Server-001 | connectionId=<id> | status=OK | result={"product":42,"plugin":"external"}
```

After shutdown, the test deletes the JAR to check that the loader released it.
The full `ExternalPluginTest` class also covers plugin exceptions, null results,
duplicate instructions, invalid names, missing providers, corrupt JARs, and
missing or empty plugin directories.

The test creates and removes its own plugin files and server; `external.multiply`
is available only in that test instance. For an external plugin you can run
manually, follow [Adding an External Plugin](#adding-an-external-plugin) to package
and load the standalone `math.multiply` example. Its JAR is retained under
`plugins/multiply-plugin/target/`; the test-generated `external.multiply` JAR
described above is still temporary.

#### Startup and Lifecycle Tests

To test startup options, simultaneous instances, occupied ports, cleanup, and
the actual command-line applications:

```powershell
mvn test "-Dtest=ServerOptionsTest,ClientOptionsTest,ServerInstanceTest,StartStopTest" "-Dmaven.test.redirectTestOutputToFile=false"
```

These tests cover omitted port defaults, explicit/automatic/mixed ports, invalid
arguments, main/admin/cross-role conflicts, rollback after partial binding, six
simultaneous instances, a race for one port pair, expected-server-ID validation,
idle admin connection cleanup, and console `quit`, `shutdown`, and end-of-input.

To run the test that starts `Server-001` and verifies its ID in a text-message reply:

```powershell
mvn test "-Dtest=ServerLifecycleTest#configuredServerIdAppearsInMessageReply" "-Dmaven.test.redirectTestOutputToFile=false"
```

The terminal displays startup and message logs with `serverId=Server-001`, followed
by `Named server test PASSED` and the verified reply. The two-server test uses
`Server-001` and `Server-002` and checks that each reply identifies the correct server.

To run only the two-server test with terminal output:

```powershell
mvn test "-Dtest=ServerLifecycleTest#twoServersIdentifyWhichInstanceRepliedToTheMessage" "-Dmaven.test.redirectTestOutputToFile=false"
```

#### 1,000-Request Load Test

Run this command from the project root to execute only the load test:

```powershell
mvn test "-Dtest=ServerLifecycleTest#handlesOneThousandRequests" "-Dmaven.test.redirectTestOutputToFile=false"
```

This test sends 1,000 HTTP GET requests from virtual-thread clients, with at most
100 requests in flight. Every response must have status 200, the expected body,
and a matching content length. All requests must finish within 30 seconds, and
the server must still answer administrative status checks afterward. The test
runs as part of `mvn test` too. It starts its own server on a temporary port;
it does not send requests to an application you already have running on port 9000.

The terminal displays request logs, progress every 100 verified responses, and
a final summary such as `Load test PASSED: 1,000 responses verified in ... ms; server is ALIVE`.
Maven reports `Tests run: 1` because this one test method sends all 1,000 requests.

To save the request logs and progress to a file instead, use:

```powershell
mvn test "-Dtest=ServerLifecycleTest#handlesOneThousandRequests" "-Dmaven.test.redirectTestOutputToFile=true"
```

With redirection enabled, test output is in
`target/surefire-reports/dev.mars.server.ServerLifecycleTest-output.txt`.

Tests cover HTTP response construction, UTF-8 content lengths, wire formatting,
output failures, concurrent socket clients, UTF-8 text-message round trips,
message ID matching, malformed/mismatched replies, distinct server instances,
empty-message validation, and administrative status/shutdown commands.
Socket tests start their own servers on temporary ports and clean up afterward;
you do not need to start the application first. Reports are in `target/surefire-reports/`.
Maven prints a test summary and `BUILD SUCCESS` when the suite passes. If a test
fails, check the summary and the corresponding `.txt` or `.xml` report in that directory.
`mvn clean verify` also creates `target/socket-client-server-1.0-SNAPSHOT.jar`.
This is a regular JAR; direct Java execution still requires the runtime dependency
classpath described above. A clean build removes `target/dependency`, so rerun
`mvn dependency:copy-dependencies "-DincludeScope=runtime"` before direct launches.

#### Test Client Connections

Start the application first, then run these commands in a second terminal.
The examples below use the default ports; replace 9000/9001 with your instance's
actual main/admin ports when configured differently.

**Using curl:**

```bash
curl http://localhost:9000
```

On Windows PowerShell, use `curl.exe http://localhost:9000` to invoke curl directly.

**Using telnet:**
```bash
telnet localhost 9000
```

After connecting, enter `GET / HTTP/1.1`, then `Host: localhost`, then an empty
line to finish the request headers.

**Using netcat:**
```bash
printf 'GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' | nc localhost 9000
```

#### Administrative Commands

**Check server status:**
```bash
echo "status" | nc localhost 9001
```

**Shutdown server:**
```bash
echo "shutdown" | nc localhost 9001
```

## 📊 Server Response

HTTP GET requests return a demonstration response in the following format.
The connection closes after the response:

```
HTTP/1.1 200 OK
Content-Type: text/plain; charset=UTF-8
Content-Length: [length]

WorkerRunnable: ThreadPooledServer - [timestamp]
```

Here `[timestamp]` is Unix epoch milliseconds. Console log timestamps are formatted
as a readable date and time. `Content-Length` counts the UTF-8 body bytes.

## 🔧 Configuration

### Customizing Server Settings

Use the startup arguments described above to configure the server ID and ports.
Java callers can start and close a complete instance with:

```java
try (var instance = ServerInstance.start(new ServerOptions("Server-001", 0, 0))) {
    var client = new TextMessageClient("localhost", instance.getPort(), instance.getServerId());
    MessageReply reply = client.send("Hello server");
    System.out.println(reply);
}
```

`getPort()` and `getAdminPort()` return the actual bound ports, including when
automatic allocation was requested. Closing the instance releases both listeners
and waits for accepted tasks. The console uses these same resolved settings.

### Virtual Thread Execution

The request executor uses `Executors.newVirtualThreadPerTaskExecutor()`; there is
no fixed worker pool size. Main and admin listeners each run on a named virtual
thread through a thread-per-task executor. Plugin startup also uses virtual
threads. During shutdown, the listener stops accepting clients and waits for
accepted request tasks to finish. Workers use a 10-second socket read timeout.

[Virtual threads are daemon threads](https://docs.oracle.com/en/java/javase/27/docs/api/java.base/java/lang/Thread.html),
so an embedding application's main thread must remain alive while its servers
are needed. The interactive server and swarm entry points do this while reading
console commands and complete cleanup before returning. The swarm's JVM
shutdown hook also runs on a virtual thread.

## 🏗️ SOLID Design Principles

This project demonstrates SOLID design principles in action:

### Single Responsibility Principle (SRP)

- **HttpResponse**: Only handles HTTP response data
- **HttpResponseBuilder**: Only responsible for building HTTP responses
- **HttpResponseWriter**: Only handles writing responses to output streams
- **WorkerRunnable**: Focuses on client request processing, delegates response creation

### Open/Closed Principle (OCP)

- Easy to extend with new response types without modifying existing classes
- HttpResponseBuilder provides factory methods for common responses
- New response writers can be added without changing existing code

### Dependency Inversion Principle (DIP)

- WorkerRunnable can accept HttpResponseWriter via constructor injection
- Classes depend on abstractions rather than concrete implementations

## 🛡️ Thread Safety

The implementation uses several thread-safety mechanisms:

- `AtomicBoolean` for server state management
- `synchronized` methods for critical sections
- Thread-safe `ExecutorService` for task management
- Proper resource cleanup with try-with-resources

## 📝 Logging

The server provides comprehensive logging:

- Client connection notifications
- Request processing timestamps
- Thread identification for debugging
- Error handling with stack traces
- Server lifecycle events

Connection and request logs include a readable timestamp, connection ID, client
IP address and source port, server name and instance ID, message ID when available,
thread ID, and virtual-thread status:

```text
[2026-10-13 14:30:00.123 +08:00] Client connected | connectionId=1 | client=127.0.0.1:54321 | server=ThreadPooledServer | serverId=Server-001 | messageId=- | thread=42 | virtual=true
[2026-10-13 14:30:00.124 +08:00] Executing instruction: echo | connectionId=1 | client=127.0.0.1:54321 | server=ThreadPooledServer | serverId=Server-001 | messageId=11111111-1111-4111-8111-111111111111 | thread=42 | virtual=true
```

The same connection ID appears in both entries and is unique within the running
JVM. The source port distinguishes clients sharing an IP address. `messageId=-`
means a text-message ID has not been read, or the connection carries HTTP traffic.
Timestamps use the JVM's local time zone.

## 🔄 Lifecycle Management

### Server Startup

1. Resolve the supplied server ID or generate a UUID default
2. Discover and validate plugins, then initialize the virtual thread executor
3. Bind both listeners, releasing both if any bind fails
4. Run optional startup hooks with the resolved endpoints, including the address announcement
5. Start the listener threads and report the actual main/admin endpoints
6. Display the console command prompt

### Request Processing

1. Accept client connection
2. Start a virtual thread for the connection
3. Read a JSON command, legacy text command, or HTTP request headers
4. Dispatch the instruction and return a correlated result/error, or an HTTP response
5. Close client socket

### Server Shutdown

1. Acknowledge `shutdown` and close the administrative listener
2. Mark the main server stopped and close its listening socket
3. Close the request executor, waiting for accepted tasks to finish
4. Enter `quit` to leave the console command loop and allow the application to exit

Using `quit` directly, or closing console input, also closes the whole instance
and waits for its listener and worker executors to terminate.
Closing the instance also releases the external plugin class loader after accepted
work finishes. An admin `shutdown` stops listeners; enter `quit` to close the console
and finish instance cleanup.

## 🤝 Contributing

This project serves as an educational example of socket programming and virtual threads in Java. Feel free to extend it with additional features such as:

- SSL/TLS support
- Request routing
- Session management
- Performance metrics
- Configuration files

## 📄 License

Copyright 2026 Mark Andrew Ray-Smith

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details
and [NOTICE](NOTICE) for attribution.
