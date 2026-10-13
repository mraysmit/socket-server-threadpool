package dev.mars.server;

import dev.mars.worker.WorkerRunnable;
import dev.mars.plugin.PluginRegistry;

import java.net.ServerSocket;
import java.net.Socket;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ThreadPooledServer implements Runnable{
    private static final int CONNECTION_BACKLOG = 256;
    private final String serverId;
    private final PluginRegistry plugins;
    protected int          serverPort   = 8080;
    protected volatile ServerSocket serverSocket = null;
    protected final AtomicBoolean isStopped = new AtomicBoolean(false);
    protected Thread       runningThread= null;
    protected final ExecutorService requestExecutor;
    final protected String SERVER_NAME = "ThreadPooledServer";

    public ThreadPooledServer(int port) {
        this(port, null);
    }

    public ThreadPooledServer(int port, String serverId) {
        this(port, serverId, null);
    }

    public ThreadPooledServer(int port, String serverId, PluginRegistry plugins) {
        this.serverId = ServerOptions.resolveServerId(serverId);
        ServerOptions.validatePort(port, "Main port");
        this.serverPort = port;
        try { this.plugins = plugins == null ? PluginRegistry.load(null) : plugins; }
        catch (IOException e) { throw new UncheckedIOException("Cannot load command plugins", e); }
        requestExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public void run(){
        try {
            synchronized(this){
                this.runningThread = Thread.currentThread();
                if (isStopped.get()) {
                    return;
                }
                bind();
            }
            System.out.println(SERVER_NAME + " started on port: " + getPort() + " | serverId=" + serverId);
            while(! isStopped()){
                Socket clientSocket;
                try {
                    clientSocket = this.serverSocket.accept();
                } catch (IOException e) {
                    if(isStopped()) {
                        System.out.println(SERVER_NAME + " Stopped.") ;
                        break;
                    }
                    throw new RuntimeException("Error accepting client connection", e);
                }
                this.requestExecutor.execute(new WorkerRunnable(clientSocket, SERVER_NAME, serverId, plugins));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot start " + SERVER_NAME, e);
        } finally {
            try {
                stop();
            } finally {
                // Wait for accepted requests before exiting, including after a bind failure.
                this.requestExecutor.close();
            }
            System.out.println(SERVER_NAME + " Closed.") ;
        }
    }

    public synchronized boolean isStopped() {
        return this.isStopped.get();
    }

    public String getServerId() {
        return serverId;
    }

    public synchronized void stop(){
        this.isStopped.getAndSet(true);
        try {
            if (this.serverSocket != null) {
                this.serverSocket.close();
            }
        } catch (IOException e) {
            throw new RuntimeException("Error closing " + SERVER_NAME , e);
        }
    }

    public synchronized void bind() throws IOException {
        if (isStopped.get()) {
            throw new IOException("Main server has already been stopped");
        }
        if (serverSocket == null) {
            serverSocket = new ServerSocket(serverPort, CONNECTION_BACKLOG);
        }
    }

    public synchronized int getPort() {
        return serverSocket == null ? serverPort : serverSocket.getLocalPort();
    }
}
