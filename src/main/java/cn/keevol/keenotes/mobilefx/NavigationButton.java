package cn.keevol.keenotes.mobilefx;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;

/**
 * Navigation button for sidebar with SVG icon support
 */
public class NavigationButton extends Button {
    
    private final Node iconNode;
    private final String text;
    private boolean selected;
    
    public NavigationButton(String text, Node iconNode, boolean selected) {
        this.text = text;
        this.iconNode = iconNode;
        this.selected = selected;
        
        setupButton();
        updateStyle();
    }
    
    private void setupButton() {
        // Style the icon
        if (iconNode instanceof javafx.scene.shape.SVGPath) {
            javafx.scene.shape.SVGPath svgIcon = (javafx.scene.shape.SVGPath) iconNode;
            svgIcon.setFill(Color.web("#8B949E")); // Default gray
            svgIcon.setScaleX(0.8);
            svgIcon.setScaleY(0.8);
        }
        
        Label textLabel = new Label(text);
        textLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #E6EDF3;");
        
        HBox content = new HBox(12, iconNode, textLabel);
        content.setAlignment(Pos.CENTER_LEFT);
        content.setPadding(new Insets(10, 12, 10, 12));
        
        setGraphic(content);
        setMaxWidth(Double.MAX_VALUE);
        getStyleClass().add("navigation-button");
        
        // Hover effect
        setOnMouseEntered(e -> {
            if (!selected && iconNode instanceof javafx.scene.shape.SVGPath) {
                ((javafx.scene.shape.SVGPath) iconNode).setFill(Color.web("#00D4FF"));
            }
        });
        
        setOnMouseExited(e -> {
            if (!selected && iconNode instanceof javafx.scene.shape.SVGPath) {
                ((javafx.scene.shape.SVGPath) iconNode).setFill(Color.web("#8B949E"));
            }
        });
    }
    
    /**
     * Set selected state
     */
    public void setSelected(boolean selected) {
        this.selected = selected;
        updateStyle();
    }
    
    /**
     * Update button style based on selected state
     */
    private void updateStyle() {
        if (selected) {
            setStyle("-fx-background-color: rgba(0, 212, 255, 0.2); " +
                   "-fx-border-color: #00D4FF; " +
                   "-fx-border-width: 0 0 0 3;");
            if (iconNode instanceof javafx.scene.shape.SVGPath) {
                ((javafx.scene.shape.SVGPath) iconNode).setFill(Color.web("#00D4FF"));
            }
        } else {
            setStyle("");
            if (iconNode instanceof javafx.scene.shape.SVGPath) {
                ((javafx.scene.shape.SVGPath) iconNode).setFill(Color.web("#8B949E"));
            }
        }
    }
    
    /**
     * Check if button is selected
     */
    public boolean isSelected() {
        return selected;
    }
}
