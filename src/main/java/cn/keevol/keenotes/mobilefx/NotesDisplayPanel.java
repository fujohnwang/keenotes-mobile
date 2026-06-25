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
import javafx.beans.value.ChangeListener;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
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

    /** reviewDays sentinel: paginate via {@link LocalCacheService#getNotesOnThisDayPaged} */
    public static final int PAGINATION_MODE_ON_THIS_DAY = -1;

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
    private volatile Future<?> dbLoadFuture;

    // Optimistic card tracking
    private LocalCacheService.NoteData optimisticNoteData = null;
    private NoteCardView optimisticCard = null;
    private final Set<Long> renderedRealNoteIds = new HashSet<>();
    private final Set<LocalCacheService.NoteData> resolvedOptimisticNotes = new HashSet<>();
    private final Set<LocalCacheService.NoteData> closedOptimisticNotes = new HashSet<>();

    // Listener references for dispose pattern
    private final ChangeListener<ThemeService.Theme> themeListener;
    private final ChangeListener<Number> noteFontSizeListener;
    private final ChangeListener<String> noteFontFamilyListener;
    private WebSocketClientService.SyncListener webSocketSyncListener;
    private WebSocketClientService registeredWebSocketService;

    public NotesDisplayPanel() {
        getStyleClass().add("notes-display-panel");
        setSpacing(0);

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

        // Store listener references for dispose() — font/theme changes trigger listView.refresh()
        // so NoteCardView.update() reapplies current settings from singletons.
        themeListener = (obs, oldTheme, newTheme) -> Platform.runLater(() -> {
            updateThemeColors();
            listView.refresh();
        });
        noteFontSizeListener = (obs, oldVal, newVal) -> Platform.runLater(() -> listView.refresh());
        noteFontFamilyListener = (obs, oldVal, newVal) -> Platform.runLater(() -> listView.refresh());

        ThemeService.getInstance().currentThemeProperty().addListener(themeListener);
        SettingsService settings = SettingsService.getInstance();
        settings.noteFontSizeProperty().addListener(noteFontSizeListener);
        settings.noteFontFamilyProperty().addListener(noteFontFamilyListener);

        // Setup WebSocket listener for sync status
        setupSyncStatusListener();
    }

    /**
     * Remove all listeners registered on singleton services.
     * Call when this panel is no longer in use (account switch, window close, etc.)
     * to prevent listener retention.
     */
    public void dispose() {
        stopDotsAnimation();
        cancelDbLoad();
        loadGeneration++; // invalidate any pending Platform.runLater callbacks
        ThemeService.getInstance().currentThemeProperty().removeListener(themeListener);
        SettingsService settings = SettingsService.getInstance();
        settings.noteFontSizeProperty().removeListener(noteFontSizeListener);
        settings.noteFontFamilyProperty().removeListener(noteFontFamilyListener);
        if (registeredWebSocketService != null && webSocketSyncListener != null) {
            registeredWebSocketService.removeListener(webSocketSyncListener);
        }
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

        // Sync progress indicator is driven by MainContentArea for the visible panel only.
        // This listener only updates the long-lived Sync Channel status in the header.
        webSocketSyncListener = new WebSocketClientService.SyncListener() {
            @Override
            public void onConnectionStatus(boolean connected) {
                Platform.runLater(() -> updateSyncChannelStatus(connected));
            }

            @Override
            public void onSyncProgress(int current, int total) {
                // handled centrally by MainContentArea
            }

            @Override
            public void onSyncComplete(int total, long lastSyncId) {
                // handled centrally by MainContentArea
            }

            @Override
            public void onRealtimeUpdate(long id, String content) {
                // handled centrally by MainContentArea
            }

            @Override
            public void onError(String error) {
                // handled centrally by MainContentArea
            }

            @Override
            public void onOffline() {
                Platform.runLater(() -> showSyncChannelOffline());
            }

            @Override
            public void onReconnecting(int attempt, int maxAttempts) {
                Platform.runLater(() -> showSyncChannelReconnecting(attempt, maxAttempts));
            }
        };
        webSocketService.addListener(webSocketSyncListener);
        registeredWebSocketService = webSocketService;

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
                showSyncChannelReconnecting(0, 10);  // 手动重连，显示为第0次尝试
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
    private void showSyncChannelReconnecting(int attempt, int maxAttempts) {
        if (syncChannelIndicator == null || syncChannelLabel == null) {
            return;
        }
        boolean isDark = ThemeService.getInstance().isDarkTheme();
        String reconnectingColor = isDark ? "#D29922" : "#BF8700";
        syncChannelIndicator.setFill(Color.web(reconnectingColor));
        syncChannelLabel.setText("Sync Channel: reconnecting (" + attempt + "/" + maxAttempts + ")");
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
        cancelDbLoad();
        stopDotsAnimation();
        noteItems.clear();
        renderedRealNoteIds.clear();
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
        loadInitialNotesFromDb(0);
    }

    private void loadInitialNotesFromDb(int retryAttempt) {
        final int gen = loadGeneration;
        final int currentRetryAttempt = retryAttempt;
        cancelDbLoad();
        dbLoadFuture = AppExecutors.submitUiDb(() -> {
            try {
                logger.info("loadInitialNotesFromDb start: gen=" + gen
                        + ", retryAttempt=" + currentRetryAttempt
                        + ", totalNoteCount=" + totalNoteCount
                        + ", reviewDays=" + reviewDays);
                List<LocalCacheService.NoteData> notes;
                if (reviewDays == PAGINATION_MODE_ON_THIS_DAY) {
                    notes = localCache.getNotesOnThisDayPaged(0, 20);
                } else if (reviewDays > 0) {
                    notes = localCache.getNotesForReviewPaged(reviewDays, 0, 20);
                } else {
                    notes = localCache.getNotesPaged(0, 20);
                }
                if (Thread.currentThread().isInterrupted() || gen != loadGeneration) {
                    return null;
                }
                Platform.runLater(() -> applyInitialNotesFromDb(gen, currentRetryAttempt, notes));
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted() && gen == loadGeneration) {
                    Platform.runLater(() -> {
                        logger.severe("loadInitialNotesFromDb ERROR (retryAttempt="
                                + currentRetryAttempt + "): " + e.getMessage());
                        showError("Error loading notes: " + e.getMessage());
                    });
                }
            }
            return null;
        });
    }

    private void applyInitialNotesFromDb(int gen, int currentRetryAttempt, List<LocalCacheService.NoteData> notes) {
        if (gen != loadGeneration) {
            logger.info("loadInitialNotesFromDb: stale generation, skipping"
                    + " (gen=" + gen + ", current=" + loadGeneration + ")");
            return;
        }

        logger.info("loadInitialNotesFromDb: loaded " + notes.size()
                + " notes from DB (totalNoteCount=" + totalNoteCount + ")");

        if (noteLoadCallback != null) {
            noteLoadCallback.accept(notes);
        }

        if (totalNoteCount > 0 && notes.isEmpty()) {
            logger.warning("loadInitialNotesFromDb mismatch: totalNoteCount=" + totalNoteCount
                    + ", loaded=0, retryAttempt=" + currentRetryAttempt);
            if (currentRetryAttempt == 0) {
                logger.warning("loadInitialNotesFromDb scheduling one-shot retry");
                loadInitialNotesFromDb(1);
                return;
            }
            showError("Failed to load notes from cache. Please retry.");
            return;
        }

        appendUniqueNotes(notes);
        loadedFromDbCount = notes.size();
        logger.info("loadInitialNotesFromDb: rendered " + notes.size()
                + " notes via ListView, noteItems.size=" + noteItems.size());
    }

    private void cancelDbLoad() {
        Future<?> future = dbLoadFuture;
        if (future != null) {
            future.cancel(true);
            dbLoadFuture = null;
        }
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
        renderedRealNoteIds.clear();
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

        appendUniqueNotes(notes);
    }

    /**
     * Load more notes from database (true pagination, triggered by scroll)
     */
    private void loadMoreNotesFromDb() {
        if (isLoadingMore || loadedFromDbCount >= totalNoteCount)
            return;

        isLoadingMore = true;
        final int gen = loadGeneration;
        final int offset = loadedFromDbCount;

        AppExecutors.submitUiDb(() -> {
            try {
                List<LocalCacheService.NoteData> notes;
                if (reviewDays == PAGINATION_MODE_ON_THIS_DAY) {
                    notes = localCache.getNotesOnThisDayPaged(offset, 10);
                } else if (reviewDays > 0) {
                    notes = localCache.getNotesForReviewPaged(reviewDays, offset, 10);
                } else {
                    notes = localCache.getNotesPaged(offset, 10);
                }
                if (Thread.currentThread().isInterrupted() || gen != loadGeneration) {
                    Platform.runLater(() -> isLoadingMore = false);
                    return null;
                }
                Platform.runLater(() -> {
                    if (gen != loadGeneration) {
                        isLoadingMore = false;
                        return;
                    }
                    if (noteLoadCallback != null) {
                        noteLoadCallback.accept(notes);
                    }
                    appendUniqueNotes(notes);
                    loadedFromDbCount += notes.size();
                    isLoadingMore = false;
                });
            } catch (Exception e) {
                Platform.runLater(() -> isLoadingMore = false);
            }
            return null;
        });
    }

    // ===== Note operations =====

    /**
     * Add a single note at the top (for new notes from sync)
     */
    public void addNoteAtTop(LocalCacheService.NoteData note) {
        if (isDuplicateRealNote(note)) {
            logger.fine("Skipping duplicate note render for id=" + note.id);
            return;
        }

        noteItems.add(0, note);
        trackRenderedNote(note);
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
        resolvedOptimisticNotes.remove(note);
        closedOptimisticNotes.remove(note);
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
            resolvedOptimisticNotes.remove(optimisticNoteData);
            closedOptimisticNotes.remove(optimisticNoteData);
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
        if (optimisticNoteData == null) {
            return;
        }

        if (!closedOptimisticNotes.remove(optimisticNoteData)) {
            resolvedOptimisticNotes.add(optimisticNoteData);
        }

        // Complete border animation before list mutation so updateItem does not cancel it
        // and trigger a heavy TextArea relayout on the FX thread.
        if (optimisticCard != null) {
            optimisticCard.completeBorderAnimation();
        }

        int index = noteItems.indexOf(optimisticNoteData);
        if (index >= 0) {
            if (isDuplicateRealNote(realNote)) {
                noteItems.remove(index);
            } else {
                noteItems.set(index, realNote);
                trackRenderedNote(realNote);
            }
        }

        optimisticNoteData = null;
        optimisticCard = null;
    }

    /**
     * Consume the "optimistic note has already been resolved by realtime sync" marker.
     */
    public boolean consumeResolvedOptimistic(LocalCacheService.NoteData optimisticNote) {
        return resolvedOptimisticNotes.remove(optimisticNote);
    }

    public void finishOptimisticRequest(LocalCacheService.NoteData optimisticNote) {
        resolvedOptimisticNotes.remove(optimisticNote);
        if (optimisticNoteData == optimisticNote) {
            closedOptimisticNotes.add(optimisticNote);
        } else {
            closedOptimisticNotes.remove(optimisticNote);
        }
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
        // Don't clear noteItems — keep old data visible in case loading fails.
        // displayNotesWithPagination() will clear and repopulate on success.
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
        renderedRealNoteIds.clear();

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
        renderedRealNoteIds.clear();
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
        renderedRealNoteIds.clear();
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

    private void appendUniqueNotes(List<LocalCacheService.NoteData> notes) {
        List<LocalCacheService.NoteData> filteredNotes = filterUniqueNotes(notes);
        if (filteredNotes.isEmpty()) {
            return;
        }

        noteItems.addAll(filteredNotes);
        filteredNotes.forEach(this::trackRenderedNote);
    }

    private List<LocalCacheService.NoteData> filterUniqueNotes(List<LocalCacheService.NoteData> notes) {
        List<LocalCacheService.NoteData> filteredNotes = new ArrayList<>();
        Set<Long> batchIds = new HashSet<>();

        for (LocalCacheService.NoteData note : notes) {
            if (!isRealNote(note)) {
                filteredNotes.add(note);
                continue;
            }
            if (renderedRealNoteIds.contains(note.id) || !batchIds.add(note.id)) {
                logger.fine("Filtered duplicate note from batch render, id=" + note.id);
                continue;
            }
            filteredNotes.add(note);
        }

        return filteredNotes;
    }

    private boolean isDuplicateRealNote(LocalCacheService.NoteData note) {
        return isRealNote(note) && renderedRealNoteIds.contains(note.id);
    }

    private boolean isRealNote(LocalCacheService.NoteData note) {
        return note != null && note.id > 0;
    }

    private void trackRenderedNote(LocalCacheService.NoteData note) {
        if (isRealNote(note)) {
            renderedRealNoteIds.add(note.id);
        }
    }
}
