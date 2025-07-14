package dev.mars.server;

import dev.mars.worker.WorkerRunnable;

import java.net.ServerSocket;
import java.net.Socket;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ThreadPooledServer implements Runnable{
    protected int          serverPort   = 8080;
    protected ServerSocket serverSocket = null;
    protected final AtomicBoolean isStopped = new AtomicBoolean(false);
    protected Thread       runningThread= null;
    protected ExecutorService threadPool = null;
    final protected String SERVER_NAME = "ThreadPooledServer";

    public ThreadPooledServer(int port, int threadPoolSize) {
        this.serverPort = port;
        threadPool = Executors.newFixedThreadPool(threadPoolSize);
    }

    public void run(){
        synchronized(this){
            this.runningThread = Thread.currentThread();
        }
        openServerSocket();
        while(! isStopped()){
            Socket clientSocket = null;
            try {
                clientSocket = this.serverSocket.accept();
                System.out.println("Client connected: " + clientSocket.getInetAddress());

            } catch (IOException e) {
                if(isStopped()) {
                    System.out.println(SERVER_NAME + " Stopped.") ;
                    break;
                }
                throw new RuntimeException("Error accepting client connection", e);
            }
            this.threadPool.execute(new WorkerRunnable(clientSocket, SERVER_NAME));
        }
        this.threadPool.shutdown();
        System.out.println(SERVER_NAME + " Closed.") ;
    }

    public synchronized boolean isStopped() {
        return this.isStopped.get();
    }

    public synchronized void stop(){
        this.isStopped.getAndSet(true);
        try {
            this.serverSocket.close();
        } catch (IOException e) {
            throw new RuntimeException("Error closing " + SERVER_NAME , e);
        }
    }

    private void openServerSocket() {
        try {
            this.serverSocket = new ServerSocket(this.serverPort);
            System.out.println(SERVER_NAME + " started on port: " + this.serverPort);
        } catch (IOException e) {
            throw new RuntimeException(SERVER_NAME + "Cannot open port " + this.serverPort, e);
        }
    }
}
