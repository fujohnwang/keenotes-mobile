package cn.keevol.keenotes.utils;

import cn.keevol.keenotes.mobilefx.SettingsService;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public class SimpleForwardServer {

    public void start() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                SettingsService settings = SettingsService.getInstance();

                // 1. 创建 JDK 内置的 HTTP Server
                int LOCAL_PORT = settings.getLocalImportServerPort();
                try {
                    HttpServer server = HttpServer.create(new InetSocketAddress("localhost", LOCAL_PORT), 0);
                    Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                        @Override
                        public void run() {
                            server.stop(5);
                        }
                    }));
                    // =================================================================
                    // 核心重点：使用单线程 Executor
                    // 这直接从底层保证了请求是“串行”的。
                    // 只有当前一个请求的 handle 方法彻底执行完毕退出后，
                    // 线程才会去取下一个请求。你不需要自己写锁。
                    // =================================================================
                    server.setExecutor(Executors.newSingleThreadExecutor());

                    // 2. 创建上下文，绑定路径
                    server.createContext("/ingest", new ForwardHandler());

                    // 3. 启动
                    System.out.println("Forward Server started on port " + LOCAL_PORT);
                    System.out.println("Mode: Serial (Single Thread)");
                    server.start();
                } catch (Throwable t) {
                    t.printStackTrace();
                }

            }
        });
        t.setDaemon(true);
        t.start();
    }

}