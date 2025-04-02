package dev.mars;

public class StartStop {

    public static void main(String[] args) {

        /*
        var server = new ThreadPooledServer(9000, 10);
        var monitor = new ShutdownMonitor(9001, server);

        System.out.println("Starting Server");
        new Thread(server).start();
        new Thread(monitor).start();

        System.out.println("To stop server, connect to port 9001");
         */



        var server = new ThreadPooledServer(9000, 10);
        System.out.println("Starting Server");
        new Thread(server).start();

        try {
            Thread.sleep(20 * 1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        System.out.println("Stopping Server");
        server.stop();


    }

}
