package cn.keevol.keenotes.mobilefx;

import javafx.application.Platform;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

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
            CompletableFuture.runAsync(() -> {
                try {
                    localCacheService.initialize();
                    synchronized (ServiceManager.this) {
                        localCacheInitialized = true;
                    }
                    notifyStatusChanged("local_cache_ready", "本地缓存已就绪");
                } catch (Exception e) {
                    notifyStatusChanged("local_cache_error", "本地缓存初始化失败: " + e.getMessage());
                }
            });
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
        // 确保在JavaFX线程之外执行网络操作
        CompletableFuture.runAsync(() -> {
            try {
                SettingsService settings = getSettingsService();
                if (settings.isConfigured()) {
                    WebSocketClientService ws = getWebSocketService();
                    ws.connect();
                } else {
                    notifyStatusChanged("not_configured", "未配置服务器地址，请在设置中配置");
                }
            } catch (Exception e) {
                notifyStatusChanged("connect_error", "连接失败: " + e.getMessage());
            }
        });
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
        if (webSocketService != null) {
            webSocketService.shutdown();
        }
        if (localCacheService != null) {
            localCacheService.close();
        }
    }

    /**
     * 服务状态监听器接口
     */
    public interface ServiceStatusListener {
        void onStatusChanged(String status, String message);
    }
}
