package cn.keevol.keenotes.mobilefx;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * Panel for displaying a list of notes
 * Reusable for Note mode, Search mode, and Review mode
 * Supports lazy loading - initially shows 20 notes, loads more on scroll
 * Includes Sync Channel status and Sync Indicator
 */
public class NotesDisplayPanel extends VBox {
    
    private final VBox notesContainer;
    private final ScrollPane scrollPane;
    private final Label statusLabel;
    private final VBox fixedHeaderContainer; // Fixed header container (not scrollable)
    private HBox headerRow; // Header row with count + sync indicator + sync channel
    private Label countLabel;
    private PauseTransition dotsAnimation;
    private String baseLoadingText;
    
    // Sync Channel status (long-term)
    private Circle syncChannelIndicator;
    private Label syncChannelLabel;
    
    // Sync Indicator (transient)
    private ProgressIndicator syncSpinner;
    private Label syncStatusLabel;
    private HBox syncIndicatorBox;
    
    // Lazy loading
    private List<LocalCacheService.NoteData> allNotes = new ArrayList<>();
    private int displayedCount = 0;
    private static final int INITIAL_LOAD_COUNT = 20;
    private static final int LOAD_MORE_COUNT = 10;
    private boolean isLoadingMore = false;
    
    // True pagination support (load from database on demand)
    private boolean useTruePagination = false;
    private int totalNoteCount = 0;
    private LocalCacheService localCache = null;
    private int reviewDays = 0; // For review pagination
    private java.util.function.Consumer<java.util.List<LocalCacheService.NoteData>> noteLoadCallback = null;
    
