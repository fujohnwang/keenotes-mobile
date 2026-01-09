package cn.keevol.keenotes.mobilefx;

import javafx.animation.TranslateTransition;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.control.Control;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/**
 * Custom Toggle Switch component similar to iOS/Android toggle
 */
public class ToggleSwitch extends Control {
    
    private final BooleanProperty selected = new SimpleBooleanProperty(false);
    private final Pane container;
    private final Rectangle background;
    private final Circle thumb;
    private final TranslateTransition transition;
    
    // Colors
    private static final Color COLOR_OFF = Color.web("#CCCCCC");
    private static final Color COLOR_ON = Color.web("#4CAF50");
    private static final Color THUMB_COLOR = Color.WHITE;
    
    // Dimensions
    private static final double WIDTH = 50;
    private static final double HEIGHT = 26;
    private static final double THUMB_RADIUS = 10;
    private static final double PADDING = 3;
    
    public ToggleSwitch() {
        container = new Pane();
        container.setPrefSize(WIDTH, HEIGHT);
        container.setMaxSize(WIDTH, HEIGHT);
        container.setMinSize(WIDTH, HEIGHT);
        
        // Background track
        background = new Rectangle(WIDTH, HEIGHT);
        background.setArcWidth(HEIGHT);
        background.setArcHeight(HEIGHT);
        background.setFill(COLOR_OFF);
        background.setStroke(Color.TRANSPARENT);
        
        // Thumb (circle)
        thumb = new Circle(THUMB_RADIUS);
        thumb.setFill(THUMB_COLOR);
        thumb.setStroke(Color.web("#DDDDDD"));
        thumb.setStrokeWidth(1);
        
        // Position thumb at OFF position initially
        thumb.setTranslateX(PADDING + THUMB_RADIUS);
        thumb.setTranslateY(HEIGHT / 2);
        
        // Animation for thumb movement
        transition = new TranslateTransition(Duration.millis(150), thumb);
        
        container.getChildren().addAll(background, thumb);
        getChildren().add(container);
        
        // Click handler
        container.setOnMouseClicked(event -> {
            setSelected(!isSelected());
        });
        
        // Hover effect
        container.setOnMouseEntered(event -> {
            container.setStyle("-fx-cursor: hand;");
        });
        
        container.setOnMouseExited(event -> {
            container.setStyle("-fx-cursor: default;");
        });
        
        // Listen to selected property changes
        selected.addListener((obs, oldVal, newVal) -> {
            updateVisuals(newVal);
        });
    }
    
    private void updateVisuals(boolean isSelected) {
        if (isSelected) {
            // Move thumb to ON position (right side)
            transition.setToX(WIDTH - PADDING - THUMB_RADIUS);
            background.setFill(COLOR_ON);
        } else {
            // Move thumb to OFF position (left side)
            transition.setToX(PADDING + THUMB_RADIUS);
            background.setFill(COLOR_OFF);
        }
        transition.play();
    }
    
    // Property accessors
    public BooleanProperty selectedProperty() {
        return selected;
    }
    
    public boolean isSelected() {
        return selected.get();
    }
    
    public void setSelected(boolean value) {
        selected.set(value);
    }
}
