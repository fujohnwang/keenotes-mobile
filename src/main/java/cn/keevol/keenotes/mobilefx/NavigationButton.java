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
        // Style the icon (for PNG ImageView, we'll handle opacity via CSS)
        if (iconNode instanceof javafx.scene.shape.SVGPath) {
            javafx.scene.shape.SVGPath svgIcon = (javafx.scene.shape.SVGPath) iconNode;
            svgIcon.setFill(Color.web("#8B949E")); // Default gray
            svgIcon.setScaleX(0.8);
            svgIcon.setScaleY(0.8);
        } else if (iconNode instanceof javafx.scene.image.ImageView) {
            // For PNG icons, set default opacity
            iconNode.setOpacity(0.6);
        }
        
        Label textLabel = new Label(text);
        textLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #8B949E;"); // Muted gray by default
        
        HBox content = new HBox(12, iconNode, textLabel);
        content.setAlignment(Pos.CENTER_LEFT);
        content.setPadding(new Insets(10, 16, 10, 16)); // Increased horizontal padding
        
        setGraphic(content);
        setMaxWidth(Double.MAX_VALUE);
        getStyleClass().add("navigation-button");
        
        // Add left/right margins for floating pill effect
        HBox.setMargin(this, new Insets(0, 12, 0, 12));
        
        // Hover effect
        setOnMouseEntered(e -> {
            if (!selected) {
                if (iconNode instanceof javafx.scene.shape.SVGPath) {
                    ((javafx.scene.shape.SVGPath) iconNode).setFill(Color.web("#E6EDF3"));
                } else if (iconNode instanceof javafx.scene.image.ImageView) {
                    iconNode.setOpacity(1.0);
                }
                // Update text color on hover
                textLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #E6EDF3;");
            }
        });
        
        setOnMouseExited(e -> {
            if (!selected) {
                if (iconNode instanceof javafx.scene.shape.SVGPath) {
                    ((javafx.scene.shape.SVGPath) iconNode).setFill(Color.web("#8B949E"));
                } else if (iconNode instanceof javafx.scene.image.ImageView) {
                    iconNode.setOpacity(0.6);
                }
                // Restore muted text color
                textLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #8B949E;");
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
            // Floating pill style: rounded background with low opacity, no left border
            setStyle("-fx-background-color: rgba(6, 182, 212, 0.15); " +
                   "-fx-background-radius: 10px;");
            
            // Update icon color/opacity
            if (iconNode instanceof javafx.scene.shape.SVGPath) {
                ((javafx.scene.shape.SVGPath) iconNode).setFill(Color.web("#22D3EE")); // Bright cyan
            } else if (iconNode instanceof javafx.scene.image.ImageView) {
                iconNode.setOpacity(1.0);
            }
            
            // Update text color to bright cyan
            if (getGraphic() instanceof HBox) {
                HBox content = (HBox) getGraphic();
                if (content.getChildren().size() > 1 && content.getChildren().get(1) instanceof Label) {
                    Label textLabel = (Label) content.getChildren().get(1);
                    textLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #22D3EE; -fx-font-weight: 600;");
                }
            }
        } else {
            setStyle("");
            
            // Reset icon color/opacity
            if (iconNode instanceof javafx.scene.shape.SVGPath) {
                ((javafx.scene.shape.SVGPath) iconNode).setFill(Color.web("#8B949E"));
            } else if (iconNode instanceof javafx.scene.image.ImageView) {
                iconNode.setOpacity(0.6);
            }
            
            // Reset text color to muted gray
            if (getGraphic() instanceof HBox) {
                HBox content = (HBox) getGraphic();
                if (content.getChildren().size() > 1 && content.getChildren().get(1) instanceof Label) {
                    Label textLabel = (Label) content.getChildren().get(1);
                    textLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #8B949E;");
                }
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
