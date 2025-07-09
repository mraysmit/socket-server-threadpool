# Socket Server with Thread Pool

A Java-based multi-threaded socket server implementation that demonstrates concurrent client handling using a thread pool architecture. This project provides a robust server solution with graceful shutdown capabilities and administrative monitoring.

## 🚀 Features

- **Thread Pool Architecture**: Efficiently handles multiple concurrent client connections using a fixed-size thread pool
- **HTTP Response Support**: Returns proper HTTP responses using SOLID design principles
- **Graceful Shutdown**: Administrative interface for controlled server shutdown
- **Status Monitoring**: Real-time server status checking capabilities
- **Console Management**: Interactive command-line interface for server administration
- **Thread-Safe Operations**: Uses atomic operations for safe concurrent access

## 📋 Project Structure

```
src/main/java/dev/mars/
├── StartStop.java           # Main application entry point
├── ThreadPooledServer.java  # Core server implementation
├── WorkerRunnable.java      # Client request handler
├── ShutdownMonitor.java     # Administrative monitoring service
├── HttpResponse.java        # HTTP response data model
├── HttpResponseBuilder.java # HTTP response builder (Builder pattern)
└── HttpResponseWriter.java  # HTTP response writer service
```

## 🏗️ Architecture Overview

### Core Components

1. **ThreadPooledServer**: The main server that listens for client connections on port 9000
   - Uses a fixed thread pool (default: 10 threads)
   - Accepts incoming socket connections
   - Delegates client handling to worker threads

2. **WorkerRunnable**: Handles individual client requests
   - Processes each client connection in a separate thread
   - Uses HTTP response classes for clean separation of concerns
   - Logs request processing details

5. **HttpResponse**: Data model for HTTP responses
   - Encapsulates status line, headers, and body
   - Provides content length calculation

6. **HttpResponseBuilder**: Builder pattern for creating HTTP responses
   - Fluent API for constructing responses
   - Provides common response factory methods
   - Automatically handles content-length calculation

7. **HttpResponseWriter**: Service for writing HTTP responses
   - Handles the serialization of HTTP responses to output streams
   - Provides convenience methods for common response types

3. **ShutdownMonitor**: Administrative service running on port 9001
   - Provides server status checking
   - Enables graceful server shutdown
   - Accepts administrative commands

4. **StartStop**: Main application launcher
   - Initializes and starts both servers
   - Provides console-based command interface
   - Manages application lifecycle

## 🛠️ Technical Specifications

- **Java Version**: 24
- **Build Tool**: Maven
- **Main Server Port**: 9000
- **Admin Port**: 9001
- **Default Thread Pool Size**: 10 threads
- **Response Format**: HTTP/1.1 with proper headers

## 🚦 Getting Started

### Prerequisites

- Java 24 or higher
- Maven 3.6+

### Building the Project

```bash
mvn clean compile
```

### Running the Server

```bash
mvn exec:java -Dexec.mainClass="dev.mars.StartStop"
```

Or compile and run directly:

```bash
javac -cp src/main/java src/main/java/dev/mars/*.java
java -cp src/main/java dev.mars.StartStop
```

## 💻 Usage

### Starting the Server

When you run the application, it will:

1. Start the main server on port 9000
2. Start the shutdown monitor on port 9001
3. Display a console prompt for commands

### Console Commands

The application provides an interactive console with the following commands:

- `status` - Check if the server is running
- `shutdown` - Gracefully shutdown the server
- `quit` - Exit the console application

### Testing the Server

#### Test Client Connections

You can test the server using various tools:

**Using curl:**
```bash
curl http://localhost:9000
```

**Using telnet:**
```bash
telnet localhost 9000
```

**Using netcat:**
```bash
echo "GET / HTTP/1.1" | nc localhost 9000
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

The server returns HTTP-compliant responses in the following format:

```
HTTP/1.1 200 OK
Content-Type: text/plain; charset=UTF-8
Content-Length: [length]

WorkerRunnable: ThreadPooledServer - [timestamp]
```

## 🔧 Configuration

### Customizing Server Settings

You can modify the server configuration by editing the `StartStop.java` file:

```java
// Change port and thread pool size
var threadPooledServer = new ThreadPooledServer(9000, 10);  // port, thread count
var shutdownMonitor = new ShutdownMonitor(9001, threadPooledServer);  // admin port
```

### Thread Pool Sizing

The default thread pool size is 10. Consider adjusting based on:
- Expected concurrent connections
- Server hardware capabilities
- Response time requirements

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

## 🔄 Lifecycle Management

### Server Startup
1. Initialize thread pool
2. Bind to server socket
3. Start accepting connections
4. Launch shutdown monitor

### Request Processing
1. Accept client connection
2. Submit to thread pool
3. Process in worker thread
4. Return HTTP response
5. Close client socket

### Server Shutdown
1. Stop accepting new connections
2. Complete existing requests
3. Shutdown thread pool
4. Close server socket
5. Clean up resources

## 🤝 Contributing

This project serves as an educational example of socket programming and thread pool management in Java. Feel free to extend it with additional features such as:

- SSL/TLS support
- Request routing
- Session management
- Performance metrics
- Configuration files

## 📄 License

This project is provided as-is for educational purposes.
