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
        
        getChildren().addAll(sendChannel, spacer, syncChannel);
        
        // TODO: Connect to actual service status
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
}
