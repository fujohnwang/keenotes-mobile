package cn.keevol.keenotes.mobilefx;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.stage.Popup;
import javafx.util.Duration;

/**
 * 轻量级 PopOver 组件，用于显示引导提示
 * 使用 SVG Path 创建带箭头的整体形状
 */
public class LightweightPopOver extends Popup {
    
    /**
     * 箭头位置枚举
     */
    public enum ArrowLocation {
        TOP_LEFT, TOP_CENTER, TOP_RIGHT,
        RIGHT_TOP, RIGHT_CENTER, RIGHT_BOTTOM,
        BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT,
        LEFT_TOP, LEFT_CENTER, LEFT_BOTTOM
    }
    
    private final Pane root;
    private final Region background;
    private final VBox contentContainer;
    private final Label titleLabel;
    private final Pane contentPane;
    
    private ArrowLocation arrowLocation = ArrowLocation.LEFT_CENTER;
    private final double arrowSize = 16;
    private final double cornerRadius = 8;
    
    public LightweightPopOver() {
        // 创建背景形状（带箭头）
        background = new Region();
        background.setStyle(
            "-fx-background-color: -fx-surface-elevated;" +
            "-fx-border-color: derive(-fx-primary, 50%);" + // 添加主题色边框
            "-fx-border-width: 2;"
        );
        
        // 创建标题
        titleLabel = new Label();
        titleLabel.setStyle(
            "-fx-font-size: 14px;" +
            "-fx-font-weight: bold;" +
            "-fx-text-fill: -fx-text-primary;"
        );
        
        // 创建内容容器
        contentPane = new StackPane();
        
        // 组合标题和内容
        contentContainer = new VBox(8);
        contentContainer.getChildren().addAll(titleLabel, contentPane);
        contentContainer.setStyle("-fx-padding: 16;");
        
        // 创建根容器
        root = new Pane();
        root.getChildren().addAll(background, contentContainer);
        
        // 增强阴影效果，让卡片更突出
        DropShadow shadow = new DropShadow();
        shadow.setColor(Color.rgb(0, 0, 0, 0.35)); // 增加不透明度，从 0.2 到 0.35
        shadow.setRadius(16); // 增大半径，从 10 到 16
        shadow.setOffsetY(4); // 增加偏移，从 2 到 4
        shadow.setSpread(0.1); // 添加扩散效果
        root.setEffect(shadow);
        
        // 设置为 Popup 的内容
        getContent().add(root);
        
        setAutoHide(false);
        setHideOnEscape(true);
    }
    
    /**
     * 设置标题
     */
    public void setTitle(String title) {
        titleLabel.setText(title);
        titleLabel.setVisible(title != null && !title.isEmpty());
        titleLabel.setManaged(titleLabel.isVisible());
    }
    
    /**
     * 设置内容节点
     */
    public void setContentNode(Node content) {
        contentPane.getChildren().clear();
        contentPane.getChildren().add(content);
    }
    
    /**
     * 设置箭头位置
     */
    public void setArrowLocation(ArrowLocation location) {
        this.arrowLocation = location;
    }
    
    /**
     * 显示 PopOver
     */
    public void show(Node owner) {
        // 先显示以获取尺寸
        super.show(owner, 0, 0);
        
        // 更新背景形状
        updateBackgroundShape();
        
        // 计算位置
        Bounds ownerBounds = owner.localToScreen(owner.getBoundsInLocal());
        if (ownerBounds == null) {
            return;
        }
        
        Point2D position = calculatePosition(ownerBounds);
        
        // 更新位置
        setX(position.getX());
        setY(position.getY());
        
        // 播放显示动画
        playShowAnimation();
    }
    
