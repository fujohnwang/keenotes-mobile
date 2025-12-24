package cn.keevol.keenotes.mobilefx;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Priority;

import java.sql.SQLException;

/**
 * Debug view for development and troubleshooting.
 * Contains all debug-related functionality.
 */
public class DebugView extends VBox {

    public DebugView(Runnable onBack) {
        getStyleClass().add("debug-view");

        // Debug buttons container
        VBox debugContainer = new VBox(12);
        debugContainer.setPadding(new Insets(16));
        debugContainer.getStyleClass().add("debug-container");

        // Add all debug buttons
        debugContainer.getChildren().addAll(
            createDebugButton("Dump All Notes", this::dumpAllNotes),
            createDebugButton("Reset Sync State", this::resetSyncState),
            createDebugButton("Check DB Count", this::checkDbCount),
            createDebugButton("Clear All Notes", this::clearAllNotes)
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
            System.out.println("=== DEBUG: LocalCache not ready ===");
            return;
        }
        System.out.println("=== DEBUG: DUMPING ALL NOTES ===");
        localCache.getAllNotes();
        System.out.println("=== DEBUG: DUMP COMPLETE ===");
    }

    private void resetSyncState() {
        LocalCacheService localCache = ServiceManager.getInstance().getLocalCacheService();
        if (localCache == null || !localCache.isInitialized()) {
            System.out.println("=== DEBUG: LocalCache not ready ===");
            return;
        }
        try {
            localCache.resetSyncState();
            System.out.println("=== SYNC STATE RESET - Restart app to re-sync ===");
        } catch (SQLException ex) {
            System.err.println("Failed to reset sync: " + ex.getMessage());
        }
    }

    private void checkDbCount() {
        LocalCacheService localCache = ServiceManager.getInstance().getLocalCacheService();
        if (localCache == null || !localCache.isInitialized()) {
            System.out.println("=== DEBUG: LocalCache not ready ===");
            return;
        }
        int count = localCache.getLocalNoteCount();
        String lastSync = localCache.getLastSyncTime();
        System.out.println("=== DEBUG: Database has " + count + " notes ===");
        System.out.println("=== DEBUG: Last sync: " + (lastSync != null ? lastSync : "Never") + " ===");
    }

    private void clearAllNotes() {
        LocalCacheService localCache = ServiceManager.getInstance().getLocalCacheService();
        if (localCache == null || !localCache.isInitialized()) {
            System.out.println("=== DEBUG: LocalCache not ready ===");
            return;
        }
        try {
            localCache.resetSyncState();
            System.out.println("=== ALL NOTES CLEARED ===");
        } catch (SQLException ex) {
            System.err.println("Failed to clear notes: " + ex.getMessage());
        }
    }
}
