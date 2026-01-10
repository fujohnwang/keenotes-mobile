package cn.keevol.keenotes.mobilefx;

import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.function.Consumer;

/**
 * Search input panel for desktop view
 * Contains search field with debouncing
 */
public class SearchInputPanel extends VBox {
    
    private final TextField searchField;
    private final Button clearButton;
    private final Label hintLabel;
    private final Consumer<String> onSearch;
    private PauseTransition searchDebounce;
    
    public SearchInputPanel(Consumer<String> onSearch) {
        this.onSearch = onSearch;
        
        getStyleClass().add("search-input-panel");
        setSpacing(8);
        setPadding(new Insets(16));
        
        // Search field
        searchField = new TextField();
        searchField.setPromptText("Search notes...");
        searchField.getStyleClass().add("search-field");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        
        // Clear button
        clearButton = new Button("✕");
        clearButton.getStyleClass().add("clear-search-btn");
        clearButton.setVisible(false);
        clearButton.setManaged(false);
        clearButton.setOnAction(e -> clearSearch());
        
        // Search box
        HBox searchBox = new HBox(8, searchField, clearButton);
        searchBox.setAlignment(Pos.CENTER_LEFT);
        
        // Hint label
        hintLabel = new Label("Enter keywords to search your notes");
        hintLabel.getStyleClass().add("field-hint");
        
        getChildren().addAll(searchBox, hintLabel);
        
        // Setup search debouncing (500ms)
        searchDebounce = new PauseTransition(Duration.millis(500));
        searchDebounce.setOnFinished(e -> performSearch());
        
        // Listen to text changes
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            boolean hasText = newVal != null && !newVal.trim().isEmpty();
            clearButton.setVisible(hasText);
            clearButton.setManaged(hasText);
            
            if (!hasText) {
                searchDebounce.stop();
                if (onSearch != null) {
                    onSearch.accept("");
                }
            } else {
                searchDebounce.stop();
                searchDebounce.playFromStart();
            }
        });
        
        // Enter key triggers immediate search
        searchField.setOnAction(e -> {
            searchDebounce.stop();
            performSearch();
        });
    }
    
    private void performSearch() {
        String query = searchField.getText().trim();
        if (onSearch != null) {
            onSearch.accept(query);
        }
    }
    
    private void clearSearch() {
        searchField.clear();
        searchField.requestFocus();
    }
    
    /**
     * Request focus on search field
     */
    public void requestSearchFocus() {
        searchField.requestFocus();
    }
    
    /**
     * Get current search query
     */
    public String getQuery() {
        return searchField.getText().trim();
    }
    
    /**
     * Set search query
     */
    public void setQuery(String query) {
        searchField.setText(query);
    }
}
