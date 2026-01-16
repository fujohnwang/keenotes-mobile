package cn.keevol.keenotes.mobilefx;

import javafx.animation.TranslateTransition;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Pos;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/**
 * Custom Toggle Switch component similar to iOS/Android toggle
 */
public class ToggleSwitch extends StackPane {
    
    private final BooleanProperty selected = new SimpleBooleanProperty(false);
    private final Rectangle background;
    private final Circle thumb;
    private final TranslateTransition transition;
    
    private static final double WIDTH = 50;
    private static final double HEIGHT = 26;
    private static final double THUMB_RADIUS = 9; // Slightly smaller to fit inside track
    private static final double PADDING = 3; // Padding from edge
    
    public ToggleSwitch() {
        // Background track
        background = new Rectangle(WIDTH, HEIGHT);
        background.setArcWidth(HEIGHT);
        background.setArcHeight(HEIGHT);
        background.setFill(Color.web("#8B949E")); // Gray when off
        
        // Thumb (circle) - smaller and with padding
        thumb = new Circle(THUMB_RADIUS);
        thumb.setFill(Color.WHITE);
        
        // Calculate positions to keep thumb inside track
        // Left position: -WIDTH/2 + PADDING + THUMB_RADIUS
        // Right position: WIDTH/2 - PADDING - THUMB_RADIUS
        double leftPos = -WIDTH / 2 + PADDING + THUMB_RADIUS;
        double rightPos = WIDTH / 2 - PADDING - THUMB_RADIUS;
        
        thumb.setTranslateX(leftPos); // Start position (left)
        
        getChildren().addAll(background, thumb);
        setAlignment(Pos.CENTER);
        
        // Animation
        transition = new TranslateTransition(Duration.millis(200), thumb);
        
        // Click handler
        setOnMouseClicked(e -> setSelected(!isSelected()));
        setCursor(javafx.scene.Cursor.HAND);
        
        // Listen to selected property
        selected.addListener((obs, oldVal, newVal) -> {
            if (newVal) {
                // Move to right, change to blue
                transition.setToX(rightPos);
                background.setFill(Color.web("#00D4FF")); // Primary color
            } else {
                // Move to left, change to gray
                transition.setToX(leftPos);
                background.setFill(Color.web("#8B949E"));
            }
            transition.play();
        });
        
        // Set initial size
        setMinSize(WIDTH, HEIGHT);
        setMaxSize(WIDTH, HEIGHT);
        setPrefSize(WIDTH, HEIGHT);
    }
    
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
