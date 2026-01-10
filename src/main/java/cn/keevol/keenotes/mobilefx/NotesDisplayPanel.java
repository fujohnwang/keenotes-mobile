package cn.keevol.keenotes.mobilefx;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * Panel for displaying a list of notes
 * Reusable for Note mode, Search mode, and Review mode
 * Supports lazy loading - initially shows 20 notes, loads more on scroll
 */
public class NotesDisplayPanel extends VBox {
    
    private final VBox notesContainer;
    private final ScrollPane scrollPane;
    private final Label statusLabel;
    private Label countLabel; // Count label inside notes container
    private PauseTransition dotsAnimation;
    private String baseLoadingText;
    
    // Lazy loading
    private List<LocalCacheService.NoteData> allNotes = new ArrayList<>();
    private int displayedCount = 0;
    private static final int INITIAL_LOAD_COUNT = 20;
    private static final int LOAD_MORE_COUNT = 10;
    private boolean isLoadingMore = false;
    
    public NotesDisplayPanel() {
        getStyleClass().add("notes-display-panel");
        setSpacing(0);
        
        // Notes container (includes count label and note cards)
        notesContainer = new VBox(12);
        notesContainer.setPadding(new Insets(0, 16, 16, 16));
        notesContainer.getStyleClass().add("notes-container");
        
        // Scroll pane
        scrollPane = new ScrollPane(notesContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("content-scroll");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        
        // Listen to scroll position for lazy loading
        scrollPane.vvalueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal.doubleValue() >= 0.9 && !isLoadingMore && displayedCount < allNotes.size()) {
                loadMoreNotes();
            }
        });
        
        // Status label (for loading/empty states)
        statusLabel = new Label();
        statusLabel.getStyleClass().add("search-loading");
        statusLabel.setVisible(false);
        statusLabel.setManaged(false);
        
        getChildren().addAll(scrollPane, statusLabel);
    }
    
    /**
     * Display a list of notes with fade-in animation and lazy loading
     */
    public void displayNotes(List<LocalCacheService.NoteData> notes) {
        stopDotsAnimation();
        notesContainer.getChildren().clear();
        statusLabel.setVisible(false);
        statusLabel.setManaged(false);
        
        if (notes == null || notes.isEmpty()) {
            showEmptyState("No notes found");
            allNotes.clear();
            displayedCount = 0;
            return;
        }
        
        // Store all notes for lazy loading
        allNotes = new ArrayList<>(notes);
        displayedCount = 0;
        
        // Add count label inside notes container (will scroll with content)
        countLabel = new Label(notes.size() + " note(s)");
        countLabel.getStyleClass().add("search-count");
        notesContainer.getChildren().add(countLabel);
        
        // Load initial batch
        loadInitialNotes();
    }
    
    /**
     * Load initial batch of notes
     */
    private void loadInitialNotes() {
        int toLoad = Math.min(INITIAL_LOAD_COUNT, allNotes.size());
        
        for (int i = 0; i < toLoad; i++) {
            LocalCacheService.NoteData note = allNotes.get(i);
            NoteCardView card = new NoteCardView(note);
            
            // Set initial opacity to 0
            card.setOpacity(0);
            notesContainer.getChildren().add(card);
            
            // Create fade-in animation with delay based on index
            javafx.animation.FadeTransition fadeIn = new javafx.animation.FadeTransition(
                javafx.util.Duration.millis(300), card
            );
            fadeIn.setFromValue(0);
            fadeIn.setToValue(1);
            fadeIn.setDelay(javafx.util.Duration.millis(i * 30)); // 30ms delay per card
            fadeIn.play();
        }
        
        displayedCount = toLoad;
        
        // Add "loading more" indicator if there are more notes
        if (displayedCount < allNotes.size()) {
            Label loadMoreHint = new Label("Scroll down to load more...");
            loadMoreHint.getStyleClass().add("field-hint");
            loadMoreHint.setStyle("-fx-padding: 16 0 0 0;");
            notesContainer.getChildren().add(loadMoreHint);
        }
    }
    
    /**
     * Load more notes when scrolling to bottom
     */
    private void loadMoreNotes() {
        if (isLoadingMore || displayedCount >= allNotes.size()) {
            return;
        }
        
        isLoadingMore = true;
        
        // Remove "load more" hint if exists
        if (!notesContainer.getChildren().isEmpty()) {
            var lastChild = notesContainer.getChildren().get(notesContainer.getChildren().size() - 1);
            if (lastChild instanceof Label && ((Label) lastChild).getText().contains("Scroll down")) {
                notesContainer.getChildren().remove(lastChild);
            }
        }
        
        // Calculate how many to load
        int startIndex = displayedCount;
        int endIndex = Math.min(startIndex + LOAD_MORE_COUNT, allNotes.size());
        
        // Add new cards with animation
        for (int i = startIndex; i < endIndex; i++) {
            LocalCacheService.NoteData note = allNotes.get(i);
            NoteCardView card = new NoteCardView(note);
            
            card.setOpacity(0);
            notesContainer.getChildren().add(card);
            
            // Animate in
            javafx.animation.FadeTransition fadeIn = new javafx.animation.FadeTransition(
                javafx.util.Duration.millis(300), card
            );
            fadeIn.setFromValue(0);
            fadeIn.setToValue(1);
            fadeIn.setDelay(javafx.util.Duration.millis((i - startIndex) * 30));
            fadeIn.play();
        }
        
        displayedCount = endIndex;
        
        // Add hint again if there are still more notes
        if (displayedCount < allNotes.size()) {
            Label loadMoreHint = new Label("Scroll down to load more...");
            loadMoreHint.getStyleClass().add("field-hint");
            loadMoreHint.setStyle("-fx-padding: 16 0 0 0;");
            notesContainer.getChildren().add(loadMoreHint);
        }
        
        isLoadingMore = false;
    }
    
    /**
     * Add a single note at the top with animation (for new notes)
     */
    public void addNoteAtTop(LocalCacheService.NoteData note) {
        stopDotsAnimation();
        
        // Update count label if exists
        if (countLabel != null && notesContainer.getChildren().contains(countLabel)) {
            String currentText = countLabel.getText();
            int count = Integer.parseInt(currentText.split(" ")[0]) + 1;
            countLabel.setText(count + " note(s)");
        }
        
        // Create new card
        NoteCardView card = new NoteCardView(note);
        card.setOpacity(0);
        
        // Insert after count label (index 1) or at beginning if no count label
        int insertIndex = (countLabel != null && notesContainer.getChildren().contains(countLabel)) ? 1 : 0;
        notesContainer.getChildren().add(insertIndex, card);
        
        // Animate in
        javafx.animation.FadeTransition fadeIn = new javafx.animation.FadeTransition(
            javafx.util.Duration.millis(400), card
        );
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);
        fadeIn.play();
    }
    
    /**
     * Show loading state with animated dots
     */
    public void showLoading(String message) {
        notesContainer.getChildren().clear();
        countLabel = null;
        
        // Add loading label inside notes container (shows at top)
        Label loadingLabel = new Label(message);
        loadingLabel.getStyleClass().add("search-loading");
        notesContainer.getChildren().add(loadingLabel);
        
        statusLabel.setVisible(false);
        statusLabel.setManaged(false);
        startDotsAnimation(message, loadingLabel);
    }
    
    /**
     * Show empty state
     */
    public void showEmptyState(String message) {
        stopDotsAnimation();
        notesContainer.getChildren().clear();
        countLabel = null;
        
        Label emptyLabel = new Label(message);
        emptyLabel.getStyleClass().add("no-results");
        notesContainer.getChildren().add(emptyLabel);
        
        statusLabel.setVisible(false);
        statusLabel.setManaged(false);
    }
    
    /**
     * Show error state
     */
    public void showError(String message) {
        stopDotsAnimation();
        notesContainer.getChildren().clear();
        countLabel = null;
        
        Label errorLabel = new Label(message);
        errorLabel.getStyleClass().add("status-label");
        errorLabel.getStyleClass().add("error");
        errorLabel.setWrapText(true);
        notesContainer.getChildren().add(errorLabel);
        
        statusLabel.setVisible(false);
        statusLabel.setManaged(false);
    }
    
    /**
     * Clear all content
     */
    public void clear() {
        stopDotsAnimation();
        notesContainer.getChildren().clear();
        countLabel = null;
        statusLabel.setVisible(false);
        statusLabel.setManaged(false);
        allNotes.clear();
        displayedCount = 0;
    }
    
    /**
     * Start animated dots for loading state
     */
    private void startDotsAnimation(String baseText, Label targetLabel) {
        stopDotsAnimation();
        baseLoadingText = baseText;
        
        final int[] dotCount = {0};
        dotsAnimation = new PauseTransition(Duration.millis(500));
        dotsAnimation.setOnFinished(e -> {
            String dots = ".".repeat(dotCount[0]);
            targetLabel.setText(baseLoadingText + dots);
            dotCount[0] = (dotCount[0] + 1) % 4;
            dotsAnimation.playFromStart();
        });
        dotsAnimation.play();
    }
    
    /**
     * Stop dots animation
     */
    private void stopDotsAnimation() {
        if (dotsAnimation != null) {
            dotsAnimation.stop();
            dotsAnimation = null;
        }
    }
    
    /**
     * Get notes container for direct manipulation if needed
     */
    public VBox getNotesContainer() {
        return notesContainer;
    }
}
