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
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polygon;
import javafx.stage.Popup;
import javafx.util.Duration;

/**
 * 轻量级 PopOver 组件，用于显示引导提示
 * 不依赖 ControlsFX，完全自定义实现
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
    
    private final StackPane root;
    private final VBox contentContainer;
    private final Label titleLabel;
    private final Pane contentPane;
    private final Polygon arrow;
    
    private ArrowLocation arrowLocation = ArrowLocation.LEFT_CENTER;
    private final double arrowSize = 12;
    private final double cornerRadius = 8;
    private final double spacing = 5; // 箭头与内容的间距
    
    public LightweightPopOver() {
        // 创建箭头
        arrow = createArrow();
        
        // 创建标题
        titleLabel = new Label();
        titleLabel.getStyleClass().add("popover-title");
        titleLabel.setStyle(
            "-fx-font-size: 14px;" +
            "-fx-font-weight: bold;" +
            "-fx-text-fill: -fx-text-primary;"
        );
        
        // 创建内容容器
        contentPane = new StackPane();
        contentPane.getStyleClass().add("popover-content");
        
        // 组合标题和内容
        contentContainer = new VBox(8);
        contentContainer.getChildren().addAll(titleLabel, contentPane);
        contentContainer.getStyleClass().add("popover-container");
        contentContainer.setStyle(
            "-fx-background-color: -fx-surface-elevated;" +
            "-fx-background-radius: " + cornerRadius + ";" +
            "-fx-padding: 16;" +
            "-fx-min-width: 200;" +
            "-fx-max-width: 350;"
        );
        
        // 添加阴影效果
        DropShadow shadow = new DropShadow();
        shadow.setColor(Color.rgb(0, 0, 0, 0.2));
        shadow.setRadius(10);
        shadow.setOffsetY(2);
        contentContainer.setEffect(shadow);
        
        // 组合箭头和内容
        root = new StackPane();
        root.getChildren().addAll(contentContainer, arrow);
        
        // 设置为 Popup 的内容
        getContent().add(root);
        
        // 默认设置 - 向导模式下不应该自动隐藏
        setAutoHide(false);  // 改为 false，防止点击外部时消失
        setHideOnEscape(true);  // 保留 ESC 键关闭功能
    }
    
    /**
     * 创建箭头形状
     */
    private Polygon createArrow() {
        Polygon arrow = new Polygon();
        // 默认指向左侧的箭头（三角形）
        arrow.getPoints().addAll(
            0.0, 0.0,
            arrowSize, arrowSize / 2,
            0.0, arrowSize
        );
        // 箭头颜色使用 CSS 变量
        arrow.setStyle("-fx-fill: -fx-surface-elevated;");
        
        // 箭头阴影
        DropShadow arrowShadow = new DropShadow();
        arrowShadow.setColor(Color.rgb(0, 0, 0, 0.15));
        arrowShadow.setRadius(3);
        arrowShadow.setOffsetX(-1);
        arrow.setEffect(arrowShadow);
        
        return arrow;
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
        System.out.println("[LightweightPopOver] show() called");
        System.out.println("[LightweightPopOver] owner: " + owner);
        
        // 先显示以获取尺寸
        super.show(owner, 0, 0);
        
        System.out.println("[LightweightPopOver] Popup shown at (0, 0)");
        
        // 计算位置
        Bounds ownerBounds = owner.localToScreen(owner.getBoundsInLocal());
        if (ownerBounds == null) {
            System.err.println("[LightweightPopOver] ownerBounds is null!");
            return;
        }
        
        System.out.println("[LightweightPopOver] ownerBounds: " + ownerBounds);
        
        Point2D position = calculatePosition(ownerBounds);
        
        System.out.println("[LightweightPopOver] Calculated position: " + position);
        
        // 更新位置
        setX(position.getX());
        setY(position.getY());
        
        // 更新箭头位置
        updateArrowPosition();
        
        // 播放显示动画
        playShowAnimation();
        
        System.out.println("[LightweightPopOver] PopOver fully displayed");
    }
    
    /**
     * 计算 PopOver 位置
     */
    private Point2D calculatePosition(Bounds ownerBounds) {
        double x = 0, y = 0;
        double totalSpacing = arrowSize + spacing;
        
        Bounds contentBounds = contentContainer.getBoundsInLocal();
        
        switch (arrowLocation) {
            case LEFT_TOP:
                x = ownerBounds.getMaxX() + totalSpacing;
                y = ownerBounds.getMinY();
                break;
            case LEFT_CENTER:
                x = ownerBounds.getMaxX() + totalSpacing;
                y = ownerBounds.getMinY() + (ownerBounds.getHeight() - contentBounds.getHeight()) / 2;
                break;
            case LEFT_BOTTOM:
                x = ownerBounds.getMaxX() + totalSpacing;
                y = ownerBounds.getMaxY() - contentBounds.getHeight();
                break;
            case RIGHT_TOP:
                x = ownerBounds.getMinX() - contentBounds.getWidth() - totalSpacing;
                y = ownerBounds.getMinY();
                break;
            case RIGHT_CENTER:
                x = ownerBounds.getMinX() - contentBounds.getWidth() - totalSpacing;
                y = ownerBounds.getMinY() + (ownerBounds.getHeight() - contentBounds.getHeight()) / 2;
                break;
            case RIGHT_BOTTOM:
                x = ownerBounds.getMinX() - contentBounds.getWidth() - totalSpacing;
                y = ownerBounds.getMaxY() - contentBounds.getHeight();
                break;
            case TOP_LEFT:
                x = ownerBounds.getMinX();
                y = ownerBounds.getMaxY() + totalSpacing;
                break;
            case TOP_CENTER:
                x = ownerBounds.getMinX() + (ownerBounds.getWidth() - contentBounds.getWidth()) / 2;
                y = ownerBounds.getMaxY() + totalSpacing;
                break;
            case TOP_RIGHT:
                x = ownerBounds.getMaxX() - contentBounds.getWidth();
                y = ownerBounds.getMaxY() + totalSpacing;
                break;
            case BOTTOM_LEFT:
                x = ownerBounds.getMinX();
                y = ownerBounds.getMinY() - contentBounds.getHeight() - totalSpacing;
                break;
            case BOTTOM_CENTER:
                x = ownerBounds.getMinX() + (ownerBounds.getWidth() - contentBounds.getWidth()) / 2;
                y = ownerBounds.getMinY() - contentBounds.getHeight() - totalSpacing;
                break;
            case BOTTOM_RIGHT:
                x = ownerBounds.getMaxX() - contentBounds.getWidth();
                y = ownerBounds.getMinY() - contentBounds.getHeight() - totalSpacing;
                break;
        }
        
        return new Point2D(x, y);
    }
    
    /**
     * 更新箭头位置和旋转
     */
    private void updateArrowPosition() {
        Bounds contentBounds = contentContainer.getBoundsInLocal();
        
        // 重置箭头变换
        arrow.getTransforms().clear();
        arrow.setRotate(0);
        
        switch (arrowLocation) {
            case LEFT_TOP:
            case LEFT_CENTER:
            case LEFT_BOTTOM:
                // 箭头指向左侧
                arrow.setLayoutX(-arrowSize);
                if (arrowLocation == ArrowLocation.LEFT_TOP) {
                    arrow.setLayoutY(20);
                } else if (arrowLocation == ArrowLocation.LEFT_CENTER) {
                    arrow.setLayoutY(contentBounds.getHeight() / 2 - arrowSize / 2);
                } else {
                    arrow.setLayoutY(contentBounds.getHeight() - 20 - arrowSize);
                }
                arrow.setRotate(0);
                break;
                
            case RIGHT_TOP:
            case RIGHT_CENTER:
            case RIGHT_BOTTOM:
                // 箭头指向右侧
                arrow.setLayoutX(contentBounds.getWidth());
                if (arrowLocation == ArrowLocation.RIGHT_TOP) {
                    arrow.setLayoutY(20);
                } else if (arrowLocation == ArrowLocation.RIGHT_CENTER) {
                    arrow.setLayoutY(contentBounds.getHeight() / 2 - arrowSize / 2);
                } else {
                    arrow.setLayoutY(contentBounds.getHeight() - 20 - arrowSize);
                }
                arrow.setRotate(180);
                break;
                
            case TOP_LEFT:
            case TOP_CENTER:
            case TOP_RIGHT:
                // 箭头指向上方
                if (arrowLocation == ArrowLocation.TOP_LEFT) {
                    arrow.setLayoutX(20);
                } else if (arrowLocation == ArrowLocation.TOP_CENTER) {
                    arrow.setLayoutX(contentBounds.getWidth() / 2 - arrowSize / 2);
                } else {
                    arrow.setLayoutX(contentBounds.getWidth() - 20 - arrowSize);
                }
                arrow.setLayoutY(-arrowSize);
                arrow.setRotate(-90);
                break;
                
            case BOTTOM_LEFT:
            case BOTTOM_CENTER:
            case BOTTOM_RIGHT:
                // 箭头指向下方
                if (arrowLocation == ArrowLocation.BOTTOM_LEFT) {
                    arrow.setLayoutX(20);
                } else if (arrowLocation == ArrowLocation.BOTTOM_CENTER) {
                    arrow.setLayoutX(contentBounds.getWidth() / 2 - arrowSize / 2);
                } else {
                    arrow.setLayoutX(contentBounds.getWidth() - 20 - arrowSize);
                }
                arrow.setLayoutY(contentBounds.getHeight());
                arrow.setRotate(90);
                break;
        }
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
     * 设置是否可分离（简化版本不支持）
     */
    public void setDetachable(boolean detachable) {
        // 简化版本不支持 detach 功能
    }
    
    /**
     * 重新定位 PopOver（用于窗口大小变化时）
     */
    public void reposition(Node owner) {
        if (!isShowing()) {
            return;
        }
        
        // 计算新位置
        Bounds ownerBounds = owner.localToScreen(owner.getBoundsInLocal());
        if (ownerBounds == null) {
            return;
        }
        
        Point2D position = calculatePosition(ownerBounds);
        
        // 更新位置
        setX(position.getX());
        setY(position.getY());
        
        // 更新箭头位置
        updateArrowPosition();
    }
}