    /**
     * 更新背景形状（带箭头的圆角矩形）
     */
    private void updateBackgroundShape() {
        Bounds contentBounds = contentContainer.getBoundsInLocal();
        double width = contentBounds.getWidth();
        double height = contentBounds.getHeight();
        
        // 创建 SVG Path
        String svgPath = createArrowShapePath(width, height);
        
        SVGPath path = new SVGPath();
        path.setContent(svgPath);
        
        // 不设置 fill，让 CSS 样式控制颜色
        background.setShape(path);
        background.setMinSize(width + arrowSize, height);
        background.setMaxSize(width + arrowSize, height);
        
        // 调整内容容器位置（为箭头留出空间）
        if (arrowLocation == ArrowLocation.LEFT_TOP || 
            arrowLocation == ArrowLocation.LEFT_CENTER || 
            arrowLocation == ArrowLocation.LEFT_BOTTOM) {
            contentContainer.setLayoutX(arrowSize);
            contentContainer.setLayoutY(0);
        } else {
            contentContainer.setLayoutX(0);
            contentContainer.setLayoutY(0);
        }
    }
    
    /**
     * 创建带箭头的 SVG Path
     */
    private String createArrowShapePath(double width, double height) {
        double arrowY = height / 2;
        double arrowHeight = 20;
        
        StringBuilder path = new StringBuilder();
        
        // 从左上角开始（考虑箭头偏移）
        path.append("M ").append(arrowSize).append(" ").append(cornerRadius);
        
        // 左上角圆角
        path.append(" Q ").append(arrowSize).append(" 0 ");
        path.append(arrowSize + cornerRadius).append(" 0");
        
        // 顶边
        path.append(" L ").append(width + arrowSize - cornerRadius).append(" 0");
        
        // 右上角圆角
        path.append(" Q ").append(width + arrowSize).append(" 0 ");
        path.append(width + arrowSize).append(" ").append(cornerRadius);
        
        // 右边
        path.append(" L ").append(width + arrowSize).append(" ").append(height - cornerRadius);
        
        // 右下角圆角
        path.append(" Q ").append(width + arrowSize).append(" ").append(height).append(" ");
        path.append(width + arrowSize - cornerRadius).append(" ").append(height);
        
        // 底边
        path.append(" L ").append(arrowSize + cornerRadius).append(" ").append(height);
        
        // 左下角圆角
        path.append(" Q ").append(arrowSize).append(" ").append(height).append(" ");
        path.append(arrowSize).append(" ").append(height - cornerRadius);
        
        // 左边（到箭头下方）
        path.append(" L ").append(arrowSize).append(" ").append(arrowY + arrowHeight / 2);
        
        // 箭头（向左突出）
        path.append(" L 0 ").append(arrowY);
        path.append(" L ").append(arrowSize).append(" ").append(arrowY - arrowHeight / 2);
        
        // 左边（箭头上方到顶部）
        path.append(" L ").append(arrowSize).append(" ").append(cornerRadius);
        
        path.append(" Z");
        
        return path.toString();
    }
    
    /**
     * 计算 PopOver 位置
     */
    private Point2D calculatePosition(Bounds ownerBounds) {
        double x = 0, y = 0;
        Bounds contentBounds = contentContainer.getBoundsInLocal();
        
        x = ownerBounds.getMaxX() + arrowSize;
        y = ownerBounds.getMinY() + (ownerBounds.getHeight() - contentBounds.getHeight()) / 2;
        
        return new Point2D(x, y);
    }
    
    /**
     * 播放显示动画
     */
    private void playShowAnimation() {
        root.setOpacity(0);
        root.setScaleX(0.9);
        root.setScaleY(0.9);
        
        FadeTransition fade = new FadeTransition(Duration.millis(200), root);
        fade.setFromValue(0);
        fade.setToValue(1);
        
        ScaleTransition scale = new ScaleTransition(Duration.millis(200), root);
        scale.setFromX(0.9);
        scale.setFromY(0.9);
        scale.setToX(1.0);
        scale.setToY(1.0);
        
        ParallelTransition transition = new ParallelTransition(fade, scale);
        transition.play();
    }
    
    /**
     * 设置是否可分离
     */
    public void setDetachable(boolean detachable) {
        // 简化版本不支持
    }
    
    /**
     * 重新定位 PopOver
     */
    public void reposition(Node owner) {
        if (!isShowing()) {
            return;
        }
        
        Bounds ownerBounds = owner.localToScreen(owner.getBoundsInLocal());
        if (ownerBounds == null) {
            return;
        }
        
        Point2D position = calculatePosition(ownerBounds);
        setX(position.getX());
        setY(position.getY());
    }
}
