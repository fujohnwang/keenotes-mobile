package cn.keevol.keenotes.mobilefx;

import javafx.application.Platform;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 服务管理器 - 负责延迟初始化和管理所有服务
 * 确保UI可以快速启动，即使网络不通或配置未完成
 */
public class ServiceManager {

    /**
     * 初始化状态枚举
     */
    public enum InitializationState {
        NOT_STARTED("未开始"),
        INITIALIZING("初始化中"),
        READY("就绪"),
        ERROR("错误");
        
        private final String description;
        
        InitializationState(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }

    private static ServiceManager instance;

    // 服务实例（延迟初始化）
    private volatile LocalCacheService localCacheService;
    private volatile ApiServiceV2 apiService;
    private volatile WebSocketClientService webSocketService;
    private volatile SettingsService settingsService;

    // 服务状态监听器
    private final CopyOnWriteArrayList<ServiceStatusListener> listeners = new CopyOnWriteArrayList<>();

    // 初始化状态标志 - 使用新的状态管理
    private volatile InitializationState localCacheState = InitializationState.NOT_STARTED;
    private volatile String localCacheErrorMessage = null;
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
        }
        
        // 检查是否需要初始化
        if (localCacheState == InitializationState.NOT_STARTED && !localCacheService.isInitialized()) {
            localCacheState = InitializationState.INITIALIZING;
            
            // 在后台线程初始化数据库，避免阻塞UI
            // 使用普通 Thread 而不是 CompletableFuture，因为后者在 Native Image 中可能有问题
            Thread initThread = new Thread(() -> {
                try {
                    System.out.println("[ServiceManager] Starting local cache initialization...");
                    System.out.println("[ServiceManager] Platform: " + System.getProperty("os.name"));
                    System.out.println("[ServiceManager] Java version: " + System.getProperty("java.version"));
                    
                    // Android特定：增加初始化延迟，确保应用完全启动
                    String osName = System.getProperty("os.name", "").toLowerCase();
                    if (osName.contains("android") || osName.contains("linux")) {
                        System.out.println("[ServiceManager] Detected mobile platform, adding initialization delay...");
                        Thread.sleep(1000); // 等待1秒确保应用完全启动
                    }
                    
                    localCacheService.initialize();
                    synchronized (ServiceManager.this) {
                        localCacheState = InitializationState.READY;
                        localCacheErrorMessage = null;
                    }
                    notifyStatusChanged("local_cache_ready", "本地缓存已就绪");
                    System.out.println("[ServiceManager] Local cache initialized successfully");
                } catch (InterruptedException e) {
                    System.err.println("[ServiceManager] Local cache initialization interrupted");
                    Thread.currentThread().interrupt();
                    synchronized (ServiceManager.this) {
                        localCacheState = InitializationState.ERROR;
                        localCacheErrorMessage = "初始化被中断";
                    }
                    notifyStatusChanged("local_cache_error", "本地缓存初始化被中断");
                } catch (Exception e) {
                    System.err.println("[ServiceManager] Local cache initialization failed!");
                    System.err.println("[ServiceManager] Error: " + e.getMessage());
                    System.err.println("[ServiceManager] This error will not prevent the UI from working");
                    e.printStackTrace();
                    
                    synchronized (ServiceManager.this) {
                        localCacheState = InitializationState.ERROR;
                        localCacheErrorMessage = e.getMessage();
                    }
                    notifyStatusChanged("local_cache_error", "本地缓存初始化失败: " + e.getMessage());
                    
                    // Android特定：重试机制
                    String osName = System.getProperty("os.name", "").toLowerCase();
                    if (osName.contains("android") || osName.contains("linux")) {
                        System.out.println("[ServiceManager] Attempting retry in 3 seconds...");
                        try {
                            Thread.sleep(3000);
                            System.out.println("[ServiceManager] Retrying local cache initialization...");
                            localCacheService.initialize();
                            synchronized (ServiceManager.this) {
                                localCacheState = InitializationState.READY;
                                localCacheErrorMessage = null;
                            }
                            notifyStatusChanged("local_cache_ready", "本地缓存已就绪（重试成功）");
                            System.out.println("[ServiceManager] Local cache initialized successfully on retry");
                        } catch (Exception retryException) {
                            System.err.println("[ServiceManager] Retry also failed: " + retryException.getMessage());
                            synchronized (ServiceManager.this) {
                                localCacheState = InitializationState.ERROR;
                                localCacheErrorMessage = "重试失败: " + retryException.getMessage();
                            }
                            notifyStatusChanged("local_cache_error", "本地缓存初始化重试失败");
                        }
                    }
                }
            }, "LocalCacheInit");
            initThread.setDaemon(true);
            initThread.start();
        } else if (localCacheService.isInitialized() && localCacheState != InitializationState.READY) {
            // 如果服务已经初始化但状态不对，同步状态
            synchronized (this) {
                localCacheState = InitializationState.READY;
                localCacheErrorMessage = null;
            }
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

    /**
     * 获取本地缓存初始化状态
     */
    public InitializationState getLocalCacheState() {
        return localCacheState;
    }

    /**
     * 获取本地缓存错误信息
     */
    public String getLocalCacheErrorMessage() {
        return localCacheErrorMessage;
    }

    /**
     * 检查本地缓存是否已初始化
     */
    public boolean isLocalCacheInitialized() {
        return localCacheState == InitializationState.READY;
    }

    /**
     * 检查本地缓存是否出错
     */
    public boolean isLocalCacheError() {
        return localCacheState == InitializationState.ERROR;
    }

    /**
     * 重试本地缓存初始化
     */
    public void retryLocalCacheInitialization() {
        if (localCacheState == InitializationState.ERROR) {
            System.out.println("[ServiceManager] Retrying local cache initialization...");
            localCacheState = InitializationState.INITIALIZING;
            localCacheErrorMessage = null;
            
            Thread retryThread = new Thread(() -> {
                try {
                    localCacheService.initialize();
                    synchronized (ServiceManager.this) {
                        localCacheState = InitializationState.READY;
                        localCacheErrorMessage = null;
                    }
                    notifyStatusChanged("local_cache_ready", "本地缓存已就绪");
                } catch (Exception e) {
                    System.err.println("[ServiceManager] Retry failed: " + e.getMessage());
                    synchronized (ServiceManager.this) {
                        localCacheState = InitializationState.ERROR;
                        localCacheErrorMessage = e.getMessage();
                    }
                    notifyStatusChanged("local_cache_error", "重试失败: " + e.getMessage());
                }
            }, "LocalCacheRetry");
            retryThread.setDaemon(true);
            retryThread.start();
        } else {
            System.out.println("[ServiceManager] Retry called but state is not ERROR: " + localCacheState);
        }
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
