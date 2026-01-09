package cn.keevol.keenotes.mobilefx;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/**
 * 状态栏组件 - 显示发送通道和同步通道的独立状态
 */
public class StatusFooter extends HBox {
    
    /**
     * 连接状态枚举
     */
    public enum ConnectionState {
        CONNECTED("Connected", "#4CAF50"),      // Green
        DISCONNECTED("Disconnected", "#9E9E9E"), // Gray
        CONNECTING("Connecting...", "#FFC107");  // Yellow/Amber
        
        private final String text;
        private final String color;
        
        ConnectionState(String text, String color) {
            this.text = text;
            this.color = color;
        }
        
        public String getText() { return text; }
        public String getColor() { return color; }
    }
    
    /**
     * 发送通道状态枚举
     */
    public enum SendChannelState {
        READY("Ready", "#4CAF50"),              // Green
        NOT_CONFIGURED("Not Configured", "#FFC107"), // Amber
        NO_NETWORK("No Network", "#F44336");    // Red
        
        private final String text;
        private final String color;
        
        SendChannelState(String text, String color) {
            this.text = text;
            this.color = color;
        }
        
        public String getText() { return text; }
        public String getColor() { return color; }
    }
    
    // Send Channel (HTTP API) components
    private final Circle sendIndicator;
    private final Label sendLabel;
    private final Label sendStatusLabel;
    
    // Sync Channel (WebSocket) components
    private final Circle syncIndicator;
    private final Label syncLabel;
    private final Label syncStatusLabel;
    
    private ConnectionState currentSyncState = ConnectionState.DISCONNECTED;
    private SendChannelState currentSendState = SendChannelState.NOT_CONFIGURED;
    
    public StatusFooter() {
        // 设置布局
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(8, 16, 8, 16));
        setSpacing(12);
        getStyleClass().add("status-footer");
        
        // === Send Channel (Left) ===
        HBox sendChannel = new HBox(6);
        sendChannel.setAlignment(Pos.CENTER_LEFT);
        
        sendIndicator = new Circle(4);
        sendIndicator.getStyleClass().add("status-indicator");
        
        sendLabel = new Label("Send Channel:");
        sendLabel.getStyleClass().add("status-label-title");
        sendLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666666;");
        
        sendStatusLabel = new Label();
        sendStatusLabel.getStyleClass().add("status-label");
        sendStatusLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");
        
        sendChannel.getChildren().addAll(sendIndicator, sendLabel, sendStatusLabel);
        
        // === Spacer ===
        Region spacer = new Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        
        // === Sync Channel (Right) ===
        HBox syncChannel = new HBox(6);
        syncChannel.setAlignment(Pos.CENTER_LEFT);
        
        syncIndicator = new Circle(4);
        syncIndicator.getStyleClass().add("status-indicator");
        
        syncLabel = new Label("Sync Channel:");
        syncLabel.getStyleClass().add("status-label-title");
        syncLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666666;");
        
        syncStatusLabel = new Label();
        syncStatusLabel.getStyleClass().add("status-label");
        syncStatusLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");
        
        syncChannel.getChildren().addAll(syncIndicator, syncLabel, syncStatusLabel);
        
        // 添加到主布局
        getChildren().addAll(sendChannel, spacer, syncChannel);
        
        // 设置初始状态
        setSendChannelState(SendChannelState.NOT_CONFIGURED);
        setSyncChannelState(ConnectionState.DISCONNECTED);
    }
    
    /**
     * 设置发送通道状态
     */
    public void setSendChannelState(SendChannelState state) {
        this.currentSendState = state;
        sendIndicator.setFill(Color.web(state.getColor()));
        sendStatusLabel.setText(state.getText());
        sendStatusLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: " + state.getColor() + ";");
    }
    
    /**
     * 设置同步通道状态
     */
    public void setSyncChannelState(ConnectionState state) {
        this.currentSyncState = state;
        syncIndicator.setFill(Color.web(state.getColor()));
        syncStatusLabel.setText(state.getText());
        syncStatusLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: " + state.getColor() + ";");
    }
    
    /**
     * 设置同步通道状态（兼容旧API）
     */
    public void setConnectionState(ConnectionState state) {
        setSyncChannelState(state);
    }
    
    /**
     * 获取当前同步通道状态
     */
    public ConnectionState getConnectionState() {
        return currentSyncState;
    }
    
    /**
     * 设置同步通道自定义消息（如 "Syncing..."）
     */
    public void setSyncStatusMessage(String message) {
        syncStatusLabel.setText(message);
    }
    
    /**
     * 设置同步通道自定义消息和颜色
     */
    public void setSyncStatusMessage(String message, String colorHex) {
        syncStatusLabel.setText(message);
        syncStatusLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: " + colorHex + ";");
        syncIndicator.setFill(Color.web(colorHex));
    }
    
    /**
     * 兼容旧API
     */
    @Deprecated
    public void setStatusMessage(String message) {
        setSyncStatusMessage(message);
    }
    
    /**
     * 兼容旧API
     */
    @Deprecated
    public void setStatusMessage(String message, String colorHex) {
        setSyncStatusMessage(message, colorHex);
    }
}
