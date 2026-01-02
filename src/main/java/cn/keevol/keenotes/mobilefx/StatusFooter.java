package cn.keevol.keenotes.mobilefx;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/**
 * 状态栏组件 - 显示 WebSocket 连接状态
 */
public class StatusFooter extends HBox {
    
    /**
     * 连接状态枚举
     */
    public enum ConnectionState {
        CONNECTED("Connected", "#4CAF50"),      // Green
        DISCONNECTED("Disconnected", "#F44336"), // Red
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
    
    private final Circle statusIndicator;
    private final Label statusLabel;
    private ConnectionState currentState = ConnectionState.DISCONNECTED;
    
    public StatusFooter() {
        // 设置布局
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(8, 16, 8, 16));
        setSpacing(8);
        getStyleClass().add("status-footer");
        
        // 创建状态指示器（圆点）
        statusIndicator = new Circle(5);
        statusIndicator.getStyleClass().add("status-indicator");
        
        // 创建状态文本
        statusLabel = new Label();
        statusLabel.getStyleClass().add("status-label");
        
        // 添加到布局
        getChildren().addAll(statusIndicator, statusLabel);
        
        // 设置初始状态
        setConnectionState(ConnectionState.DISCONNECTED);
    }
    
    /**
     * 设置连接状态
     */
    public void setConnectionState(ConnectionState state) {
        this.currentState = state;
        statusIndicator.setFill(Color.web(state.getColor()));
        statusLabel.setText(state.getText());
        
        // 更新样式类
        statusIndicator.getStyleClass().removeAll("connected", "disconnected", "connecting");
        statusLabel.getStyleClass().removeAll("connected", "disconnected", "connecting");
        
        String styleClass = state.name().toLowerCase();
        statusIndicator.getStyleClass().add(styleClass);
        statusLabel.getStyleClass().add(styleClass);
    }
    
    /**
     * 获取当前连接状态
     */
    public ConnectionState getConnectionState() {
        return currentState;
    }
    
    /**
     * 设置自定义状态消息
     */
    public void setStatusMessage(String message) {
        statusLabel.setText(message);
    }
    
    /**
     * 设置自定义状态消息和颜色
     */
    public void setStatusMessage(String message, String colorHex) {
        statusLabel.setText(message);
        statusIndicator.setFill(Color.web(colorHex));
    }
}
