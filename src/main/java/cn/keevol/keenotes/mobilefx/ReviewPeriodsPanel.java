package cn.keevol.keenotes.mobilefx;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * Review periods selection panel
 */
public class ReviewPeriodsPanel extends VBox {
    
    private final Consumer<String> onPeriodSelected;
    private Button selectedButton;
    
    public ReviewPeriodsPanel(Consumer<String> onPeriodSelected) {
        this.onPeriodSelected = onPeriodSelected;
        
        getStyleClass().add("review-periods-panel");
        setPadding(new Insets(8, 0, 8, 12));
        setSpacing(6);
        
        setupButtons();
    }
    
    private void setupButtons() {
        String[] periods = {"7 days", "30 days", "90 days", "All"};
        
        for (String period : periods) {
            Button button = createPeriodButton(period);
            getChildren().add(button);
        }
    }
    
    private Button createPeriodButton(String period) {
        Button button = new Button(period);
        button.getStyleClass().add("period-button");
        button.setMaxWidth(Double.MAX_VALUE);
        
        button.setOnAction(e -> {
            selectButton(button);
            onPeriodSelected.accept(period);
        });
        
        // Hover effect
        button.setOnMouseEntered(e -> {
            if (button != selectedButton) {
                button.setStyle("-fx-background-color: rgba(0, 212, 255, 0.1);");
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
        button.setStyle("-fx-background-color: rgba(0, 212, 255, 0.15); " +
                       "-fx-text-fill: #00D4FF;");
    }
}
