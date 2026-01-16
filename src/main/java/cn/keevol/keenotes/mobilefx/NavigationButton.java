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
    private Label textLabel; // Store reference for theme updates
    
    public NavigationButton(String text, Node iconNode, boolean selected) {
        this.text = text;
        this.iconNode = iconNode;
        this.selected = selected;
        
        setupButton();
        updateStyle();
        
        // Listen to theme changes
        ThemeService.getInstance().currentThemeProperty().addListener((obs, oldTheme, newTheme) -> {
            javafx.application.Platform.runLater(this::updateStyle);
        });
    }
    
    private void setupButton() {
        // Get theme colors
        boolean isDark = ThemeService.getInstance().isDarkTheme();
        String mutedColor = isDark ? "#8B949E" : "#57606A";
        
        // Style the icon (for PNG ImageView, we'll handle opacity via CSS)
        if (iconNode != null) {
            if (iconNode instanceof javafx.scene.shape.SVGPath) {
                javafx.scene.shape.SVGPath svgIcon = (javafx.scene.shape.SVGPath) iconNode;
                svgIcon.setFill(Color.web(mutedColor)); // Default gray
                svgIcon.setScaleX(0.8);
                svgIcon.setScaleY(0.8);
            } else if (iconNode instanceof javafx.scene.image.ImageView) {
                // For PNG icons, set default opacity
                iconNode.setOpacity(0.6);
            }
        }
        
        textLabel = new Label(text);
        textLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: " + mutedColor + ";"); // Muted gray by default
        
        HBox content;
        if (iconNode != null) {
            content = new HBox(12, iconNode, textLabel);
            content.setAlignment(Pos.CENTER_LEFT);
        } else {
            // No icon - center the text
            content = new HBox(textLabel);
            content.setAlignment(Pos.CENTER);
        }
        content.setPadding(new Insets(10, 16, 10, 16)); // Increased horizontal padding
        
        setGraphic(content);
        setMaxWidth(Double.MAX_VALUE);
        getStyleClass().add("navigation-button");
        
        // Add left/right margins for floating pill effect
        HBox.setMargin(this, new Insets(0, 12, 0, 12));
        
        // Hover effect
        setOnMouseEntered(e -> {
            if (!selected) {
                boolean isDarkHover = ThemeService.getInstance().isDarkTheme();
                String hoverColor = isDarkHover ? "#E6EDF3" : "#24292F";
                
                if (iconNode != null) {
                    if (iconNode instanceof javafx.scene.shape.SVGPath) {
                        ((javafx.scene.shape.SVGPath) iconNode).setFill(Color.web(hoverColor));
                    } else if (iconNode instanceof javafx.scene.image.ImageView) {
                        iconNode.setOpacity(1.0);
                    }
                }
                // Update text color on hover
                textLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: " + hoverColor + ";");
            }
        });
        
        setOnMouseExited(e -> {
            if (!selected) {
                boolean isDarkExit = ThemeService.getInstance().isDarkTheme();
                String mutedColorExit = isDarkExit ? "#8B949E" : "#57606A";
                
                if (iconNode != null) {
                    if (iconNode instanceof javafx.scene.shape.SVGPath) {
                        ((javafx.scene.shape.SVGPath) iconNode).setFill(Color.web(mutedColorExit));
                    } else if (iconNode instanceof javafx.scene.image.ImageView) {
                        iconNode.setOpacity(0.6);
                    }
                }
                // Restore muted text color
                textLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: " + mutedColorExit + ";");
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
        boolean isDark = ThemeService.getInstance().isDarkTheme();
        String primaryColor = isDark ? "#22D3EE" : "#0969DA";
        String mutedColor = isDark ? "#8B949E" : "#57606A";
        String accentBg = isDark ? "rgba(6, 182, 212, 0.15)" : "rgba(9, 105, 218, 0.08)";
        
        if (selected) {
            // Floating pill style: rounded background with low opacity, no left border
            setStyle("-fx-background-color: " + accentBg + "; " +
                   "-fx-background-radius: 10px;");
            
            // Update icon color/opacity (if icon exists)
            if (iconNode != null) {
                if (iconNode instanceof javafx.scene.shape.SVGPath) {
                    ((javafx.scene.shape.SVGPath) iconNode).setFill(Color.web(primaryColor));
                } else if (iconNode instanceof javafx.scene.image.ImageView) {
                    iconNode.setOpacity(1.0);
                }
            }
            
            // Update text color to primary
            if (textLabel != null) {
                textLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: " + primaryColor + "; -fx-font-weight: 600;");
            }
        } else {
            setStyle("");
            
            // Reset icon color/opacity (if icon exists)
            if (iconNode != null) {
                if (iconNode instanceof javafx.scene.shape.SVGPath) {
                    ((javafx.scene.shape.SVGPath) iconNode).setFill(Color.web(mutedColor));
                } else if (iconNode instanceof javafx.scene.image.ImageView) {
                    iconNode.setOpacity(0.6);
                }
            }
            
            // Reset text color to muted
            if (textLabel != null) {
                textLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: " + mutedColor + ";");
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
