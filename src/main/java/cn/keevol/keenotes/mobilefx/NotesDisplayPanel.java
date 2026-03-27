package cn.keevol.keenotes.mobilefx;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.util.List;
import java.util.logging.Logger;

/**
 * Panel for displaying a list of notes using virtualized ListView.
 * VirtualFlow ensures only visible cells exist in the scene graph,
 * preventing GPU texture exhaustion with large note lists.
 * Supports true pagination (load from database on demand).
 * Includes Sync Channel status and Sync Indicator.
 */
public class NotesDisplayPanel extends VBox {

    private static final Logger logger = AppLogger.getLogger(NotesDisplayPanel.class);

    // ListView (virtualized, replaces VBox + ScrollPane)
    private final ListView<LocalCacheService.NoteData> listView;
    private final ObservableList<LocalCacheService.NoteData> noteItems;
    private final Label statusLabel;
    private final VBox fixedHeaderContainer;
    private HBox headerRow;
    private Label countLabel;
    private PauseTransition dotsAnimation;
    private String baseLoadingText;

    // Sync Channel status (long-term)
    private Circle syncChannelIndicator;
    private Label syncChannelLabel;
    private HBox syncChannelBox;  // Container for sync channel status

    // Sync Indicator (transient)
    private ProgressIndicator syncSpinner;
    private Label syncStatusLabel;
    private HBox syncIndicatorBox;

    // True pagination support (load from database on demand)
    private boolean useTruePagination = false;
    private int totalNoteCount = 0;
    private int loadedFromDbCount = 0;
    private LocalCacheService localCache = null;
    private int reviewDays = 0;
    private java.util.function.Consumer<java.util.List<LocalCacheService.NoteData>> noteLoadCallback = null;
    private boolean isLoadingMore = false;

    // Generation counter: prevents stale background thread callbacks from modifying
    // UI
    private int loadGeneration = 0;

    // Optimistic card tracking
    private LocalCacheService.NoteData optimisticNoteData = null;
    private NoteCardView optimisticCard = null;

    public NotesDisplayPanel() {
        getStyleClass().add("notes-display-panel");
        setSpacing(0);

        // Listen to theme changes
        ThemeService.getInstance().currentThemeProperty().addListener((obs, oldTheme, newTheme) -> {
            Platform.runLater(this::updateThemeColors);
        });

        // Fixed header container (stays at top, doesn't scroll)
        fixedHeaderContainer = new VBox();
        fixedHeaderContainer.getStyleClass().add("fixed-header");
        fixedHeaderContainer.setPadding(new Insets(0, 16, 0, 16));
        fixedHeaderContainer.setVisible(false);
        fixedHeaderContainer.setManaged(false);

        // ListView (virtualized — only visible cells exist in scene graph)
        noteItems = FXCollections.observableArrayList();
        listView = new ListView<>(noteItems);
        listView.setCellFactory(lv -> new NoteListCell(this));
        listView.getStyleClass().add("notes-list-view");
        listView.setStyle("-fx-background-color: transparent; -fx-background-insets: 0; -fx-padding: 8 16 16 16;");
        listView.setFocusTraversable(false);
        VBox.setVgrow(listView, Priority.ALWAYS);

        // Setup scroll listener for pagination after skin is loaded
        listView.skinProperty().addListener((obs, oldSkin, newSkin) -> {
            if (newSkin != null) {
                Platform.runLater(this::setupScrollListener);
            }
        });

        // Status label (for loading/error states)
        statusLabel = new Label();
        statusLabel.getStyleClass().add("search-loading");
        statusLabel.setVisible(false);
        statusLabel.setManaged(false);

        getChildren().addAll(fixedHeaderContainer, listView, statusLabel);

        // Setup WebSocket listener for sync status
        setupSyncStatusListener();
    }

