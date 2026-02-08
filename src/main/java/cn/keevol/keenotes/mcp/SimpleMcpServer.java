package cn.keevol.keenotes.mcp;

import cn.keevol.keenotes.mobilefx.ApiServiceV2;
import cn.keevol.keenotes.mobilefx.ServiceManager;
import cn.keevol.keenotes.mobilefx.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lightweight MCP Server implementation for KeeNotes.
 * Provides Model Context Protocol server functionality over HTTP.
 * 
 * No SDK transport abstractions - direct MCP JSON-RPC protocol implementation.
 */
public class SimpleMcpServer {
    
    protected static final AtomicBoolean running = new AtomicBoolean(false);
    protected static final AtomicReference<HttpServer> httpServerRef = new AtomicReference<>();
    protected static final AtomicReference<ExecutorService> executorRef = new AtomicReference<>();
    
    public static int stopWaitInSeconds = 5;
    
    public static void start() {
        if (running.compareAndSet(false, true)) {
            Thread thread = new Thread(() -> {
                try {
                    SettingsService settings = SettingsService.getInstance();
                    
                    if (!settings.isMcpServerEnabled()) {
                        System.out.println("MCP Server is disabled in settings");
                        running.set(false);
                        return;
                    }
                    
                    int MCP_PORT = settings.getMcpServerPort();
                    
                    // Get API service
                    ApiServiceV2 apiService = ServiceManager.getInstance().getApiService();
                    
                    // Create AddNoteTool
                    AddNoteTool addNoteTool = new AddNoteTool(apiService);
                    
                    // Create ObjectMapper
                    ObjectMapper mapper = new ObjectMapper();
                    
                    // Create HTTP Server
                    HttpServer server = HttpServer.create(new InetSocketAddress("localhost", MCP_PORT), 0);
                    httpServerRef.set(server);
                    
                    // Use single thread executor for serial processing
                    ExecutorService executor = Executors.newSingleThreadExecutor();
                    executorRef.set(executor);
                    server.setExecutor(executor);
                    
                    // Create MCP handler
                    StreamableHttpMcpServerTransport mcpHandler = new StreamableHttpMcpServerTransport(
                        addNoteTool,
                        mapper,
                        "keenotes-mcp-server",
                        "1.0.0"
                    );
                    
                    // Bind handler to /mcp endpoint
                    server.createContext("/mcp", mcpHandler);
                    
                    // Start server
                    System.out.println("MCP Server started on port " + MCP_PORT);
                    System.out.println("MCP endpoint: http://localhost:" + MCP_PORT + "/mcp");
                    server.start();
                    
                } catch (Throwable t) {
                    t.printStackTrace();
                    running.set(false);
                }
            });
            thread.setDaemon(true);
            thread.start();
        }
    }
    
    public static void stop() {
        if (running.compareAndSet(true, false)) {
            System.out.println("Stopping MCP Server...");
            
            // 1. Stop HTTP Server first
            HttpServer server = httpServerRef.get();
            if (server != null) {
                server.stop(stopWaitInSeconds);
                httpServerRef.set(null);
            }
            
            // 2. Shutdown ExecutorService
            ExecutorService executor = executorRef.get();
            if (executor != null) {
                try {
                    executor.shutdown();
                    if (!executor.awaitTermination(stopWaitInSeconds, TimeUnit.SECONDS)) {
                        System.err.println("Executor did not terminate in time, forcing shutdown");
                        executor.shutdownNow();
                    }
                    executorRef.set(null);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    executor.shutdownNow();
                    System.err.println("Interrupted while shutting down executor");
                }
            }
            
            System.out.println("MCP Server stopped.");
        }
    }
    
    public static void restart() {
        System.out.println("Restarting MCP Server...");
        stop();
        start();
    }
}
