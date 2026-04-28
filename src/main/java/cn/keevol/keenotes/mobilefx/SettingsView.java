package cn.keevol.keenotes.mobilefx;

import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;

/**
 * Settings view container that switches between General, Preferences, and Data Import
 */
public class SettingsView extends BorderPane {

    private final Runnable onBack;
    private final StackPane contentArea;

    // Sub views
    private VBox generalView;
    private VBox preferencesView;
    private VBox aiView;
    private VBox shareView;
    private DataImportView dataImportView;
    private VBox debugView;

    private VBox currentView;

    public SettingsView(Runnable onBack) {
        this.onBack = onBack;
        getStyleClass().add("main-view");

        getStyleClass().add("main-view");

        // Content area (stack pane for view switching)
        contentArea = new StackPane();

        // Create sub views
        generalView = new SettingsGeneralView(this::handleSaveSuccess);
        preferencesView = new SettingsPreferencesView();
        aiView = new AIView();
        shareView = new SettingsShareView();
        dataImportView = new DataImportView();
        debugView = createDebugView();

        // Add all views to content area
        contentArea.getChildren().addAll(generalView, preferencesView, aiView, dataImportView, shareView, debugView);

        // Initially show general view
        generalView.setVisible(true);
        preferencesView.setVisible(false);
        aiView.setVisible(false);
        shareView.setVisible(false);
        dataImportView.setVisible(false);
        debugView.setVisible(false);

        currentView = generalView;

        ScrollPane scrollPane = new ScrollPane(contentArea);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("content-scroll");
        setCenter(scrollPane);

        // Footer
        setBottom(createFooter());
    }

    /**
     * Release sub-view resources. Call when settings is no longer needed.
     */
    public void dispose() {
        dataImportView.dispose();
    }

    /**
     * Switch to a sub view
     */
    public void showSubView(String subView) {
        VBox targetView = switch (subView) {
            case "General" -> generalView;
            case "Preferences" -> preferencesView;
            case "AI" -> aiView;
            case "Share" -> shareView;
            case "Data Import" -> dataImportView;
            default -> generalView;
        };

        if (targetView == currentView) {
            return;
        }

        // Fade out current view
        FadeTransition fadeOut = new FadeTransition(Duration.millis(150), currentView);
        fadeOut.setFromValue(1.0);
        fadeOut.setToValue(0.0);
        fadeOut.setOnFinished(e -> {
            currentView.setVisible(false);

            // Fade in target view
            targetView.setVisible(true);
            targetView.setOpacity(0.0);

            FadeTransition fadeIn = new FadeTransition(Duration.millis(150), targetView);
            fadeIn.setFromValue(0.0);
            fadeIn.setToValue(1.0);
            fadeIn.play();

            currentView = targetView;
        });
        fadeOut.play();
    }

    private VBox createFooter() {
        // Copyright footer
        Label copyrightLabel = new Label("©2025 王福强(Fuqiang Wang)  All Rights Reserved");
        copyrightLabel.getStyleClass().add("copyright-label");

        // Easter egg: click 7 times to show debug section
        final int[] clickCount = {0};
        final long[] lastClickTime = {0};
        copyrightLabel.setOnMouseClicked(e -> {
            long now = System.currentTimeMillis();
            if (now - lastClickTime[0] > 1000) {
                clickCount[0] = 0;
            }
            lastClickTime[0] = now;
            clickCount[0]++;

            if (clickCount[0] >= 7 && !debugView.isVisible()) {
                // Switch to debug view
                debugView.setVisible(true);
                debugView.setOpacity(0.0);

                currentView.setVisible(false);

                FadeTransition fadeIn = new FadeTransition(Duration.millis(150), debugView);
                fadeIn.setFromValue(0.0);
                fadeIn.setToValue(1.0);
                fadeIn.play();

                currentView = debugView;
            }
        });
        copyrightLabel.setStyle("-fx-cursor: hand;");

        Label websiteLabel = new Label("https://keenotes.afoo.me");
        websiteLabel.getStyleClass().add("copyright-link");

        VBox footer = new VBox(4, copyrightLabel, websiteLabel);
        footer.setAlignment(Pos.CENTER);
        footer.setPadding(new Insets(12, 0, 12, 0));
        footer.getStyleClass().add("footer-fixed");

        return footer;
    }