    /**
     * Setup scroll listener on ListView's vertical ScrollBar for pagination
     */
    private void setupScrollListener() {
        ScrollBar vbar = (ScrollBar) listView.lookup(".scroll-bar:vertical");
        if (vbar != null) {
            vbar.valueProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal.doubleValue() >= 0.9 && !isLoadingMore
                        && useTruePagination && loadedFromDbCount < totalNoteCount) {
                    loadMoreNotesFromDb();
                }
            });
        }
    }

    // ===== Sync Status (unchanged) =====

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
                Platform.runLater(() -> {
                    showSyncIndicator("Syncing...");
                    PauseTransition hideDelay = new PauseTransition(Duration.millis(500));
                    hideDelay.setOnFinished(e -> hideSyncIndicator());
                    hideDelay.play();
                });
            }

            @Override
            public void onError(String error) {
                Platform.runLater(() -> hideSyncIndicator());
            }

            @Override
            public void onOffline() {
                Platform.runLater(() -> showSyncChannelOffline());
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
        headerRow.setAlignment(Pos.CENTER);
        headerRow.setPadding(new Insets(8, 0, 12, 0));

        // Count label (left)
        countLabel = new Label(countText);
        countLabel.getStyleClass().add("search-count");
        countLabel.setStyle("-fx-font-size: 12px;");

        // Sync indicator (transient, next to count)
        syncSpinner = new ProgressIndicator();
        syncSpinner.setMaxSize(12, 12);
        syncSpinner.setPrefSize(12, 12);
        syncSpinner.setStyle("-fx-progress-color: #00D4FF;");

        syncStatusLabel = new Label("Syncing...");
        syncStatusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #8B949E;");

        syncIndicatorBox = new HBox(4, syncSpinner, syncStatusLabel);
        syncIndicatorBox.setAlignment(Pos.CENTER);
        syncIndicatorBox.setVisible(false);
        syncIndicatorBox.setManaged(false);

        // Spacer
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Sync Channel status (right)
        syncChannelIndicator = new Circle(4);
        syncChannelIndicator.setFill(Color.web("#3FB950"));

        syncChannelLabel = new Label("Sync Channel: ✓");
        syncChannelLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #3FB950;");

        syncChannelBox = new HBox(6, syncChannelIndicator, syncChannelLabel);
        syncChannelBox.setAlignment(Pos.CENTER);
        syncChannelBox.setCursor(javafx.scene.Cursor.HAND);
        syncChannelBox.setOnMouseClicked(e -> {
            WebSocketClientService ws = ServiceManager.getInstance().getWebSocketService();
            if (!ws.isConnected()) {
                showSyncChannelReconnecting();
                ws.manualReconnect();
            }
        });

        // Bind visibility to settings property
        SettingsService settings = SettingsService.getInstance();
        syncChannelBox.visibleProperty().bind(settings.showSyncChannelStatusProperty());
        syncChannelBox.managedProperty().bind(settings.showSyncChannelStatusProperty());

        headerRow.getChildren().addAll(countLabel, syncIndicatorBox, spacer, syncChannelBox);

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

        boolean isDark = ThemeService.getInstance().isDarkTheme();
        String successColor = isDark ? "#3FB950" : "#1A7F37";
        String errorColor = isDark ? "#F85149" : "#CF222E";

        if (connected) {
            syncChannelIndicator.setFill(Color.web(successColor));
            syncChannelLabel.setText("Sync Channel: ✓");
            syncChannelLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: " + successColor + ";");
        } else {
            syncChannelIndicator.setFill(Color.web(errorColor));
            syncChannelLabel.setText("Sync Channel: ✗");
            syncChannelLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: " + errorColor + ";");
        }
    }

    /**
     * 显示 offline 状态（重连耗尽），提示用户可点击重连
     */
    private void showSyncChannelOffline() {
        if (syncChannelIndicator == null || syncChannelLabel == null) {
            return;
        }
        boolean isDark = ThemeService.getInstance().isDarkTheme();
        String offlineColor = isDark ? "#8B949E" : "#656D76";
        syncChannelIndicator.setFill(Color.web(offlineColor));
        syncChannelLabel.setText("Sync Channel: offline");
        syncChannelLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: " + offlineColor + ";");
    }

    /**
     * 显示重连中过渡状态
     */
    private void showSyncChannelReconnecting() {
        if (syncChannelIndicator == null || syncChannelLabel == null) {
            return;
        }
        boolean isDark = ThemeService.getInstance().isDarkTheme();
        String reconnectingColor = isDark ? "#D29922" : "#BF8700";
        syncChannelIndicator.setFill(Color.web(reconnectingColor));
        syncChannelLabel.setText("Sync Channel: reconnecting...");
        syncChannelLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: " + reconnectingColor + ";");
    }

    /**
     * Update colors based on current theme
     */
    private void updateThemeColors() {
        if (syncChannelIndicator != null && syncChannelLabel != null) {
            WebSocketClientService ws = ServiceManager.getInstance().getWebSocketService();
            if (ws.isOffline()) {
                showSyncChannelOffline();
            } else {
                updateSyncChannelStatus(ws.isConnected());
            }
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

    // ===== Display methods =====

    /**
     * Display notes with true pagination (load from database on demand)
     */
    public void displayNotesWithPagination(int totalCount, LocalCacheService localCache) {
        displayNotesWithPagination(totalCount, localCache, 0, null, null);
    }

    public void displayNotesWithPagination(int totalCount, LocalCacheService localCache, int days, String periodInfo) {
        displayNotesWithPagination(totalCount, localCache, days, periodInfo, null);
    }

    public void displayNotesWithPagination(int totalCount, LocalCacheService localCache, int days, String periodInfo,
            java.util.function.Consumer<java.util.List<LocalCacheService.NoteData>> noteLoadCallback) {
        stopDotsAnimation();
        noteItems.clear();
        hideStatus();
        showListView();
        loadGeneration++;

        if (totalCount == 0) {
            showEmptyState("No notes found");
            useTruePagination = false;
            loadedFromDbCount = 0;
            return;
        }

        useTruePagination = true;
        this.totalNoteCount = totalCount;
        this.localCache = localCache;
        this.reviewDays = days;
        this.noteLoadCallback = noteLoadCallback;
        loadedFromDbCount = 0;

        String countText = totalCount + " note(s)";
        if (periodInfo != null && !periodInfo.isEmpty()) {
            countText += " - " + periodInfo;
        }
        createHeaderRow(countText);

        loadInitialNotesFromDb();
    }

    /**
     * Load initial batch of notes from database
     */
    private void loadInitialNotesFromDb() {
        final int gen = loadGeneration;
        new Thread(() -> {
            try {
                List<LocalCacheService.NoteData> notes;
                if (reviewDays > 0) {
                    notes = localCache.getNotesForReviewPaged(reviewDays, 0, 20);
                } else {
                    notes = localCache.getNotesPaged(0, 20);
                }

                logger.info("loadInitialNotesFromDb: loaded " + notes.size()
                        + " notes from DB (totalNoteCount=" + totalNoteCount + ")");

                Platform.runLater(() -> {
                    if (gen != loadGeneration) {
                        logger.info("loadInitialNotesFromDb: stale generation, skipping");
                        return;
                    }

                    if (noteLoadCallback != null) {
                        noteLoadCallback.accept(notes);
                    }

                    noteItems.addAll(notes);
                    loadedFromDbCount = notes.size();

                    logger.info("loadInitialNotesFromDb: rendered " + notes.size()
                            + " notes via ListView, noteItems.size=" + noteItems.size());
                });
            } catch (Exception e) {
                logger.severe("loadInitialNotesFromDb ERROR: " + e.getMessage());
                e.printStackTrace();
                Platform.runLater(() -> showError("Error loading notes: " + e.getMessage()));
            }
        }, "LoadInitialNotes").start();
    }

    /**
     * Display a list of notes (all in memory, no pagination)
     */
    public void displayNotes(List<LocalCacheService.NoteData> notes) {
        displayNotes(notes, null);
    }

    public void displayNotes(List<LocalCacheService.NoteData> notes, String periodInfo) {
        stopDotsAnimation();
        noteItems.clear();
        hideStatus();
        showListView();
        loadGeneration++;
        useTruePagination = false;

        if (notes == null || notes.isEmpty()) {
            showEmptyState("No notes found");
            return;
        }

        String countText = notes.size() + " note(s)";
        if (periodInfo != null && !periodInfo.isEmpty()) {
            countText += " - " + periodInfo;
        }
        createHeaderRow(countText);

        noteItems.addAll(notes);
    }

    /**
     * Load more notes from database (true pagination, triggered by scroll)
     */
    private void loadMoreNotesFromDb() {
        if (isLoadingMore || loadedFromDbCount >= totalNoteCount)
            return;

        isLoadingMore = true;
        final int gen = loadGeneration;

        new Thread(() -> {
            try {
                List<LocalCacheService.NoteData> notes;
                if (reviewDays > 0) {
                    notes = localCache.getNotesForReviewPaged(reviewDays, loadedFromDbCount, 10);
                } else {
                    notes = localCache.getNotesPaged(loadedFromDbCount, 10);
                }

                Platform.runLater(() -> {
                    if (gen != loadGeneration) {
                        isLoadingMore = false;
                        return;
                    }

                    if (noteLoadCallback != null) {
                        noteLoadCallback.accept(notes);
                    }

                    noteItems.addAll(notes);
                    loadedFromDbCount += notes.size();
                    isLoadingMore = false;
                });
            } catch (Exception e) {
                Platform.runLater(() -> isLoadingMore = false);
            }
        }, "LoadMoreNotes").start();
    }

    // ===== Note operations =====

    /**
     * Add a single note at the top (for new notes from sync)
     */
    public void addNoteAtTop(LocalCacheService.NoteData note) {
        noteItems.add(0, note);
        listView.scrollTo(0);

        if (useTruePagination) {
            totalNoteCount++;
        }

        if (countLabel != null) {
            int count = useTruePagination ? totalNoteCount : noteItems.size();
            String currentText = countLabel.getText();
            String newText = count + " note(s)";
            if (currentText.contains(" - ")) {
                newText += currentText.substring(currentText.indexOf(" - "));
            }
            countLabel.setText(newText);
        }
    }

    /**
     * Add an optimistic note at the top with border animation
     */
    public void addOptimisticNote(LocalCacheService.NoteData note) {
        optimisticNoteData = note;
        optimisticCard = null;
        addNoteAtTop(note);
        // Border animation will be started by NoteListCell when it renders this item
    }

    /**
     * Get the current optimistic note data (for matching in MainContentArea)
     */
    public LocalCacheService.NoteData getOptimisticNoteData() {
        return optimisticNoteData;
    }

    /**
     * Set the optimistic card reference (called by NoteListCell)
     */
    void setOptimisticCard(NoteCardView card) {
        this.optimisticCard = card;
    }

    /**
     * Remove the optimistic note (send failed)
     */
    public void removeOptimisticNote() {
        if (optimisticCard != null) {
            optimisticCard.cancelBorderAnimation();
        }
        if (optimisticNoteData != null) {
            noteItems.remove(optimisticNoteData);
        }
        optimisticNoteData = null;
        optimisticCard = null;
    }

    /**
     * Replace the optimistic note with the real note data from database.
     * This updates the temp note (with id=-1) to the real note (with actual id),
     * and completes the border animation.
     */
    public void replaceOptimisticNoteWithReal(LocalCacheService.NoteData realNote) {
        if (optimisticNoteData != null) {
            int index = noteItems.indexOf(optimisticNoteData);
            if (index >= 0) {
                // Replace the optimistic note data with real note data
                noteItems.set(index, realNote);
            }
        }
        // Complete the border animation on the card
        if (optimisticCard != null) {
            optimisticCard.completeBorderAnimation();
        }
        // Clear optimistic tracking
        optimisticNoteData = null;
        optimisticCard = null;
    }

    // ===== Status display =====

    private void showListView() {
        listView.setVisible(true);
        listView.setManaged(true);
    }

    private void hideStatus() {
        statusLabel.setVisible(false);
        statusLabel.setManaged(false);
    }

    /**
     * Show loading state with animated dots
     */
    public void showLoading(String message) {
        noteItems.clear();
        listView.setVisible(false);
        listView.setManaged(false);
        fixedHeaderContainer.setVisible(false);
        fixedHeaderContainer.setManaged(false);
        headerRow = null;
        countLabel = null;

        statusLabel.setText(message);
        statusLabel.getStyleClass().setAll("search-loading");
        statusLabel.setWrapText(false);
        statusLabel.setVisible(true);
        statusLabel.setManaged(true);
        startDotsAnimation(message, statusLabel);
    }

    /**
     * Show empty state
     */
    public void showEmptyState(String message) {
        stopDotsAnimation();
        noteItems.clear();

        createHeaderRow("");
        if (countLabel != null) {
            countLabel.setText(message);
        }

        showListView();
        hideStatus();
    }

    /**
     * Clear empty state (called when first note is added dynamically)
     */
    public void clearEmptyState() {
        if (countLabel != null) {
            countLabel.setText("0 note(s)");
        }
    }

    /**
     * Show error state
     */
    public void showError(String message) {
        stopDotsAnimation();
        noteItems.clear();
        listView.setVisible(false);
        listView.setManaged(false);
        fixedHeaderContainer.setVisible(false);
        fixedHeaderContainer.setManaged(false);
        headerRow = null;
        countLabel = null;

        statusLabel.setText(message);
        statusLabel.getStyleClass().setAll("status-label", "error");
        statusLabel.setWrapText(true);
        statusLabel.setVisible(true);
        statusLabel.setManaged(true);
    }

    /**
     * Clear all content
     */
    public void clear() {
        stopDotsAnimation();
        noteItems.clear();
        fixedHeaderContainer.setVisible(false);
        fixedHeaderContainer.setManaged(false);
        headerRow = null;
        countLabel = null;
        hideStatus();
        showListView();
        loadGeneration++;
    }

    /**
     * Start animated dots for loading state
     */
    private void startDotsAnimation(String baseText, Label targetLabel) {
        stopDotsAnimation();
        baseLoadingText = baseText;

        final int[] dotCount = { 0 };
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
}
