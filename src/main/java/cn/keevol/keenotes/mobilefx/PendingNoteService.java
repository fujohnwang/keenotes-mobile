package cn.keevol.keenotes.mobilefx;

import javafx.application.Platform;
import javafx.beans.property.IntegerProperty;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * 离线暂存笔记的调度服务
 * - 暂存发送失败的笔记
 * - 定时重试发送（30分钟间隔）
 * - WebSocket重连时立即触发重试
 */
public class PendingNoteService {
    private static final Logger logger = Logger.getLogger(PendingNoteService.class.getName());
    private static final DateTimeFormatter TS_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long RETRY_INTERVAL_MINUTES = 30;

    private static PendingNoteService instance;

    private final LocalCacheService localCache;
    private ScheduledExecutorService retryScheduler;
    private volatile boolean retrying = false;

    private PendingNoteService() {
        this.localCache = LocalCacheService.getInstance();
    }

    public static synchronized PendingNoteService getInstance() {
        if (instance == null) {
            instance = new PendingNoteService();
        }
        return instance;
    }

    /**
     * 获取 pending count 的 reactive property（代理到 LocalCacheService）
     */
    public IntegerProperty pendingCountProperty() {
        return localCache.pendingNoteCountProperty();
    }

    /**
     * 暂存一条笔记到本地
     */
    public void savePendingNote(String content, String channel) {
        try {
            localCache.insertPendingNote(content, channel);
            logger.info("Note saved to pending: " + content.substring(0, Math.min(20, content.length())) + "...");
        } catch (Exception e) {
            logger.warning("Failed to save pending note: " + e.getMessage());
        }
    }

    /**
     * 获取所有待发送笔记
     */
    public List<LocalCacheService.PendingNoteData> getPendingNotes() {
        return localCache.getPendingNotes();
    }

    /**
     * 启动定时重试调度器
     */
    public void startRetryScheduler() {
        if (retryScheduler != null && !retryScheduler.isShutdown()) {
            return;
        }
        retryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pending-note-retry");
            t.setDaemon(true);
            return t;
        });
        retryScheduler.scheduleAtFixedRate(
                this::retryPendingNotes,
                RETRY_INTERVAL_MINUTES,
                RETRY_INTERVAL_MINUTES,
                TimeUnit.MINUTES
        );
        logger.info("Pending note retry scheduler started (interval: " + RETRY_INTERVAL_MINUTES + " min)");
    }

    /**
     * WebSocket 重连成功时调用，立即触发一次重试
     */
    public void onNetworkRestored() {
        if (localCache.getPendingNoteCount() > 0) {
            logger.info("Network restored, triggering pending note retry");
            CompletableFuture.runAsync(this::retryPendingNotes);
        }
    }

    /**
     * 逐条重试发送 pending notes（按 created_at 顺序）
     */
    private void retryPendingNotes() {
        if (retrying) return;
        retrying = true;

        try {
            List<LocalCacheService.PendingNoteData> pendingNotes = localCache.getPendingNotes();
            if (pendingNotes.isEmpty()) return;

            logger.info("Retrying " + pendingNotes.size() + " pending notes...");
            ApiServiceV2 apiService = ServiceManager.getInstance().getApiService();

            for (LocalCacheService.PendingNoteData note : pendingNotes) {
                try {
                    ApiServiceV2.ApiResult result = apiService.postNote(
                            note.content, note.channel, note.createdAt
                    ).get(30, TimeUnit.SECONDS);

                    if (result.success()) {
                        localCache.deletePendingNote(note.id);
                        logger.info("Pending note sent successfully, id=" + note.id);
                    } else {
                        logger.warning("Pending note send failed, id=" + note.id + ": " + result.message());
                        break; // 失败后停止，等下次重试
                    }
                } catch (TimeoutException e) {
                    logger.warning("Pending note send timeout, id=" + note.id);
                    break;
                } catch (Exception e) {
                    logger.warning("Pending note send error, id=" + note.id + ": " + e.getMessage());
                    break;
                }
            }
        } finally {
            retrying = false;
        }
    }

    /**
     * 检查网络是否可用（基于 WebSocket 连接状态）
     */
    public boolean isNetworkAvailable() {
        return ServiceManager.getInstance().getWebSocketService().isConnected();
    }

    public void shutdown() {
        if (retryScheduler != null && !retryScheduler.isShutdown()) {
            retryScheduler.shutdownNow();
            logger.info("Pending note retry scheduler stopped");
        }
    }
}
