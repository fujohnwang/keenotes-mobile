package cn.keevol.keenotes.mobilefx;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/**
 * Bottom status bar showing connection status
 */
public class StatusFooterBar extends HBox {
    
    private final Circle sendChannelIndicator;
    private final Label sendChannelLabel;
    private final Circle syncChannelIndicator;
    private final Label syncChannelLabel;
    private final Label importStatusLabel;
    
    public StatusFooterBar() {
        getStyleClass().add("status-footer-bar");
        setPadding(new Insets(8, 16, 8, 16));
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(24);
        setMinHeight(36);
        setPrefHeight(36);
        setMaxHeight(36);
        
        // Send Channel status
        sendChannelIndicator = new Circle(4);
        sendChannelIndicator.setFill(Color.web("#4CAF50")); // Green by default
        
        sendChannelLabel = new Label("Send Channel: ✓");
        sendChannelLabel.getStyleClass().add("status-label");
        
        HBox sendChannel = new HBox(6, sendChannelIndicator, sendChannelLabel);
        sendChannel.setAlignment(Pos.CENTER_LEFT);
        
        // Spacer
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        
        // Sync Channel status
        syncChannelIndicator = new Circle(4);
        syncChannelIndicator.setFill(Color.web("#4CAF50")); // Green by default
        
        syncChannelLabel = new Label("Sync Channel: ✓");
        syncChannelLabel.getStyleClass().add("status-label");
        
        HBox syncChannel = new HBox(6, syncChannelIndicator, syncChannelLabel);
        syncChannel.setAlignment(Pos.CENTER_LEFT);
        
        // Import status (initially hidden)
        importStatusLabel = new Label("");
        importStatusLabel.getStyleClass().add("status-label");
        importStatusLabel.setVisible(false);
        importStatusLabel.setManaged(false);
        
        getChildren().addAll(sendChannel, spacer, syncChannel, importStatusLabel);
        
        startStatusMonitoring();
    }
    
    /**
     * Start monitoring connection status
     */
    private void startStatusMonitoring() {
        // TODO: Implement actual status monitoring
        // For now, just show connected status
        updateSendChannelStatus(true, "✓");
        updateSyncChannelStatus(true, "✓");
    }
    
    /**
     * Update send channel status
     */
    public void updateSendChannelStatus(boolean connected, String text) {
        sendChannelIndicator.setFill(connected ? 
            Color.web("#4CAF50") : Color.web("#F44336"));
        sendChannelLabel.setText("Send Channel: " + text);
        sendChannelLabel.setStyle(connected ? 
            "-fx-text-fill: #4CAF50;" : "-fx-text-fill: #F44336;");
    }
    
    /**
     * Update sync channel status
     */
    public void updateSyncChannelStatus(boolean connected, String text) {
        syncChannelIndicator.setFill(connected ? 
            Color.web("#4CAF50") : Color.web("#F44336"));
        syncChannelLabel.setText("Sync Channel: " + text);
        syncChannelLabel.setStyle(connected ? 
            "-fx-text-fill: #4CAF50;" : "-fx-text-fill: #F44336;");
    }
    
    /**
     * Set import status
     */
    public void setImportStatus(String text, boolean inProgress) {
        if (text == null || text.isEmpty()) {
            importStatusLabel.setVisible(false);
            importStatusLabel.setManaged(false);
        } else {
            importStatusLabel.setText("Import: " + text);
            importStatusLabel.setStyle(inProgress ? 
                "-fx-text-fill: #FF9800;" : "-fx-text-fill: #4CAF50;");
            importStatusLabel.setVisible(true);
            importStatusLabel.setManaged(true);
            
            // Auto-hide after 5 seconds if not in progress
            if (!inProgress) {
                javafx.application.Platform.runLater(() -> {
                    try {
                        Thread.sleep(5000);
                        javafx.application.Platform.runLater(() -> {
                            importStatusLabel.setVisible(false);
                            importStatusLabel.setManaged(false);
                        });
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
        }
    }
}
