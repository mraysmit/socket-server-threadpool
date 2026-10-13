# Socket Server with Virtual Threads

A Java-based socket server that handles each client connection on its own virtual thread, with graceful shutdown and administrative monitoring.

## 🚀 Features

- **Virtual Threads**: Handles each client connection on a new virtual thread
- **HTTP Response Support**: Returns proper HTTP responses using SOLID design principles
- **Text Messages**: Sends a UTF-8 text message and receives an echo reply over the main socket
- **Execution Plugins**: Dispatches JSON instructions to built-in or external JAR plugins and returns typed results
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
└── test/java/dev/mars/
    ├── StartStopTest.java
    ├── plugin/
    │   ├── PluginRegistryTest.java
    │   ├── PluginExecutionTest.java
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

Compile and start the interactive application with the server ID `Server-001`:

```powershell
mvn compile exec:java "-Dexec.mainClass=dev.mars.StartStop" "-Dexec.args=Server-001"
```

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
| `--plugins-dir <directory>` | Omitted | Load additional JAR providers from this directory; built-ins are always available |

Ports must be integers from 0 through 65535. Nonzero main and admin ports must
differ. Unknown/duplicate options, missing values, and invalid ports fail before
startup. Use `--help` to display usage.

To let the operating system assign both ports automatically:

```powershell
java -cp "target/classes;target/dependency/*" dev.mars.StartStop Server-003 --port 0 --admin-port 0
```

The app keeps both bound sockets open and reports the actual ports:

```text
Server ready | serverId=Server-003 | main=localhost:52000 | admin=localhost:52001 | instructions=[echo, math.add, text.concat]
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
send {"instruction":"math.add","parameters":{"a":10,"b":20}}
send {"instruction":"text.concat","parameters":{"a":"Hello ","b":"world"}}
send {"instruction":"echo","parameters":{"text":"Hello server"}}
```

The first command prints a reply such as:

```text
Server response: messageId=11111111-1111-4111-8111-111111111111 | serverId=Server-001 | connectionId=1 | status=OK | result=30
```

| Instruction | Required parameters | Result |
|---|---|---|
| `math.add` | Numbers `a`, `b` | Decimal sum |
| `text.concat` | Strings `a`, `b` | Concatenated string |
| `echo` | String `text` | `Received: <text>` |

Built-ins reject missing, extra, or incorrectly typed parameters. Instruction names
are case-sensitive and match `[A-Za-z][A-Za-z0-9._-]*`. Other plugins may accept
any number of named parameters and return JSON numbers, strings, booleans, objects,
arrays, or null. Plain text invokes `echo`; text beginning with `{` is interpreted
as a command object, so use explicit `echo` to send literal text beginning with `{`.
Use JSON escapes such as `\n` within strings; each request occupies one wire line.

Java callers can obtain generic results directly:

```java
CommandReply reply = client.execute("math.add", Map.of("a", 10, "b", 20));
System.out.println(reply.result()); // 30
// execute(UUID, instruction, parameters) accepts a caller-supplied correlation ID.
```

JSON integers arrive as `BigInteger`, decimal numbers as `BigDecimal`, objects as
maps, and arrays as lists. `send(String)` remains a convenience method returning
the existing `MessageReply` text record. The standalone client also accepts a JSON
command as its message argument; preserve embedded double quotes through your shell.

For a raw TCP client, send the complete versioned envelope on the main port:

```json
{"version":1,"messageId":"11111111-1111-4111-8111-111111111111","instruction":"math.add","parameters":{"a":10,"b":20}}
```

Example replies (the server also includes null `error` or `result` fields):

```json
{"version":1,"messageId":"11111111-1111-4111-8111-111111111111","serverId":"Server-001","connectionId":1,"status":"OK","result":30}
{"version":1,"messageId":"11111111-1111-4111-8111-111111111111","serverId":"Server-001","connectionId":2,"status":"ERROR","error":{"code":"UNKNOWN_INSTRUCTION","message":"Unknown instruction: missing"}}
```

Error codes are `INVALID_REQUEST`, `UNKNOWN_INSTRUCTION`, `INVALID_PARAMETERS`,
and `PLUGIN_ERROR`. A valid parsed request UUID is retained on errors; an unreadable
request may have `messageId: null`. Java clients throw `CommandExecutionException`
for correlated command errors; `reply()` exposes the error and IDs. Message IDs
provide correlation only, so retries may execute the instruction again.

### Adding an External Plugin

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
The result is `42`. The directory must exist; an empty directory still enables
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
is closed. See [EXEC_PLUGIN.md](EXEC_PLUGIN.md) for the one-page design.

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

To run the plugin and JSON client tests with command-line output:

```powershell
mvn test "-Dtest=PluginRegistryTest,PluginExecutionTest,ExternalPluginTest,TextMessageClientTest" "-Dmaven.test.redirectTestOutputToFile=false"
```

These verify built-in results, parameter validation, request/reply validation,
correlation across two named servers, 100 concurrent commands, real external JAR
compilation/discovery/execution, plugin exceptions, invalid providers, duplicate
instructions, and release of JAR files at shutdown. The terminal includes
`Plugin test PASSED`, `Concurrent plugin test PASSED`, and `External JAR test PASSED`
with instruction names and verified results. Each built-in (`math.add`,
`text.concat`, and `echo`) prints its inputs and result from both `Server-001` and
`Server-002`. The concurrency test sends 100 `math.add` calls, so its request logs
are more numerous. External plugin tests require a full JDK for Java compilation.

To see just the three built-ins with their results from both servers:

```powershell
mvn test "-Dtest=PluginExecutionTest#commandsReturnResultsAndCorrelatedIdsFromEachServer" "-Dmaven.test.redirectTestOutputToFile=false"
```

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
no fixed worker pool size. The listener and shutdown monitor keep their platform
threads. During shutdown, the listener stops accepting clients and waits for
accepted request tasks to finish. Workers use a 10-second socket read timeout.

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
4. Start the listener threads and report the actual main/admin endpoints
5. Display the console command prompt

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

This project is provided as-is for educational purposes.
