package cn.keevol.keenotes.mobilefx;

import javafx.application.Platform;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 服务管理器 - 负责延迟初始化和管理所有服务
 * 确保UI可以快速启动，即使网络不通或配置未完成
 */
public class ServiceManager {

    private static ServiceManager instance;

    // 服务实例（延迟初始化）
    private volatile LocalCacheService localCacheService;
    private volatile ApiServiceV2 apiService;
    private volatile WebSocketClientService webSocketService;
    private volatile SettingsService settingsService;

    // 服务状态监听器
    private final CopyOnWriteArrayList<ServiceStatusListener> listeners = new CopyOnWriteArrayList<>();

    // 初始化状态标志
    private volatile boolean localCacheInitialized = false;
    private volatile boolean webSocketConnected = false;
    private volatile boolean servicesReady = false;

    private ServiceManager() {
        // 构造函数为空，所有服务延迟初始化
    }

    public static synchronized ServiceManager getInstance() {
        if (instance == null) {
            instance = new ServiceManager();
        }
        return instance;
    }

    /**
     * 获取SettingsService（总是立即可用，不依赖网络）
     */
    public synchronized SettingsService getSettingsService() {
        if (settingsService == null) {
            settingsService = SettingsService.getInstance();
        }
        return settingsService;
    }

    /**
     * 获取LocalCacheService（首次调用时初始化数据库）
     * 这是唯一可能阻塞的操作，但只在首次调用时发生
     */
    public synchronized LocalCacheService getLocalCacheService() {
        if (localCacheService == null) {
            localCacheService = LocalCacheService.getInstance();
            // 在后台线程初始化数据库，避免阻塞UI
            // 使用普通 Thread 而不是 CompletableFuture，因为后者在 Native Image 中可能有问题
            Thread initThread = new Thread(() -> {
                try {
                    System.out.println("[ServiceManager] Starting local cache initialization...");
                    localCacheService.initialize();
                    synchronized (ServiceManager.this) {
                        localCacheInitialized = true;
                    }
                    notifyStatusChanged("local_cache_ready", "本地缓存已就绪");
                    System.out.println("[ServiceManager] Local cache initialized successfully");
                } catch (Exception e) {
                    System.err.println("[ServiceManager] Local cache initialization failed!");
                    System.err.println("[ServiceManager] Error: " + e.getMessage());
                    System.err.println("[ServiceManager] This error will not prevent the UI from working");
                    e.printStackTrace();
                    notifyStatusChanged("local_cache_error", "本地缓存初始化失败: " + e.getMessage());
                    // 不要重新抛出异常，让应用继续运行
                }
            }, "LocalCacheInit");
            initThread.setDaemon(true);
            initThread.start();
        }
        return localCacheService;
    }

    /**
     * 获取ApiServiceV2（立即返回，但内部服务可能未就绪）
     */
    public synchronized ApiServiceV2 getApiService() {
        if (apiService == null) {
            apiService = new ApiServiceV2();
        }
        return apiService;
    }

    /**
     * 获取WebSocketClientService（立即返回，但连接需要手动触发）
     */
    public synchronized WebSocketClientService getWebSocketService() {
        if (webSocketService == null) {
            webSocketService = new WebSocketClientService();
            // 添加监听器以跟踪连接状态
            webSocketService.addListener(new WebSocketClientService.SyncListener() {
                @Override
                public void onConnectionStatus(boolean connected) {
                    webSocketConnected = connected;
                    if (connected) {
                        servicesReady = true;
                        notifyStatusChanged("websocket_connected", "WebSocket已连接");
                    } else {
                        notifyStatusChanged("websocket_disconnected", "WebSocket已断开");
                    }
                }

                @Override
                public void onSyncProgress(int current, int total) {
                    // 同步进度通知
                }

                @Override
                public void onSyncComplete(int total, long lastSyncId) {
                    notifyStatusChanged("sync_complete", "同步完成: " + total + " 条笔记");
                }

                @Override
                public void onRealtimeUpdate(long id, String content) {
                    // 实时更新通知
                }

                @Override
                public void onError(String message) {
                    notifyStatusChanged("websocket_error", "WebSocket错误: " + message);
                }
            });
        }
        return webSocketService;
    }

