package com.yunxi.net;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.List;

public class EchoServer {
    private int port = 8111;
    private ServerSocket serverSocket;
    private ExecutorService service;
    private final int POOL_SIZE=4;

    private int portForShutdown=9001;
    private ServerSocket serverSocketForShutdown;

    // volatile保证可见性？不太懂"不是依赖它来关闭线程"是什么意思？
    private boolean isShutdown = false; //这个变量在shutdown线程中被修改，却没保证在其他线程的可见性，所以实际上不是依赖它来关闭线程的

    private Thread shutdownThread;

    public EchoServer() throws IOException {
        serverSocket = new ServerSocket(port);
        serverSocket.setSoTimeout(60000); //一分钟内没连接就关闭了  --这里不是关闭，是会抛出个异常吧
        serverSocketForShutdown = new ServerSocket(portForShutdown);

        service = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * POOL_SIZE);
    }

    public void startShutdownService() throws IOException {
        if (isShutdown) {
            throw new IOException("Server has benn closed.");
        }
        shutdownThread = new ShutdownService();
        shutdownThread.start();
        System.out.println("服务器启动！");
    }

    public static void main(String[] args) throws IOException {
        EchoServer server = new EchoServer();
        server.startShutdownService();
        server.startEchoService();
    }

    /**
     * Start echo service in blocking mode.
     */
    public void startEchoService() {
        while (!isShutdown) {
            Socket socket = null;
            try {
                socket = serverSocket.accept();
                if (isShutdown) { // 被唤醒后，可以再check一次
                    throw new IOException("Server is shutdown.");
                }
                socket.setSoTimeout(60000);
                System.out.println("new connection accepted:" + socket.getInetAddress() + ":" + socket.getPort());
                service.execute(new Handler(socket));
            } catch (SocketTimeoutException e) {
                e.printStackTrace();
            } catch (RejectedExecutionException e) {
                e.printStackTrace();
            } catch (SocketException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                try {
                    if (socket != null) {
                        socket.close();
                    }
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
            }
        }
    }

    /**
     * Shut down service.
     */
    class ShutdownService extends Thread {

        @Override public void start() {
            this.setDaemon(true);
            super.start();
        }

        @Override public void run() {
            while (!isShutdown) {
                Socket socketForShutdown = null;
                try {
                    socketForShutdown = serverSocketForShutdown.accept();
                    BufferedReader br = new BufferedReader(
                        new InputStreamReader(socketForShutdown.getInputStream()));
                    String cmd = br.readLine();

                    if (cmd.equals("shutdown")) {
                        long beginTime = System.currentTimeMillis();
                        socketForShutdown.getOutputStream()
                            .write("服务器正在关闭\r\n".getBytes());
                        isShutdown = true;

                        // 这里是不是应该把left的连接都关掉？像下面这样
                        List<Runnable> left = service.shutdownNow();
                        for (Runnable run : left) {
                            try {
                                ((Handler) run).shutDown();
                            } catch (Exception e) {
                            }
                        }

                        while (!service.isTerminated()) {
                            service.awaitTermination(30, TimeUnit.SECONDS);
                        }

                        serverSocket.close(); //关闭Echo server的通信ServerSocket
                        long endTime = System.currentTimeMillis();
                        socketForShutdown.getOutputStream().write(
                            ("服务器已经关闭，耗时" + (endTime - beginTime) + "毫秒\r\n")
                                .getBytes());
                        socketForShutdown.close();
                        serverSocketForShutdown.close();
                    } else {
                        socketForShutdown.getOutputStream()
                            .write("错误的命令\r\n".getBytes());
                        socketForShutdown.close();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Echo service handler.
     */
    class Handler implements Runnable {
        private Socket socket;

        public Handler(Socket socket) {
            this.socket = socket;
        }

        void shutDown() {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        @Override
        public void run() {
            // comments: shutdownNow -> interrupt这个线程，来关掉
            try {
                Thread th = Thread.currentThread();
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
                PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()));

                String msg = null;
                while ((msg = reader.readLine()) != null) {
                    System.out.println("received msg line: " + msg + " Thread ID:" + th.getId());

                    if (msg.equals("bye")) {
                        System.out.println("对方自闭了，关闭连接" + " Thread ID:" + th.getId());
                        break;
                    }
                    writer.println("echo " + msg);
                    writer.flush();
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                shutDown();
            }
        }
    }
}