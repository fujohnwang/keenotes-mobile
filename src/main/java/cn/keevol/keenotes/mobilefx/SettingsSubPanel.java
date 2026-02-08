package cn.keevol.keenotes.mobilefx;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * Settings sub-navigation panel (same style as ReviewPeriodsPanel)
 */
public class SettingsSubPanel extends VBox {
    
    private final Consumer<String> onSubItemSelected;
    private Button selectedButton;
    
    public SettingsSubPanel(Consumer<String> onSubItemSelected) {
        this.onSubItemSelected = onSubItemSelected;
        
        getStyleClass().add("review-periods-panel"); // Reuse same style
        setPadding(new Insets(8, 0, 8, 12));
        setSpacing(6);
        
        // Listen to theme changes
        ThemeService.getInstance().currentThemeProperty().addListener((obs, oldTheme, newTheme) -> {
            javafx.application.Platform.runLater(this::updateThemeColors);
        });
        
        setupButtons();
    }
    
    private void setupButtons() {
        String[] items = {"General", "Preferences", "AI", "Data Import"};
        
        for (String item : items) {
            Button button = createSubButton(item);
            getChildren().add(button);
        }
        
        // Select first item by default
        if (!getChildren().isEmpty()) {
            selectButton((Button) getChildren().get(0));
        }
    }
    
    private Button createSubButton(String item) {
        Button button = new Button(item);
        button.getStyleClass().add("period-button"); // Reuse same style as Review
        button.setMaxWidth(Double.MAX_VALUE);
        
        button.setOnAction(e -> {
            selectButton(button);
            onSubItemSelected.accept(item);
        });
        
        // Hover effect (same as ReviewPeriodsPanel)
        button.setOnMouseEntered(e -> {
            if (button != selectedButton) {
                boolean isDark = ThemeService.getInstance().isDarkTheme();
                String hoverBg = isDark ? "rgba(0, 212, 255, 0.1)" : "rgba(9, 105, 218, 0.08)";
                button.setStyle("-fx-background-color: " + hoverBg + ";");
            }
        });
        
        button.setOnMouseExited(e -> {
            if (button != selectedButton) {
                button.setStyle("");
            }
        });
        
        return button;
    }
    
    private void selectButton(Button button) {
        // Deselect previous
        if (selectedButton != null) {
            selectedButton.setStyle("");
        }
        
        // Select new
        selectedButton = button;
        updateButtonStyle(button, true);
    }
    
    private void updateButtonStyle(Button button, boolean selected) {
        boolean isDark = ThemeService.getInstance().isDarkTheme();
        String primaryColor = isDark ? "#00D4FF" : "#0969DA";
        String accentBg = isDark ? "rgba(0, 212, 255, 0.15)" : "rgba(9, 105, 218, 0.1)";
        
        if (selected) {
            button.setStyle("-fx-background-color: " + accentBg + "; " +
                           "-fx-text-fill: " + primaryColor + ";");
        } else {
            button.setStyle("");
        }
    }
    
    private void updateThemeColors() {
        // Re-apply selected button style with new theme colors
        if (selectedButton != null) {
            updateButtonStyle(selectedButton, true);
        }
    }
}
