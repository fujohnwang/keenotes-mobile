package cn.keevol.keenotes.mobilefx;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

/**
 * Navigation button for sidebar
 */
public class NavigationButton extends Button {
    
    private final String icon;
    private final String text;
    private boolean selected;
    
    public NavigationButton(String text, String icon, boolean selected) {
        this.text = text;
        this.icon = icon;
        this.selected = selected;
        
        setupButton();
        updateStyle();
    }
    
    private void setupButton() {
        // Create content
        Label iconLabel = new Label(icon);
        iconLabel.setStyle("-fx-font-size: 18px;");
        
        Label textLabel = new Label(text);
        textLabel.setStyle("-fx-font-size: 14px;");
        
        HBox content = new HBox(12, iconLabel, textLabel);
        content.setAlignment(Pos.CENTER_LEFT);
        content.setPadding(new Insets(8, 12, 8, 12));
        
        setGraphic(content);
        setMaxWidth(Double.MAX_VALUE);
        getStyleClass().add("navigation-button");
        
        // Hover effect
        setOnMouseEntered(e -> {
            if (!selected) {
                setStyle("-fx-background-color: rgba(0, 212, 255, 0.1);");
            }
        });
        
        setOnMouseExited(e -> {
            if (!selected) {
                setStyle("");
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
        } else {
            setStyle("");
        }
    }
    
    /**
     * Check if button is selected
     */
    public boolean isSelected() {
        return selected;
    }
}
