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

public class EchoServer {
    private int port = 8111;
    private ServerSocket serverSocket;
    private ExecutorService service;
    private final int POOL_SIZE=4;

    private int portForShutdown=9001;
    private ServerSocket serverSocketForShutdown;
    private boolean isShutdown = false; //这个变量在shutdown线程中被修改，却没保证在其他线程的可见性，所以实际上不是依赖它来关闭线程的

    private Thread shutdownThread = new Thread() {
        public void start() {
            this.setDaemon(true);
            super.start();
        }

        public void run() {
            while (!isShutdown) {
                Socket socketForShutdown = null;
                try {
                    socketForShutdown = serverSocketForShutdown.accept();
                    BufferedReader br = new BufferedReader(new InputStreamReader(socketForShutdown.getInputStream()));
                    String cmd = br.readLine();

                    if (cmd.equals("shutdown")) {
                        long beginTime = System.currentTimeMillis();
                        socketForShutdown.getOutputStream().write("服务器正在关闭\r\n".getBytes());
                        isShutdown = true;

                        //线程池不再接收新的任务，但是会继续执行完工作队列中现有的任务
                        service.shutdown();

                        while (!service.isTerminated()) {
                            service.awaitTermination(30, TimeUnit.SECONDS);
                        }

                        serverSocket.close(); //关闭Echo server的通信ServerSocket
                        long endTime = System.currentTimeMillis();
                        socketForShutdown.getOutputStream().write(("服务器已经关闭，耗时" + (endTime - beginTime) + "毫秒\r\n").getBytes());
                        socketForShutdown.close();
                        serverSocketForShutdown.close();
                    }
                    else {
                        socketForShutdown.getOutputStream().write("错误的命令\r\n".getBytes());
                        socketForShutdown.close();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    };

    public EchoServer() throws IOException {
        serverSocket = new ServerSocket(port);
        serverSocket.setSoTimeout(60000); //一分钟内没连接就关闭了
        serverSocketForShutdown = new ServerSocket(portForShutdown);

        service = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * POOL_SIZE);

        shutdownThread.start(); //又是典型的构造函数内部启动新线程
        System.out.println("服务器启动！");
    }

    public static void main(String[] args) throws IOException {
        new EchoServer().service();
    }

    public void service() {
        while (!isShutdown) {
            Socket socket = null;
            try {
                socket = serverSocket.accept();
                socket.setSoTimeout(60000);
                System.out.println("new connection accepted:" + socket.getInetAddress() + ":" + socket.getPort());
                service.execute(new Handler(socket));
            } catch (SocketTimeoutException e) {

            } catch (RejectedExecutionException e) {
                // 工作队列不再接收新的任务时，表示收到了shutdown命令
                try {
                    if (socket != null) {
                        socket.close();
                    }
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
                return;
            } catch (SocketException e) {
                // 如果是由于在执行serverSocket.accept()方法时，
                // ServerSocket被Shutdown Thread关闭而导致的异常，就退出service方法
                if (e.getMessage().indexOf("socket closed") != -1) {
                    return;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}

class Handler implements Runnable {
    private Socket socket;

    public Handler(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
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
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
