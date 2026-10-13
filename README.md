# Socket Server with Virtual Threads

A Java-based socket server that handles each client connection on its own virtual thread, with graceful shutdown and administrative monitoring.

## 🚀 Features

- **Virtual Threads**: Handles each client connection on a new virtual thread
- **HTTP Response Support**: Returns proper HTTP responses using SOLID design principles
- **Text Messages**: Sends a UTF-8 text message and receives an echo reply over the main socket
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
│   │   ├── TextMessageClient.java
│   │   └── MessageReply.java
│   ├── server/
│   │   ├── ThreadPooledServer.java
│   │   └── ShutdownMonitor.java
│   ├── worker/
│   │   └── WorkerRunnable.java
│   └── http/
│       ├── HttpResponse.java
│       ├── HttpResponseBuilder.java
│       └── HttpResponseWriter.java
└── test/java/dev/mars/
    ├── client/TextMessageClientTest.java
    ├── server/
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
   - Reads either a text-message command or HTTP request headers
   - Echoes text with message, server, and connection IDs
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
   - Accepts an optional server ID as its first startup argument
   - Initializes and starts both servers
   - Provides console-based command interface
   - Manages application lifecycle

8. **TextMessageClient**: Sends a message and validates its reply
   - Generates a message UUID or accepts one supplied by the caller
   - Checks reply IDs and echoed text
   - Provides a command-line client for `localhost:9000`

9. **MessageReply**: Reply record containing the message ID, server ID, connection ID, and text

## 🛠️ Technical Specifications

- **Java Version**: 27
- **Build Tool**: Maven
- **Main Server Port**: 9000
- **Admin Port**: 9001
- **Request Executor**: One virtual thread per connection
- **Text Protocol**: One UTF-8 message and reply per connection
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

The server ID is the first startup argument. To use a generated UUID default,
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

Alternatively, compile with Maven and launch directly with Java:

```powershell
mvn compile
java -cp target/classes dev.mars.StartStop Server-001
```

To use a generated UUID default when launching directly:

```powershell
java -cp target/classes dev.mars.StartStop
```

Keep the terminal open for console commands. The app listens on port **9000** for
HTTP and text-message clients and port **9001** for administrative commands; both ports must be free.
If startup reports `Address already in use`, stop the previous instance or change
the ports in `StartStop.java`.

## 💻 Usage

### Starting the Server

When you run the application, it will:

1. Start the main server on port 9000
2. Start the shutdown monitor on port 9001
3. Display a console prompt for commands

### Console Commands

The application provides an interactive console with the following commands:

- `send <text>` - Send a text message to port 9000 and display the server's reply
- `status` - Check if the server is running
- `shutdown` - Stop the main server and shutdown monitor
- `quit` - Exit the console command loop

Command keywords are case-insensitive. The admin socket on port 9001 accepts
`status` and `shutdown`; `send` and `quit` belong to the application console.

To stop the application cleanly, enter `shutdown`, wait for the server to stop,
then enter `quit`. Using `quit` alone does not request shutdown of either listener.

### Sending and Receiving Text Messages

Start the app, then enter a message at its console prompt:

```text
> send Hello server
Sending: messageId=11111111-1111-4111-8111-111111111111 | text=Hello server
Server response: messageId=11111111-1111-4111-8111-111111111111 | serverId=Server-001 | connectionId=1 | text=Received: Hello server
```

The client generates a UUID for each message and checks that the reply contains
the same ID and expected echoed text. Replies also include the server instance's
ID and connection ID. Missing or malformed IDs, mismatched message IDs, and
incorrect reply text cause an error instead of being displayed as successful replies.
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
java -cp target/classes dev.mars.client.TextMessageClient "Hello server"
```

With telnet or another TCP client, connect to port **9000** and send one UTF-8 line
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
Administrative commands on port 9001 remain `status` and `shutdown`.

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

#### Test Client Connections

Start the application first, then run these commands in a second terminal.

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

You can modify the server configuration by editing the `StartStop.java` file:

```java
// Preserve the startup server ID when changing the listening ports
var threadPooledServer = new ThreadPooledServer(9000, args.length == 0 ? null : args[0]);
var shutdownMonitor = new ShutdownMonitor(9001, threadPooledServer);  // admin port
```

The startup argument sets only the server ID; listening ports are configured in
code. To run multiple app instances on the same host, assign each instance a
distinct pair of listening ports and update the client destinations to match:

- The console's `TextMessageClient` and the standalone client's `main` method
  currently target `localhost:9000`.
- `StartStop.checkServerStatus` currently targets `localhost:9001`.
- Java callers can use `new TextMessageClient(host, port)` to select a message server.

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
[2026-10-13 14:30:00.124 +08:00] Message received: Hello server | connectionId=1 | client=127.0.0.1:54321 | server=ThreadPooledServer | serverId=Server-001 | messageId=11111111-1111-4111-8111-111111111111 | thread=42 | virtual=true
```

The same connection ID appears in both entries and is unique within the running
JVM. The source port distinguishes clients sharing an IP address. `messageId=-`
means a text-message ID has not been read, or the connection carries HTTP traffic.
Timestamps use the JVM's local time zone.

## 🔄 Lifecycle Management

### Server Startup

1. Resolve the supplied server ID or generate a UUID default
2. Initialize the virtual thread executor
3. Start the main listener and shutdown monitor on separate platform threads
4. Display the console command prompt

### Request Processing

1. Accept client connection
2. Start a virtual thread for the connection
3. Read a text-message command or HTTP request headers
4. Return a message reply with IDs or an HTTP response
5. Close client socket

### Server Shutdown

1. Acknowledge `shutdown` and close the administrative listener
2. Mark the main server stopped and close its listening socket
3. Close the request executor, waiting for accepted tasks to finish
4. Enter `quit` to leave the console command loop and allow the application to exit

## 🤝 Contributing

This project serves as an educational example of socket programming and virtual threads in Java. Feel free to extend it with additional features such as:

- SSL/TLS support
- Request routing
- Session management
- Performance metrics
- Configuration files

## 📄 License

This project is provided as-is for educational purposes.