    public NotesDisplayPanel() {
        getStyleClass().add("notes-display-panel");
        setSpacing(0);
        
        // Fixed header container (stays at top, doesn't scroll)
        fixedHeaderContainer = new VBox();
        fixedHeaderContainer.getStyleClass().add("fixed-header");
        fixedHeaderContainer.setPadding(new Insets(0, 16, 0, 16));
        fixedHeaderContainer.setVisible(false);
        fixedHeaderContainer.setManaged(false);
        
        // Notes container (only contains note cards, no header)
        notesContainer = new VBox(12);
        notesContainer.setPadding(new Insets(8, 16, 16, 16));
        notesContainer.getStyleClass().add("notes-container");
        
        // Scroll pane
        scrollPane = new ScrollPane(notesContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("content-scroll");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        
        // Listen to scroll position for lazy loading
        scrollPane.vvalueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal.doubleValue() >= 0.9 && !isLoadingMore) {
                // Check if there's more data based on pagination mode
                boolean hasMore = useTruePagination 
                    ? (displayedCount < totalNoteCount) 
                    : (displayedCount < allNotes.size());
                
                if (hasMore) {
                    loadMoreNotes();
                }
            }
        });
        
        // Status label (for loading/empty states)
        statusLabel = new Label();
        statusLabel.getStyleClass().add("search-loading");
        statusLabel.setVisible(false);
        statusLabel.setManaged(false);
        
        getChildren().addAll(fixedHeaderContainer, scrollPane, statusLabel);
        
        // Setup WebSocket listener for sync status
        setupSyncStatusListener();
    }
    
    /**
     * Setup listener for sync status updates
     */
    private void setupSyncStatusListener() {
        WebSocketClientService webSocketService = ServiceManager.getInstance().getWebSocketService();
        
        webSocketService.addListener(new WebSocketClientService.SyncListener() {
            @Override
            public void onConnectionStatus(boolean connected) {
                Platform.runLater(() -> updateSyncChannelStatus(connected));
            }
            
            @Override
            public void onSyncProgress(int current, int total) {
                Platform.runLater(() -> showSyncIndicator("Syncing..."));
            }
            
            @Override
            public void onSyncComplete(int total, long lastSyncId) {
                Platform.runLater(() -> hideSyncIndicator());
            }
            
            @Override
            public void onRealtimeUpdate(long id, String content) {
                // Brief indicator for realtime updates
                Platform.runLater(() -> {
                    showSyncIndicator("Syncing...");
                    // Hide after short delay
                    PauseTransition hideDelay = new PauseTransition(Duration.millis(500));
                    hideDelay.setOnFinished(e -> hideSyncIndicator());
                    hideDelay.play();
                });
            }
            
            @Override
            public void onError(String error) {
                Platform.runLater(() -> hideSyncIndicator());
            }
        });
        
        // Initial status
        updateSyncChannelStatus(webSocketService.isConnected());
    }

    
    /**
     * Create header row with count, sync indicator, and sync channel status
     */
    private HBox createHeaderRow(String countText) {
        headerRow = new HBox(12);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        headerRow.setPadding(new Insets(8, 0, 0, 0));
        
        // Count label (left)
        countLabel = new Label(countText);
        countLabel.getStyleClass().add("search-count");
        
        // Sync indicator (transient, next to count)
        syncSpinner = new ProgressIndicator();
        syncSpinner.setMaxSize(14, 14);
        syncSpinner.setPrefSize(14, 14);
        syncSpinner.setStyle("-fx-progress-color: #00D4FF;");
        
        syncStatusLabel = new Label("Syncing...");
        syncStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #8B949E;");
        
        syncIndicatorBox = new HBox(4, syncSpinner, syncStatusLabel);
        syncIndicatorBox.setAlignment(Pos.CENTER_LEFT);
        syncIndicatorBox.setVisible(false);
        syncIndicatorBox.setManaged(false);
        
        // Spacer
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        
        // Sync Channel status (right)
        syncChannelIndicator = new Circle(4);
        syncChannelIndicator.setFill(Color.web("#3FB950")); // Green by default
        
        syncChannelLabel = new Label("Sync Channel: ✓");
        syncChannelLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #3FB950;");
        
        HBox syncChannelBox = new HBox(6, syncChannelIndicator, syncChannelLabel);
        syncChannelBox.setAlignment(Pos.CENTER_RIGHT);
        
        headerRow.getChildren().addAll(countLabel, syncIndicatorBox, spacer, syncChannelBox);
        
        // Add to fixed header container and make it visible
        fixedHeaderContainer.getChildren().clear();
        fixedHeaderContainer.getChildren().add(headerRow);
        fixedHeaderContainer.setVisible(true);
        fixedHeaderContainer.setManaged(true);
        
        return headerRow;
    }
    
    /**
     * Update sync channel status display
     */
    public void updateSyncChannelStatus(boolean connected) {
        if (syncChannelIndicator == null || syncChannelLabel == null) {
            return;
        }
        
        if (connected) {
            syncChannelIndicator.setFill(Color.web("#3FB950")); // Green
            syncChannelLabel.setText("Sync Channel: ✓");
            syncChannelLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #3FB950;");
        } else {
            syncChannelIndicator.setFill(Color.web("#F85149")); // Red
            syncChannelLabel.setText("Sync Channel: ✗");
            syncChannelLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #F85149;");
        }
    }
    
    /**
     * Show sync indicator (transient)
     */
    public void showSyncIndicator(String message) {
        if (syncIndicatorBox != null) {
            syncStatusLabel.setText(message);
            syncIndicatorBox.setVisible(true);
            syncIndicatorBox.setManaged(true);
        }
    }
    
    /**
     * Hide sync indicator
     */
    public void hideSyncIndicator() {
        if (syncIndicatorBox != null) {
            syncIndicatorBox.setVisible(false);
            syncIndicatorBox.setManaged(false);
        }
    }
    
    /**
     * Display notes with true pagination (load from database on demand)
     * @param totalCount Total number of notes in database
     * @param localCache LocalCacheService instance for loading more data
     */
    public void displayNotesWithPagination(int totalCount, LocalCacheService localCache) {
        displayNotesWithPagination(totalCount, localCache, 0, null, null);
    }

    /**
     * Display notes with true pagination for review mode
     * @param totalCount Total number of notes for the period
     * @param localCache LocalCacheService instance for loading more data
     * @param days Number of days for review (0 for all notes)
     * @param periodInfo Period information to display
     */
    public void displayNotesWithPagination(int totalCount, LocalCacheService localCache, int days, String periodInfo) {
        displayNotesWithPagination(totalCount, localCache, days, periodInfo, null);
    }
    
    /**
     * Display notes with true pagination
     * @param totalCount Total number of notes
     * @param localCache LocalCacheService instance
     * @param days Number of days for review (0 for all notes)
     * @param periodInfo Period information to display
     * @param noteLoadCallback Callback when notes are loaded (for tracking displayed IDs)
     */
    public void displayNotesWithPagination(int totalCount, LocalCacheService localCache, int days, String periodInfo, 
                                          java.util.function.Consumer<java.util.List<LocalCacheService.NoteData>> noteLoadCallback) {
        stopDotsAnimation();
        notesContainer.getChildren().clear();
        statusLabel.setVisible(false);
        statusLabel.setManaged(false);
        
        if (totalCount == 0) {
            showEmptyState("No notes found");
            useTruePagination = false;
            displayedCount = 0;
            return;
        }
        
        // Enable true pagination mode
        useTruePagination = true;
        this.totalNoteCount = totalCount;
        this.localCache = localCache;
        this.reviewDays = days;
        this.noteLoadCallback = noteLoadCallback;
        this.allNotes.clear();
        displayedCount = 0;
        
        // Create header row with total count (will be added to fixed header container)
        String countText = totalCount + " note(s)";
        if (periodInfo != null && !periodInfo.isEmpty()) {
            countText += " - " + periodInfo;
        }
        createHeaderRow(countText);
        
        // Load initial batch from database
        loadInitialNotesFromDb();
    }
    
    /**
     * Load initial batch of notes from database
     */
    private void loadInitialNotesFromDb() {
        new Thread(() -> {
            try {
                List<LocalCacheService.NoteData> notes;
                if (reviewDays > 0) {
                    notes = localCache.getNotesForReviewPaged(reviewDays, 0, INITIAL_LOAD_COUNT);
                } else {
                    notes = localCache.getNotesPaged(0, INITIAL_LOAD_COUNT);
                }
                
                // Notify callback
                if (noteLoadCallback != null) {
                    noteLoadCallback.accept(notes);
                }
                
                Platform.runLater(() -> {
                    // Add all cards
                    for (LocalCacheService.NoteData note : notes) {
                        NoteCardView card = new NoteCardView(note);
                        notesContainer.getChildren().add(card);
                    }
                    
                    displayedCount = notes.size();
                    
                    // Add "loading more" indicator if there are more notes
                    if (displayedCount < totalNoteCount) {
                        Label loadMoreHint = new Label("Scroll down to load more...");
                        loadMoreHint.getStyleClass().add("field-hint");
                        loadMoreHint.setStyle("-fx-padding: 16 0 0 0;");
                        notesContainer.getChildren().add(loadMoreHint);
                    }
                    
                    // Fade in the entire container
                    notesContainer.setOpacity(0);
                    javafx.animation.FadeTransition fadeIn = new javafx.animation.FadeTransition(
                        javafx.util.Duration.millis(300), notesContainer
                    );
                    fadeIn.setFromValue(0);
                    fadeIn.setToValue(1);
                    fadeIn.play();
                });
            } catch (Exception e) {
                Platform.runLater(() -> showError("Error loading notes: " + e.getMessage()));
            }
        }, "LoadInitialNotes").start();
    }
    
    /**
     * Display a list of notes with fade-in animation and lazy loading
     */
    public void displayNotes(List<LocalCacheService.NoteData> notes) {
        displayNotes(notes, null);
    }
    
    /**
     * Display a list of notes with fade-in animation and lazy loading
     * @param notes List of notes to display
     * @param periodInfo Optional period information (e.g., "Last 7 days", "Last 30 days")
     */
    public void displayNotes(List<LocalCacheService.NoteData> notes, String periodInfo) {
        stopDotsAnimation();
        notesContainer.getChildren().clear();
        statusLabel.setVisible(false);
        statusLabel.setManaged(false);
        
        // Disable true pagination mode
        useTruePagination = false;
        
        if (notes == null || notes.isEmpty()) {
            showEmptyState("No notes found");
            allNotes.clear();
            displayedCount = 0;
            return;
        }
        
        // Store all notes for lazy loading
        allNotes = new ArrayList<>(notes);
        displayedCount = 0;
        
        // Create header row with count + sync indicator + sync channel (will be added to fixed header container)
        String countText = notes.size() + " note(s)";
        if (periodInfo != null && !periodInfo.isEmpty()) {
            countText += " - " + periodInfo;
        }
        createHeaderRow(countText);
        
        // Load initial batch
        loadInitialNotes();
    }
    
    /**
     * Load initial batch of notes
     */
    private void loadInitialNotes() {
        int toLoad = Math.min(INITIAL_LOAD_COUNT, allNotes.size());
        
        // Add all cards without individual animation
        for (int i = 0; i < toLoad; i++) {
            LocalCacheService.NoteData note = allNotes.get(i);
            NoteCardView card = new NoteCardView(note);
            notesContainer.getChildren().add(card);
        }
        
        displayedCount = toLoad;
        
        // Add "loading more" indicator if there are more notes
        if (displayedCount < allNotes.size()) {
            Label loadMoreHint = new Label("Scroll down to load more...");
            loadMoreHint.getStyleClass().add("field-hint");
            loadMoreHint.setStyle("-fx-padding: 16 0 0 0;");
            notesContainer.getChildren().add(loadMoreHint);
        }
        
        // Fade in the entire container
        notesContainer.setOpacity(0);
        javafx.animation.FadeTransition fadeIn = new javafx.animation.FadeTransition(
            javafx.util.Duration.millis(300), notesContainer
        );
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);
        fadeIn.play();
    }

    
    /**
     * Load more notes when scrolling to bottom
     */
    private void loadMoreNotes() {
        if (isLoadingMore) {
            return;
        }
        
        // Check if we should load more based on pagination mode
        if (useTruePagination) {
            if (displayedCount >= totalNoteCount) {
                return;
            }
        } else {
            if (displayedCount >= allNotes.size()) {
                return;
            }
        }
        
        isLoadingMore = true;
        
        // Remove "load more" hint if exists
        if (!notesContainer.getChildren().isEmpty()) {
            var lastChild = notesContainer.getChildren().get(notesContainer.getChildren().size() - 1);
            if (lastChild instanceof Label && ((Label) lastChild).getText().contains("Scroll down")) {
                notesContainer.getChildren().remove(lastChild);
            }
        }
        
        if (useTruePagination) {
            // Load from database
            loadMoreNotesFromDb();
        } else {
            // Load from memory
            loadMoreNotesFromMemory();
        }
    }
    
    /**
     * Load more notes from database (true pagination)
     */
    private void loadMoreNotesFromDb() {
        new Thread(() -> {
            try {
                List<LocalCacheService.NoteData> notes;
                if (reviewDays > 0) {
                    notes = localCache.getNotesForReviewPaged(reviewDays, displayedCount, LOAD_MORE_COUNT);
                } else {
                    notes = localCache.getNotesPaged(displayedCount, LOAD_MORE_COUNT);
                }
                
                // Notify callback
                if (noteLoadCallback != null) {
                    noteLoadCallback.accept(notes);
                }
                
                Platform.runLater(() -> {
                    // Add new cards
                    for (LocalCacheService.NoteData note : notes) {
                        NoteCardView card = new NoteCardView(note);
                        notesContainer.getChildren().add(card);
                    }
                    
                    displayedCount += notes.size();
                    
                    // Add hint again if there are still more notes
                    if (displayedCount < totalNoteCount) {
                        Label loadMoreHint = new Label("Scroll down to load more...");
                        loadMoreHint.getStyleClass().add("field-hint");
                        loadMoreHint.setStyle("-fx-padding: 16 0 0 0;");
                        notesContainer.getChildren().add(loadMoreHint);
                    }
                    
                    isLoadingMore = false;
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    isLoadingMore = false;
                });
            }
        }, "LoadMoreNotes").start();
    }
    
    /**
     * Load more notes from memory (old behavior for Review/Search)
     */
    private void loadMoreNotesFromMemory() {
        // Calculate how many to load
        int startIndex = displayedCount;
        int endIndex = Math.min(startIndex + LOAD_MORE_COUNT, allNotes.size());
        
        // Add new cards without animation (lazy loading should be fast)
        for (int i = startIndex; i < endIndex; i++) {
            LocalCacheService.NoteData note = allNotes.get(i);
            NoteCardView card = new NoteCardView(note);
            notesContainer.getChildren().add(card);
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
     * Add a single note at the top with pop-in animation (for new notes)
     * Animation: slide down from top + scale up + fade in
     */
    public void addNoteAtTop(LocalCacheService.NoteData note) {
        stopDotsAnimation();
        
        // Update count label and total count if in pagination mode
        if (useTruePagination) {
            totalNoteCount++;
        }
        
        // Update count label if exists
        if (countLabel != null) {
            String currentText = countLabel.getText();
            int count = useTruePagination ? totalNoteCount : (displayedCount + 1);
            String newText = count + " note(s)";
            // Preserve period info if present
            if (currentText.contains(" - ")) {
                newText += currentText.substring(currentText.indexOf(" - "));
            }
            countLabel.setText(newText);
        }
        
        // Increment displayed count
        displayedCount++;
        
        // Create new card
        NoteCardView card = new NoteCardView(note);
        
        // Set initial state for pop-in animation
        card.setOpacity(0);
        card.setScaleX(0.8);
        card.setScaleY(0.8);
        card.setTranslateY(-30); // Start above
        
        // Insert at beginning of notes container (no header row inside anymore)
        notesContainer.getChildren().add(0, card);
        
        // Scroll to top to show the new card
        scrollPane.setVvalue(0);
        
        // Create parallel animation: fade + scale + slide
        javafx.animation.FadeTransition fadeIn = new javafx.animation.FadeTransition(
            javafx.util.Duration.millis(400), card
        );
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);
        
        javafx.animation.ScaleTransition scaleIn = new javafx.animation.ScaleTransition(
            javafx.util.Duration.millis(400), card
        );
        scaleIn.setFromX(0.8);
        scaleIn.setFromY(0.8);
        scaleIn.setToX(1.0);
        scaleIn.setToY(1.0);
        
        javafx.animation.TranslateTransition slideIn = new javafx.animation.TranslateTransition(
            javafx.util.Duration.millis(400), card
        );
        slideIn.setFromY(-30);
        slideIn.setToY(0);
        
        // Use ease-out interpolator for smooth deceleration
        javafx.animation.Interpolator easeOut = javafx.animation.Interpolator.SPLINE(0.25, 0.1, 0.25, 1.0);
        fadeIn.setInterpolator(easeOut);
        scaleIn.setInterpolator(easeOut);
        slideIn.setInterpolator(easeOut);
        
        // Play all animations together
        javafx.animation.ParallelTransition popIn = new javafx.animation.ParallelTransition(
            fadeIn, scaleIn, slideIn
        );
        popIn.play();
    }
    
    /**
     * Show loading state with animated dots
     */
    public void showLoading(String message) {
        notesContainer.getChildren().clear();
        fixedHeaderContainer.setVisible(false);
        fixedHeaderContainer.setManaged(false);
        headerRow = null;
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
        fixedHeaderContainer.setVisible(false);
        fixedHeaderContainer.setManaged(false);
        headerRow = null;
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
        fixedHeaderContainer.setVisible(false);
        fixedHeaderContainer.setManaged(false);
        headerRow = null;
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
        fixedHeaderContainer.setVisible(false);
        fixedHeaderContainer.setManaged(false);
        headerRow = null;
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
