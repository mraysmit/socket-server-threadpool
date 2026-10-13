package dev.mars.server;

import dev.mars.worker.WorkerRunnable;

import java.net.ServerSocket;
import java.net.Socket;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ThreadPooledServer implements Runnable{
    private static final int CONNECTION_BACKLOG = 256;
    private final UUID serverId = UUID.randomUUID();
    protected int          serverPort   = 8080;
    protected ServerSocket serverSocket = null;
    protected final AtomicBoolean isStopped = new AtomicBoolean(false);
    protected Thread       runningThread= null;
    protected final ExecutorService requestExecutor;
    final protected String SERVER_NAME = "ThreadPooledServer";

    public ThreadPooledServer(int port) {
        this.serverPort = port;
        requestExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public void run(){
        try {
            synchronized(this){
                this.runningThread = Thread.currentThread();
                if (isStopped.get()) {
                    return;
                }
                openServerSocket();
            }
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
                this.requestExecutor.execute(new WorkerRunnable(clientSocket, SERVER_NAME, serverId));
            }
        } finally {
            // Virtual threads are daemon threads; wait for accepted requests before exiting.
            this.requestExecutor.close();
            System.out.println(SERVER_NAME + " Closed.") ;
        }
    }

    public synchronized boolean isStopped() {
        return this.isStopped.get();
    }

    public UUID getServerId() {
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

    private void openServerSocket() {
        try {
            this.serverSocket = new ServerSocket(this.serverPort, CONNECTION_BACKLOG);
            System.out.println(SERVER_NAME + " started on port: " + this.serverPort + " | serverId=" + serverId);
        } catch (IOException e) {
            throw new RuntimeException(SERVER_NAME + "Cannot open port " + this.serverPort, e);
        }
    }
}
