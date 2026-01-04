package cn.keevol.keenotes.mobilefx;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Priority;

/**
 * Debug view for development and troubleshooting.
 * Contains all debug-related functionality.
 */
public class DebugView extends VBox {

    private final Label statusLabel;

    public DebugView(Runnable onBack) {
        getStyleClass().add("debug-view");

        // Status label for feedback
        statusLabel = new Label();
        statusLabel.getStyleClass().add("debug-status");
        statusLabel.setWrapText(true);

        // Debug buttons container
        VBox debugContainer = new VBox(12);
        debugContainer.setPadding(new Insets(16));
        debugContainer.getStyleClass().add("debug-container");

        // Add status label and all debug buttons (matching Android order)
        debugContainer.getChildren().addAll(
            statusLabel,
            createDebugButton("Check DB Count", this::checkDbCount),
            createDebugButton("Dump All Notes", this::dumpAllNotes),
            createDebugButton("Reset Sync State", this::resetSyncState),
            createDebugButton("Clear All Notes", this::clearAllNotes),
            createDebugButton("Test WebSocket", this::testWebSocket)
        );

        // Spacer to push content up
        VBox.setVgrow(debugContainer, Priority.ALWAYS);

        // Header with back button and debug container
        getChildren().addAll(createHeader(onBack), debugContainer);
    }

    private HBox createHeader(Runnable onBack) {
        Label title = new Label("Debug View");
        title.getStyleClass().add("debug-title");

        Button backBtn = new Button("Back");
        backBtn.getStyleClass().add("back-btn");
        backBtn.setOnAction(e -> onBack.run());

        HBox header = new HBox(8, title, backBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(12, 16, 8, 16));
        header.getStyleClass().add("header");
        return header;
    }

    private Button createDebugButton(String text, Runnable action) {
        Button btn = new Button(text);
        btn.getStyleClass().add("debug-button");
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setOnAction(e -> action.run());
        return btn;
    }

    private void dumpAllNotes() {
        LocalCacheService localCache = ServiceManager.getInstance().getLocalCacheService();
        if (localCache == null || !localCache.isInitialized()) {
            updateStatus("LocalCache not ready");
            return;
        }
        var notes = localCache.getAllNotes();
        StringBuilder sb = new StringBuilder("=== Recent Notes ===\n");
        int count = 0;
        for (var note : notes) {
            if (count++ >= 20) break;
            String preview = note.content != null ? note.content.substring(0, Math.min(50, note.content.length())) : "";
            sb.append("ID: ").append(note.id).append(", Content: ").append(preview).append("...\n");
        }
        updateStatus(sb.toString());
        System.out.println(sb);
    }

    private void resetSyncState() {
        LocalCacheService localCache = ServiceManager.getInstance().getLocalCacheService();
        if (localCache == null || !localCache.isInitialized()) {
            updateStatus("LocalCache not ready");
            return;
        }
        localCache.resetSyncState();
        updateStatus("Sync state reset to initial");
        System.out.println("=== SYNC STATE RESET ===");
    }

    private void checkDbCount() {
        LocalCacheService localCache = ServiceManager.getInstance().getLocalCacheService();
        if (localCache == null || !localCache.isInitialized()) {
            updateStatus("LocalCache not ready");
            return;
        }
        int count = localCache.getLocalNoteCount();
        long lastSyncId = localCache.getLastSyncId();
        String lastSync = localCache.getLastSyncTime();
        String msg = "DB has " + count + " notes\nLast sync ID: " + lastSyncId + "\nLast sync time: " + (lastSync != null ? lastSync : "Never");
        updateStatus(msg);
        System.out.println("=== DEBUG: " + msg + " ===");
    }

    private void clearAllNotes() {
        LocalCacheService localCache = ServiceManager.getInstance().getLocalCacheService();
        if (localCache == null || !localCache.isInitialized()) {
            updateStatus("LocalCache not ready");
            return;
        }
        localCache.clearAllData();
        updateStatus("All notes cleared");
        System.out.println("=== ALL NOTES CLEARED ===");
    }

    private void testWebSocket() {
        WebSocketClientService wsService = ServiceManager.getInstance().getWebSocketService();
        if (wsService == null) {
            updateStatus("WebSocket service not available");
            return;
        }
        
        boolean connected = wsService.isConnected();
        updateStatus("WebSocket state: " + (connected ? "CONNECTED" : "DISCONNECTED"));
        
        if (!connected) {
            wsService.connect();
            updateStatus("Attempting to connect...");
        } else {
            // Force reconnect to trigger sync
            wsService.disconnect();
            updateStatus("Disconnected, reconnecting in 1s...");
            new Thread(() -> {
                try {
                    Thread.sleep(1000);
                    wsService.connect();
                    javafx.application.Platform.runLater(() -> updateStatus("Reconnecting..."));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }
    }

    private void updateStatus(String message) {
        javafx.application.Platform.runLater(() -> statusLabel.setText(message));
    }
}