    /**
     * 延迟连接WebSocket（在UI启动后调用）
     */
    public void connectWebSocketIfNeeded() {
        // 使用普通 Thread 而不是 CompletableFuture
        Thread connectThread = new Thread(() -> {
            try {
                System.out.println("[ServiceManager] Checking WebSocket connection...");
                SettingsService settings = getSettingsService();
                if (settings.isConfigured()) {
                    System.out.println("[ServiceManager] Settings configured, connecting WebSocket...");
                    WebSocketClientService ws = getWebSocketService();
                    ws.connect();
                } else {
                    System.out.println("[ServiceManager] Settings not configured");
                    notifyStatusChanged("not_configured", "未配置服务器地址，请在设置中配置");
                }
            } catch (Exception e) {
                System.err.println("[ServiceManager] Connect error: " + e.getMessage());
                notifyStatusChanged("connect_error", "连接失败: " + e.getMessage());
            }
        }, "WebSocketConnect");
        connectThread.setDaemon(true);
        connectThread.start();
    }

    /**
     * 重新初始化所有服务（用于配置变更）
     */
    public synchronized void reinitializeServices() {
        System.out.println("[ServiceManager] Reinitializing services due to configuration change...");
        
        try {
            // 1. 断开旧的 WebSocket 连接
            if (webSocketService != null) {
                System.out.println("[ServiceManager] Disconnecting old WebSocket connection...");
                webSocketService.disconnect();
                
                // 等待断开完成
                Thread.sleep(200);
            }
            
            // 2. 重置本地缓存同步状态（保留数据，只重置同步状态）
            if (localCacheService != null && localCacheService.isInitialized()) {
                System.out.println("[ServiceManager] Resetting local cache sync state...");
                localCacheService.resetSyncState();
            }
            
            // 3. 重置服务状态标志
            webSocketConnected = false;
            servicesReady = false;
            
            // 4. 通知状态变更
            notifyStatusChanged("reinitializing", "正在重新连接到新服务器...");
            
            // 5. 重新连接到新 endpoint
            System.out.println("[ServiceManager] Reconnecting to new endpoint...");
            connectWebSocketIfNeeded();
            
            System.out.println("[ServiceManager] Service reinitialization completed");
            
        } catch (Exception e) {
            System.err.println("[ServiceManager] Service reinitialization failed: " + e.getMessage());
            e.printStackTrace();
            notifyStatusChanged("reinit_error", "重新初始化失败: " + e.getMessage());
            throw new RuntimeException("Service reinitialization failed", e);
        }
    }

    /**
     * 初始化服务（用于首次配置）
     */
    public synchronized void initializeServices() {
        System.out.println("[ServiceManager] Initializing services for first-time configuration...");
        
        // 1. 触发缓存初始化
        getLocalCacheService();
        
        // 2. 连接 WebSocket
        connectWebSocketIfNeeded();
        
        System.out.println("[ServiceManager] Service initialization completed");
    }

    /**
     * 检查服务是否就绪
     */
    public boolean isServicesReady() {
        return servicesReady;
    }

    public boolean isLocalCacheInitialized() {
        return localCacheInitialized;
    }

    public boolean isWebSocketConnected() {
        return webSocketConnected;
    }

    /**
     * 注册服务状态监听器
     */
    public void addListener(ServiceStatusListener listener) {
        listeners.add(listener);
    }

    public void removeListener(ServiceStatusListener listener) {
        listeners.remove(listener);
    }

    private void notifyStatusChanged(String status, String message) {
        // 在JavaFX线程中通知UI
        Platform.runLater(() -> {
            listeners.forEach(l -> l.onStatusChanged(status, message));
        });
    }

    /**
     * 关闭所有服务
     */
    public void shutdown() {
        System.out.println("[ServiceManager] Starting shutdown...");

        // 先关闭WebSocket服务，停止网络连接
        if (webSocketService != null) {
            System.out.println("[ServiceManager] Shutting down WebSocket service...");
            webSocketService.shutdown();
        }

        // 关闭本地缓存服务
        if (localCacheService != null) {
            System.out.println("[ServiceManager] Closing local cache service...");
            localCacheService.close();
        }

        // 关闭API服务（如果需要）
        if (apiService != null) {
            // ApiServiceV2 如果有需要关闭的资源，在这里处理
            System.out.println("[ServiceManager] API service shutdown complete");
        }

        System.out.println("[ServiceManager] All services shutdown complete");
    }

    /**
     * 服务状态监听器接口
     */
    public interface ServiceStatusListener {
        void onStatusChanged(String status, String message);
    }
}