    private VBox createDebugView() {
        VBox debugSection = new VBox(12);
        debugSection.setAlignment(Pos.CENTER);
        debugSection.setPadding(new Insets(24));

        Label debugLabel = new Label("Debug Tools");
        debugLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: -fx-text-primary;");

        Button clearDataButton = new Button("Clear Local Notes & Reset Sync");
        clearDataButton.getStyleClass().addAll("action-button");
        clearDataButton.setMaxWidth(300);
        clearDataButton.setOnAction(e -> clearLocalData());

        Button diagnosticsButton = new Button("Copy Diagnostics Snapshot");
        diagnosticsButton.getStyleClass().addAll("action-button");
        diagnosticsButton.setMaxWidth(300);
        diagnosticsButton.setOnAction(e -> copyDiagnosticsSnapshot());

        Label statusLabel = new Label();
        statusLabel.getStyleClass().add("status-label");

        debugSection.getChildren().addAll(debugLabel, clearDataButton, diagnosticsButton, statusLabel);
        debugSection.setUserData(statusLabel); // Store reference for clearLocalData

        return debugSection;
    }

    private void clearLocalData() {
        Label statusLabel = (Label) debugView.getUserData();
        statusLabel.setText("Clearing local data...");
        statusLabel.getStyleClass().removeAll("error", "success");
        statusLabel.getStyleClass().add("success");

        new Thread(() -> {
            try {
                ServiceManager serviceManager = ServiceManager.getInstance();
                LocalCacheService cache = serviceManager.getLocalCacheService();

                cache.clearAllData();

                javafx.application.Platform.runLater(() -> {
                    statusLabel.setText("✓ Local data cleared! Reconnecting...");
                    statusLabel.getStyleClass().removeAll("error", "success");
                    statusLabel.getStyleClass().add("success");
                });

                Thread.sleep(500);
                WebSocketClientService webSocketService = serviceManager.getWebSocketService();
                webSocketService.disconnect();
                Thread.sleep(500);
                webSocketService.connect();

                javafx.application.Platform.runLater(() -> {
                    statusLabel.setText("✓ Data cleared and reconnected!");
                });

            } catch (Exception e) {
                javafx.application.Platform.runLater(() -> {
                    statusLabel.setText("✗ Failed to clear data: " + e.getMessage());
                    statusLabel.getStyleClass().removeAll("error", "success");
                    statusLabel.getStyleClass().add("error");
                });
            }
        }, "ClearLocalData").start();
    }

    private void copyDiagnosticsSnapshot() {
        Label statusLabel = (Label) debugView.getUserData();
        statusLabel.setText("Collecting diagnostics...");
        statusLabel.getStyleClass().removeAll("error", "success");
        statusLabel.getStyleClass().add("success");

        new Thread(() -> {
            try {
                ServiceManager serviceManager = ServiceManager.getInstance();
                LocalCacheService cache = serviceManager.getLocalCacheService();
                WebSocketClientService ws = serviceManager.getWebSocketService();

                StringBuilder snapshot = new StringBuilder();
                snapshot.append(cache.buildDiagnosticsSnapshot()).append(System.lineSeparator());
                snapshot.append("service.localCacheState=").append(serviceManager.getLocalCacheState())
                        .append(System.lineSeparator());
                snapshot.append("service.localCacheErrorMessage=").append(serviceManager.getLocalCacheErrorMessage())
                        .append(System.lineSeparator());
                snapshot.append("service.webSocketConnected=").append(serviceManager.isWebSocketConnected())
                        .append(System.lineSeparator());
                snapshot.append("ws.connected=").append(ws.isConnected()).append(System.lineSeparator());
                snapshot.append("ws.syncing=").append(ws.isSyncing()).append(System.lineSeparator());
                snapshot.append("ws.offline=").append(ws.isOffline()).append(System.lineSeparator());

                javafx.application.Platform.runLater(() -> {
                    javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
                    javafx.scene.input.ClipboardContent clipboardContent = new javafx.scene.input.ClipboardContent();
                    clipboardContent.putString(snapshot.toString());
                    clipboard.setContent(clipboardContent);

                    statusLabel.setText("✓ Diagnostics snapshot copied to clipboard");
                    statusLabel.getStyleClass().removeAll("error", "success");
                    statusLabel.getStyleClass().add("success");
                });
            } catch (Exception e) {
                javafx.application.Platform.runLater(() -> {
                    statusLabel.setText("✗ Failed to collect diagnostics: " + e.getMessage());
                    statusLabel.getStyleClass().removeAll("error", "success");
                    statusLabel.getStyleClass().add("error");
                });
            }
        }, "CopyDiagnosticsSnapshot").start();
    }

    private void handleSaveSuccess() {
        onBack.run();
    }
}
