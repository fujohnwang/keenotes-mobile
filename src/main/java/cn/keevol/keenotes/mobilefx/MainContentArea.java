package cn.keevol.keenotes.mobilefx;

import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import cn.keevol.keenotes.mobilefx.utils.DateTimeUtil;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Main content area that switches between different modes
 */
public class MainContentArea extends StackPane {

    private static final Logger logger = AppLogger.getLogger(MainContentArea.class);

    private static final String SLOT_RECENT = "recent";
    private static final String SLOT_REVIEW = "review";
    private static final String SLOT_SEARCH = "search";
    private static final String SLOT_ON_THIS_DAY = "on-this-day";
    private static final String SLOT_PENDING = "pending";

    private static final long REVIEW_LOAD_DEBOUNCE_MS = 250;

    // Mode panels
    private VBox noteModePanel;
    private VBox searchModePanel;
    private VBox reviewModePanel;
    private VBox onThisDayModePanel;
    private VBox settingsModePanel;

    // Note mode components
    private NoteInputPanel noteInputPanel;
    private NotesDisplayPanel notesDisplayPanel;

    // Search mode components
    private SearchInputPanel searchInputPanel;
    private NotesDisplayPanel searchResultsPanel;

    // Review mode components
    private NotesDisplayPanel reviewNotesPanel;
    private String currentReviewPeriod = "7 days";
    private NotesDisplayPanel onThisDayNotesPanel;

    // Settings mode components
    private SettingsView settingsView;

    // Services
    private ApiServiceV2 apiService;
    private LocalCacheService localCache;
    private WebSocketClientService webSocketService;
    private final AtomicBoolean sending = new AtomicBoolean(false);

    // Current visible panel
    private VBox currentPanel;

    // Track displayed notes in Note mode to detect new ones (thread-safe)
    private final java.util.Set<Long> displayedNoteIds = java.util.Collections
            .synchronizedSet(new java.util.HashSet<>());

    // Flag to prevent duplicate listener registration
    private boolean localCacheListenerRegistered = false;

    // Listener references for dispose pattern
    private final ChangeListener<Number> accountSwitchedListener;
    private final ServiceManager.ServiceStatusListener serviceStatusListener;
    private WebSocketClientService.SyncListener webSocketSyncListener;
    private WebSocketClientService registeredWebSocketService;
    private LocalCacheService.NoteChangeListener localCacheChangeListener;

    // Pending notes UI components
    private HBox pendingBanner;
    private Label pendingLabel;
    private boolean showingPendingList = false;

    private final UiLoadCoordinator uiLoads = new UiLoadCoordinator();
    private final FxCoalescer syncIndicatorShowCoalescer = new FxCoalescer();
    private final FxCoalescer syncIndicatorHideCoalescer = new FxCoalescer();

    public MainContentArea() {
        getStyleClass().add("main-content-area");
        setPadding(new Insets(16));

        // Get services
        this.apiService = ServiceManager.getInstance().getApiService();
        this.webSocketService = ServiceManager.getInstance().getWebSocketService();

        // Register WebSocket listener
        registerWebSocketListener();

        // Listen to account switch events to re-register WebSocket listener
        accountSwitchedListener = (obs, oldVal, newVal) -> {
            Platform.runLater(() -> {
                logger.info("Account switched, re-registering WebSocket listener");
                // Get new WebSocket service instance
                webSocketService = ServiceManager.getInstance().getWebSocketService();
                // Re-register listener
                registerWebSocketListener();
                // Reset displayed notes tracking
                displayedNoteIds.clear();
                // Note: Don't reset localCacheListenerRegistered
                // LocalCacheService is singleton and listener is still valid
            });
        };
        ServiceManager.getInstance().accountSwitchedProperty().addListener(accountSwitchedListener);

        // Listen to ServiceManager for cache ready event
        serviceStatusListener = (status, message) -> {
            if ("local_cache_ready".equals(status)) {
                Platform.runLater(() -> {
                    // Register LocalCacheService change listener
                    registerLocalCacheListener();

                    // If Note mode is visible and no notes displayed yet, load them
                    if (currentPanel == noteModePanel && displayedNoteIds.isEmpty()) {
                        logger.info("Cache ready, loading notes");
                        loadRecentNotes();
                    }
                });
            }
        };
        ServiceManager.getInstance().addListener(serviceStatusListener);

        setupPanels();
    }

    /**
     * Register WebSocket listener for sync events
     */
    private void registerWebSocketListener() {
        logger.info("Registering WebSocket listener");

        // Remove old listener from the service it was actually registered on
        if (webSocketSyncListener != null && registeredWebSocketService != null) {
            registeredWebSocketService.removeListener(webSocketSyncListener);
        }

        // Listen to WebSocket events for sync status display only
        // Note: UI updates are now driven by LocalCacheService change listeners
        webSocketSyncListener = new WebSocketClientService.SyncListener() {
            @Override
            public void onConnectionStatus(boolean connected) {
                // Not needed here
            }

            @Override
            public void onSyncProgress(int current, int total) {
                coalescedShowSyncIndicator("Syncing...");
            }

            @Override
            public void onSyncComplete(int total, long lastSyncId) {
                coalescedHideSyncIndicator();
            }

            @Override
            public void onRealtimeUpdate(long id, String content) {
                coalescedShowSyncIndicator("Syncing...");
                Platform.runLater(() -> {
                    javafx.animation.PauseTransition hideDelay = new javafx.animation.PauseTransition(
                            javafx.util.Duration.millis(500));
                    hideDelay.setOnFinished(e -> coalescedHideSyncIndicator());
                    hideDelay.play();
                });
            }

            @Override
            public void onError(String error) {
                coalescedHideSyncIndicator();
            }
        };
        webSocketService.addListener(webSocketSyncListener);
        registeredWebSocketService = webSocketService;
    }

    /**
     * Register listener for LocalCacheService data changes
     * This is the central point for handling all note data updates
     */
    private void registerLocalCacheListener() {
        if (localCacheListenerRegistered) {
            return;
        }

        ServiceManager serviceManager = ServiceManager.getInstance();
        if (serviceManager.getLocalCacheState() != ServiceManager.InitializationState.READY) {
            return;
        }

        localCache = serviceManager.getLocalCacheService();
        if (localCache == null) {
            return;
        }

        localCacheChangeListener = new LocalCacheService.NoteChangeListener() {
            @Override
            public void onNoteInserted(LocalCacheService.NoteData note) {
                // Single note inserted (realtime update)
                // This is already on JavaFX thread (Platform.runLater in LocalCacheService)
                handleNewNoteFromDb(note);
            }

            @Override
            public void onNotesInserted(java.util.List<LocalCacheService.NoteData> notes) {
                // Batch notes inserted (sync complete)
                // This is already on JavaFX thread (Platform.runLater in LocalCacheService)
                handleBatchNotesFromDb(notes);
            }
        };
        localCache.addChangeListener(localCacheChangeListener);

        localCacheListenerRegistered = true;
        logger.info("LocalCacheService change listener registered");
    }

    /**
     * Handle a single new note from database change notification
     */
    private void handleNewNoteFromDb(LocalCacheService.NoteData note) {
        // Only update UI if Note mode is visible
        if (currentPanel != noteModePanel) {
            logger.fine("Note mode not visible, skipping UI update for note " + note.id);
            return;
        }

        // Check if already displayed (avoid duplicates)
        if (displayedNoteIds.contains(note.id)) {
            logger.fine("Note " + note.id + " already displayed, skipping");
            return;
        }

        // 匹配 optimistic card：通过内容匹配（时间戳可能不一致，因为服务器可能使用不同的时间）
        LocalCacheService.NoteData optData = notesDisplayPanel.getOptimisticNoteData();
        if (optData != null && optData.content.equals(note.content)) {
            logger.info("Matched optimistic card for note " + note.id + " by content, completing border animation");
            // Replace optimistic note with real note and complete animation
            // This replaces the temp note (-1 id) with the real one from DB
            notesDisplayPanel.replaceOptimisticNoteWithReal(note);
            displayedNoteIds.add(note.id);
            return;
        }

        logger.info("Adding new note " + note.id + " to UI with animation");

        // Clear empty state if this is the first note
        if (displayedNoteIds.isEmpty()) {
            notesDisplayPanel.clearEmptyState();
        }

        notesDisplayPanel.addNoteAtTop(note);
        displayedNoteIds.add(note.id);
    }

    /**
     * Handle batch notes from database change notification.
     * Reconnect sync now dispatches this only once after all batches are persisted,
     * so it is safe to reload immediately.
     */
    private void handleBatchNotesFromDb(java.util.List<LocalCacheService.NoteData> notes) {
        if (currentPanel == noteModePanel) {
            logger.info("Batch sync persisted, reloading Note list");
            loadRecentNotes();
        } else if (currentPanel == reviewModePanel) {
            logger.info("Batch sync persisted, reloading Review list");
            loadReviewNotes(currentReviewPeriod);
        } else if (currentPanel == onThisDayModePanel) {
            logger.info("Batch sync persisted, reloading On This Day list");
            loadOnThisDayNotes();
        } else {
            logger.fine("Batch sync persisted but current panel is not Note/Review/OnThisDay mode, skipping UI update");
        }
    }

    /**
     * Remove all listeners registered on singleton services.
     * Call when this component is no longer in use.
     */
    public void dispose() {
        stopActiveFadeTransitions();
        uiLoads.cancelAll();
        uiLoads.shutdown();
        ServiceManager.getInstance().accountSwitchedProperty().removeListener(accountSwitchedListener);
        ServiceManager.getInstance().removeListener(serviceStatusListener);
        if (registeredWebSocketService != null && webSocketSyncListener != null) {
            registeredWebSocketService.removeListener(webSocketSyncListener);
        }
        if (localCache != null && localCacheChangeListener != null) {
            localCache.removeChangeListener(localCacheChangeListener);
        }
        if (notesDisplayPanel != null) {
            notesDisplayPanel.dispose();
        }
        if (searchResultsPanel != null) {
            searchResultsPanel.dispose();
        }
        if (reviewNotesPanel != null) {
            reviewNotesPanel.dispose();
        }
        if (onThisDayNotesPanel != null) {
            onThisDayNotesPanel.dispose();
        }
        if (settingsView != null) {
            settingsView.dispose();
        }
        logger.info("MainContentArea disposed");
    }

    private void setupPanels() {
        // Note mode panel - split view with input on left, recent notes on right
        noteModePanel = createNoteModePanel();

        // Search mode panel
        searchModePanel = createSearchModePanel();

        // Review mode panel
        reviewModePanel = createReviewModePanel();

        // On This Day mode panel
        onThisDayModePanel = createOnThisDayModePanel();

        // Settings mode panel
        settingsModePanel = createSettingsModePanel();

        // Add all panels (initially hidden)
        getChildren().addAll(noteModePanel, searchModePanel, reviewModePanel, onThisDayModePanel, settingsModePanel);

        // Hide all except note mode
        noteModePanel.setVisible(true);
        searchModePanel.setVisible(false);
        reviewModePanel.setVisible(false);
        onThisDayModePanel.setVisible(false);
        settingsModePanel.setVisible(false);

        currentPanel = noteModePanel;
    }

    /**
     * Create settings mode panel
     */
    private VBox createSettingsModePanel() {
        VBox panel = new VBox();
        panel.getStyleClass().add("mode-panel");

        // Create settings view with back action that switches to Note mode
        settingsView = new SettingsView(this::handleSettingsBack);

        // Remove the header from settings view for desktop mode
        settingsView.setTop(null);

        VBox.setVgrow(settingsView, Priority.ALWAYS);
        panel.getChildren().add(settingsView);

        return panel;
    }

    /**
     * Handle settings back action (switch to Note mode)
     */
    private void handleSettingsBack() {
        // Get parent DesktopMainView and switch to Note mode
        if (getParent() != null && getParent().getParent() instanceof DesktopMainView) {
            // This will be called after settings are saved
            // We need to notify DesktopMainView to switch back to Note mode
            Platform.runLater(() -> {
                // Find DesktopMainView in parent hierarchy
                javafx.scene.Node node = this;
                while (node != null && !(node instanceof DesktopMainView)) {
                    node = node.getParent();
                }
                if (node instanceof DesktopMainView desktopView) {
                    // Switch back to Note mode
                    desktopView.switchToNoteMode();
                }
            });
        }
    }

    /**
     * Create review mode panel
     */
    private VBox createReviewModePanel() {
        VBox panel = new VBox(16);
        panel.getStyleClass().add("mode-panel");
        panel.setPadding(new Insets(16));

        // Title label
        javafx.scene.control.Label titleLabel = new javafx.scene.control.Label("Review Notes");
        titleLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: -fx-text-primary;");

        // Create review notes panel
        reviewNotesPanel = new NotesDisplayPanel();
        VBox.setVgrow(reviewNotesPanel, Priority.ALWAYS);

        panel.getChildren().addAll(titleLabel, reviewNotesPanel);

        return panel;
    }

    private VBox createOnThisDayModePanel() {
        VBox panel = new VBox(16);
        panel.getStyleClass().add("mode-panel");
        panel.setPadding(new Insets(16));

        Label titleLabel = new Label("On this day in years past");
        titleLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: -fx-text-primary;");
        VBox.setMargin(titleLabel, new Insets(0, 0, 0, 16));

        onThisDayNotesPanel = new NotesDisplayPanel();
        VBox.setVgrow(onThisDayNotesPanel, Priority.ALWAYS);

        panel.getChildren().addAll(titleLabel, onThisDayNotesPanel);
        return panel;
    }

    /**
     * Load review notes for a specific period (debounced to coalesce rapid period clicks).
     */
    public void loadReviewNotes(String period) {
        uiLoads.debounce(SLOT_REVIEW, REVIEW_LOAD_DEBOUNCE_MS, () -> loadReviewNotesNow(period));
    }

    private void loadReviewNotesNow(String period) {
        logger.info("loadReviewNotes called with period: " + period);
        currentReviewPeriod = period;
        reviewNotesPanel.showLoading("Loading notes");

        uiLoads.submit(SLOT_REVIEW, () -> loadReviewData(period), this::applyReviewLoadResult,
                e -> reviewNotesPanel.showError("Error loading notes: " + e.getMessage()));
    }

    private ReviewLoadResult loadReviewData(String period) {
        ServiceManager serviceManager = ServiceManager.getInstance();
        ServiceManager.InitializationState state = serviceManager.getLocalCacheState();
        if (state != ServiceManager.InitializationState.READY) {
            return new ReviewLoadResult(state, serviceManager.getLocalCacheErrorMessage(), period, 0, 0, null);
        }

        LocalCacheService cache = serviceManager.getLocalCacheService();
        int days = reviewPeriodToDays(period);
        logger.info("Loading notes for " + days + " days");
        int totalCount = cache.getNotesCountForReview(days);
        String periodInfo = reviewPeriodToInfo(period);
        logger.info("Period info: " + periodInfo + ", notes count: " + totalCount);
        return new ReviewLoadResult(state, null, period, days, totalCount, periodInfo);
    }

    private void applyReviewLoadResult(ReviewLoadResult result) {
        if (result.state == ServiceManager.InitializationState.READY) {
            localCache = ServiceManager.getInstance().getLocalCacheService();
            if (result.totalCount == 0) {
                reviewNotesPanel.showEmptyState("No notes found for " + result.period);
            } else {
                reviewNotesPanel.displayNotesWithPagination(
                        result.totalCount, localCache, result.days, result.periodInfo);
            }
            return;
        }
        if (result.state == ServiceManager.InitializationState.INITIALIZING) {
            reviewNotesPanel.showLoading("Cache is initializing");
            uiLoads.scheduleDelayed(SLOT_REVIEW + "-retry", 2000, () -> loadReviewNotesNow(result.period));
        } else if (result.state == ServiceManager.InitializationState.ERROR) {
            reviewNotesPanel.showError("Cache error: " + result.errorMessage);
        } else {
            reviewNotesPanel.showLoading("Initializing cache");
            ServiceManager.getInstance().getLocalCacheService();
            uiLoads.scheduleDelayed(SLOT_REVIEW + "-retry", 1000, () -> loadReviewNotesNow(result.period));
        }
    }

    public void loadOnThisDayNotes() {
        if (onThisDayNotesPanel == null) {
            return;
        }

        if (!SettingsService.getInstance().getShowOnThisDayInYearsPast()) {
            onThisDayNotesPanel.showEmptyState("On This Day is turned off in Settings.");
            return;
        }

        onThisDayNotesPanel.showLoading("Loading notes");
        uiLoads.submit(SLOT_ON_THIS_DAY, this::loadOnThisDayData, this::applyOnThisDayLoadResult,
                e -> onThisDayNotesPanel.showError("Error loading On This Day notes: " + e.getMessage()));
    }

    private OnThisDayLoadResult loadOnThisDayData() {
        ServiceManager serviceManager = ServiceManager.getInstance();
        ServiceManager.InitializationState state = serviceManager.getLocalCacheState();
        if (state != ServiceManager.InitializationState.READY) {
            return new OnThisDayLoadResult(state, serviceManager.getLocalCacheErrorMessage(), 0);
        }
        LocalCacheService cache = serviceManager.getLocalCacheService();
        if (cache == null) {
            return new OnThisDayLoadResult(ServiceManager.InitializationState.ERROR,
                    "Cache unavailable", 0);
        }
        return new OnThisDayLoadResult(state, null, cache.getNotesOnThisDayCount());
    }

    private void applyOnThisDayLoadResult(OnThisDayLoadResult result) {
        if (result.state == ServiceManager.InitializationState.READY) {
            localCache = ServiceManager.getInstance().getLocalCacheService();
            int totalCount = result.totalCount;
            logger.info("loadOnThisDayNotes: totalCount=" + totalCount);
            if (totalCount == 0) {
                onThisDayNotesPanel.showEmptyState("No notes from this day in past years yet.");
            } else {
                onThisDayNotesPanel.displayNotesWithPagination(
                        totalCount,
                        localCache,
                        NotesDisplayPanel.PAGINATION_MODE_ON_THIS_DAY,
                        "From past years");
            }
            return;
        }
        if (result.state == ServiceManager.InitializationState.INITIALIZING) {
            onThisDayNotesPanel.showLoading("Cache is initializing");
            uiLoads.scheduleDelayed(SLOT_ON_THIS_DAY + "-retry", 2000, this::loadOnThisDayNotes);
        } else if (result.state == ServiceManager.InitializationState.ERROR) {
            onThisDayNotesPanel.showError("Cache error: " + result.errorMessage);
        } else {
            onThisDayNotesPanel.showLoading("Initializing cache");
            ServiceManager.getInstance().getLocalCacheService();
            uiLoads.scheduleDelayed(SLOT_ON_THIS_DAY + "-retry", 1000, this::loadOnThisDayNotes);
        }
    }

    /**
     * Create search mode panel
     */
    private VBox createSearchModePanel() {
        VBox panel = new VBox(16);
        panel.getStyleClass().add("mode-panel");

        // Create search input panel
        searchInputPanel = new SearchInputPanel(this::handleSearch);

        // Create search results panel
        searchResultsPanel = new NotesDisplayPanel();
        VBox.setVgrow(searchResultsPanel, Priority.ALWAYS);

        panel.getChildren().addAll(searchInputPanel, searchResultsPanel);

        // Don't show any text initially - the hint is already in SearchInputPanel

        return panel;
    }

    /**
     * Handle search query
     */
    private void handleSearch(String query) {
        if (query == null || query.trim().isEmpty()) {
            searchResultsPanel.clear();
            return;
        }

        final String trimmedQuery = query.trim();
        searchResultsPanel.showLoading("Searching");
        uiLoads.submit(SLOT_SEARCH, () -> loadSearchData(trimmedQuery), result -> applySearchLoadResult(trimmedQuery, result),
                e -> searchResultsPanel.showError("Error searching notes: " + e.getMessage()));
    }

    private SearchLoadResult loadSearchData(String query) {
        ServiceManager serviceManager = ServiceManager.getInstance();
        ServiceManager.InitializationState state = serviceManager.getLocalCacheState();
        if (state != ServiceManager.InitializationState.READY) {
            return new SearchLoadResult(state, serviceManager.getLocalCacheErrorMessage(), List.of());
        }
        LocalCacheService cache = serviceManager.getLocalCacheService();
        return new SearchLoadResult(state, null, cache.searchNotes(query));
    }

    private void applySearchLoadResult(String query, SearchLoadResult result) {
        if (result.state == ServiceManager.InitializationState.READY) {
            localCache = ServiceManager.getInstance().getLocalCacheService();
            if (result.notes.isEmpty()) {
                searchResultsPanel.showEmptyState("No results found for \"" + query + "\"");
            } else {
                searchResultsPanel.displayNotes(result.notes);
            }
            return;
        }
        if (result.state == ServiceManager.InitializationState.INITIALIZING) {
            searchResultsPanel.showLoading("Cache is initializing");
            uiLoads.scheduleDelayed(SLOT_SEARCH + "-retry", 2000, () -> handleSearch(query));
        } else if (result.state == ServiceManager.InitializationState.ERROR) {
            searchResultsPanel.showError("Cache error: " + result.errorMessage);
        } else {
            searchResultsPanel.showLoading("Initializing cache");
            ServiceManager.getInstance().getLocalCacheService();
            uiLoads.scheduleDelayed(SLOT_SEARCH + "-retry", 1000, () -> handleSearch(query));
        }
    }

    /**
     * Create note mode panel with input and recent notes
     */
    private VBox createNoteModePanel() {
        VBox panel = new VBox(1);
        panel.getStyleClass().add("mode-panel");

        // Pending notes 提示条（reactive binding 控制显示/隐藏）
        pendingBanner = createPendingBanner();
        // 左右 margin 与 NoteInputPanel 内部 inputContainer 的视觉边缘对齐
        VBox.setMargin(pendingBanner, new Insets(0, 16, 0, 16));

        // Create input panel (height auto-fits content)
        noteInputPanel = new NoteInputPanel(this::handleNoteSend);

        // Create notes display panel (will grow to fill remaining space)
        notesDisplayPanel = new NotesDisplayPanel();
        VBox.setVgrow(notesDisplayPanel, Priority.ALWAYS);

        panel.getChildren().addAll(pendingBanner, noteInputPanel, notesDisplayPanel);

        // Load recent notes
        loadRecentNotes();

        return panel;
    }

    private HBox createPendingBanner() {
        HBox banner = new HBox(8);
        banner.setAlignment(Pos.CENTER_LEFT);
        banner.setPadding(new Insets(8, 16, 8, 16));
        banner.getStyleClass().add("pending-banner");

        pendingLabel = new Label();
        pendingLabel.getStyleClass().add("pending-banner-label");

        Label viewButton = new Label("View");
        viewButton.getStyleClass().add("pending-banner-view-button");
        viewButton.setOnMouseClicked(e -> togglePendingListView());

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        banner.getChildren().addAll(pendingLabel, spacer, viewButton);

        // Reactive binding: 根据 pendingCount 控制显示/隐藏和文案
        PendingNoteService pendingService = ServiceManager.getInstance().getPendingNoteService();
        banner.visibleProperty().bind(pendingService.pendingCountProperty().greaterThan(0));
        banner.managedProperty().bind(banner.visibleProperty());
        pendingLabel.textProperty().bind(
                Bindings.concat("📤 ", pendingService.pendingCountProperty().asString(), " note(s) pending"));

        return banner;
    }

    private void togglePendingListView() {
        if (showingPendingList) {
            // 返回正常 Note 视图
            showingPendingList = false;
            noteModePanel.getChildren().set(
                    noteModePanel.getChildren().indexOf(notesDisplayPanel) >= 0
                            ? noteModePanel.getChildren().size() - 1
                            : 2,
                    notesDisplayPanel);
            VBox.setVgrow(notesDisplayPanel, Priority.ALWAYS);
            loadRecentNotes();
        } else {
            // 显示 pending notes 列表
            showingPendingList = true;
            showPendingNotesList();
        }
    }

    private void showPendingNotesList() {
        VBox pendingListView = new VBox(8);
        pendingListView.setPadding(new Insets(8, 16, 16, 16));
        VBox.setVgrow(pendingListView, Priority.ALWAYS);

        // 返回按钮 — 复用现有 back-button CSS class
        Label backButton = new Label("← Back");
        backButton.getStyleClass().add("back-button");
        backButton.setOnMouseClicked(e -> {
            showingPendingList = false;
            int idx = noteModePanel.getChildren().size() - 1;
            noteModePanel.getChildren().set(idx, notesDisplayPanel);
            VBox.setVgrow(notesDisplayPanel, Priority.ALWAYS);
            loadRecentNotes();
        });

        Label title = new Label("Pending Notes");
        title.getStyleClass().add("search-pane-title");

        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getChildren().addAll(backButton, title);

        pendingListView.getChildren().add(header);

        // 先显示 loading 状态，替换 notesDisplayPanel
        Label loadingLabel = new Label("Loading...");
        loadingLabel.getStyleClass().add("search-loading");
        pendingListView.getChildren().add(loadingLabel);

        int idx = noteModePanel.getChildren().indexOf(notesDisplayPanel);
        if (idx >= 0) {
            noteModePanel.getChildren().set(idx, pendingListView);
        }

        uiLoads.submit(SLOT_PENDING,
                () -> ServiceManager.getInstance().getPendingNoteService().getPendingNotes(),
                pendingNotes -> renderPendingNotesList(pendingListView, loadingLabel, pendingNotes),
                e -> Platform.runLater(() -> {
                    pendingListView.getChildren().remove(loadingLabel);
                    Label errorLabel = new Label("Error loading pending notes: " + e.getMessage());
                    errorLabel.getStyleClass().addAll("status-label", "error");
                    pendingListView.getChildren().add(errorLabel);
                }));
    }

    private void renderPendingNotesList(VBox pendingListView, Label loadingLabel,
            List<LocalCacheService.PendingNoteData> pendingNotes) {
        pendingListView.getChildren().remove(loadingLabel);

        if (pendingNotes.isEmpty()) {
            Label emptyLabel = new Label("No pending notes");
            emptyLabel.getStyleClass().add("search-loading");
            pendingListView.getChildren().add(emptyLabel);
            return;
        }

        javafx.scene.control.ScrollPane scrollPane = new javafx.scene.control.ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("content-scroll");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        VBox cardsContainer = new VBox(12);
        cardsContainer.setPadding(new Insets(8, 0, 8, 0));
        cardsContainer.getStyleClass().add("notes-container");

        for (LocalCacheService.PendingNoteData note : pendingNotes) {
            LocalCacheService.NoteData noteData = new LocalCacheService.NoteData(
                    note.id, note.content, note.channel, note.createdAt, null);
            cardsContainer.getChildren().add(new NoteCardView(noteData));
        }

        scrollPane.setContent(cardsContainer);
        pendingListView.getChildren().add(scrollPane);
    }

    /**
     * Handle note send action
     */
    private void handleNoteSend(String content) {
        // 防重入：阻止快速连续触发导致重复提交
        if (!sending.compareAndSet(false, true))
            return;

        PendingNoteService pendingService = ServiceManager.getInstance().getPendingNoteService();
        String ts = DateTimeUtil.getCurrentUtcTimestamp();
        String channel = getDesktopChannel();

        // Optimistic UI: 立即清空输入 + 插入临时卡片 + 启动边缘动画
        noteInputPanel.clearInput();
        noteInputPanel.setSendButtonEnabled(false);

        LocalCacheService.NoteData tempNote = new LocalCacheService.NoteData(
                -1, content, channel, ts, null);

        if (displayedNoteIds.isEmpty()) {
            notesDisplayPanel.clearEmptyState();
        }
        notesDisplayPanel.addOptimisticNote(tempNote);

        noteInputPanel.setSendButtonEnabled(true);
        sending.set(false);

        // 网络不可用：存入 pending，取消动画，反向移除卡片
        if (!pendingService.isNetworkAvailable()) {
            pendingService.savePendingNote(content, channel, ts);
            removeOptimisticCard();
            return;
        }

        // 网络可用：后台发送
        apiService.postNote(content, channel, ts).thenAccept(result -> Platform.runLater(() -> {
            if (result.success()) {
                notesDisplayPanel.finishOptimisticRequest(tempNote);
                // Copy to clipboard if enabled
                if (SettingsService.getInstance().getCopyToClipboardOnPost()) {
                    javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
                    javafx.scene.input.ClipboardContent clipContent = new javafx.scene.input.ClipboardContent();
                    String hiddenMessage = SettingsService.getInstance().getHiddenMessage();
                    clipContent.putString(ZeroWidthSteganography.embedIfNeeded(content, hiddenMessage));
                    clipboard.setContent(clipContent);
                }
                // 边缘动画会在 handleNewNoteFromDb 匹配到远程同步数据时完成
            } else {
                // 发送失败：存入 pending，取消动画，反向移除卡片
                if (notesDisplayPanel.consumeResolvedOptimistic(tempNote)) {
                    logger.info("Skipping pending fallback because note was already resolved by realtime sync");
                } else {
                    pendingService.savePendingNote(content, channel, ts);
                    removeOptimisticCard();
                }
                notesDisplayPanel.finishOptimisticRequest(tempNote);
            }
        }));
    }

    /**
     * 取消 optimistic card 的边缘动画并移除
     */
    private void removeOptimisticCard() {
        notesDisplayPanel.removeOptimisticNote();
    }

    /**
     * Get desktop channel name based on platform
     * Format: desktop-{os} (e.g., desktop-mac, desktop-win, desktop-linux)
     */
    private String getDesktopChannel() {
        String os = System.getProperty("os.name", "unknown").toLowerCase();
        String osType;

        if (os.contains("mac") || os.contains("darwin")) {
            osType = "mac";
        } else if (os.contains("win")) {
            osType = "win";
        } else if (os.contains("nux") || os.contains("nix")) {
            osType = "linux";
        } else {
            osType = "unknown";
        }

        return "desktop-" + osType;
    }

    /**
     * Load recent notes (last 7 days)
     * Simple logic: just load from local DB, assume DB is ready
     * Global initialization is handled separately by ServiceManager
     */
    /**
     * Reload recent notes in Note mode (public entry for navigation layer).
     */
    public void refreshRecentNotes() {
        loadRecentNotes();
    }

    private void loadRecentNotes() {
        notesDisplayPanel.showLoading("Loading recent notes");
        uiLoads.submit(SLOT_RECENT, this::loadRecentNotesData, this::applyRecentNotesLoadResult,
                e -> notesDisplayPanel.showError("Error loading notes: " + e.getMessage()));
    }

    private RecentNotesLoadResult loadRecentNotesData() {
        ServiceManager serviceManager = ServiceManager.getInstance();
        LocalCacheService cache = serviceManager.getLocalCacheService();
        if (cache == null || !cache.isInitialized()) {
            logger.warning("loadRecentNotes: cache not ready (localCache="
                    + (cache == null ? "null" : "notNull") + ")");
            return new RecentNotesLoadResult(false, 0, cache);
        }
        int totalCount = cache.getLocalNoteCount();
        logger.info("loadRecentNotes: totalCount=" + totalCount);
        return new RecentNotesLoadResult(true, totalCount, cache);
    }

    private void applyRecentNotesLoadResult(RecentNotesLoadResult result) {
        if (!result.cacheReady) {
            notesDisplayPanel.showEmptyState("Waiting for data sync...");
            return;
        }

        localCache = result.cache;
        registerLocalCacheListener();
        displayedNoteIds.clear();

        if (result.totalCount == 0) {
            notesDisplayPanel.showEmptyState("No notes found");
        } else {
            notesDisplayPanel.displayNotesWithPagination(result.totalCount, localCache, 0, null,
                    notes -> {
                        for (var note : notes) {
                            displayedNoteIds.add(note.id);
                        }
                    });
        }
        logger.info("Note list loaded with " + result.totalCount + " total notes, tracking "
                + displayedNoteIds.size() + " IDs"
                + ", noteModePanel.opacity=" + noteModePanel.getOpacity()
                + ", noteModePanel.visible=" + noteModePanel.isVisible());
    }

    /**
     * Create a placeholder panel for testing
     */
    private VBox createPlaceholderPanel(String title) {
        VBox panel = new VBox(16);
        panel.setPadding(new Insets(24));
        panel.getStyleClass().add("mode-panel");

        javafx.scene.control.Label label = new javafx.scene.control.Label(title);
        label.setStyle("-fx-font-size: 24px; -fx-text-fill: -fx-text-primary;");

        panel.getChildren().add(label);
        return panel;
    }

    /**
     * Switch to a different mode with fade animation
     */
    public void showMode(DesktopMainView.ViewMode mode) {
        VBox targetPanel = switch (mode) {
            case NOTE -> noteModePanel;
            case ON_THIS_DAY -> onThisDayModePanel;
            case SEARCH -> searchModePanel;
            case REVIEW -> reviewModePanel;
            case SETTINGS -> settingsModePanel;
        };

        if (targetPanel == currentPanel) {
            return;
        }

        stopActiveFadeTransitions();

        // Fade out current panel
        activeFadeOut = new FadeTransition(Duration.millis(150), currentPanel);
        FadeTransition fadeOut = activeFadeOut;
        fadeOut.setFromValue(1.0);
        fadeOut.setToValue(0.0);
        fadeOut.setOnFinished(e -> {
            if (fadeOut != activeFadeOut) {
                return;
            }
            currentPanel.setVisible(false);

            // Fade in target panel
            targetPanel.setVisible(true);
            targetPanel.setOpacity(0.0);

            activeFadeIn = new FadeTransition(Duration.millis(150), targetPanel);
            FadeTransition fadeIn = activeFadeIn;
            fadeIn.setFromValue(0.0);
            fadeIn.setToValue(1.0);

            fadeIn.setOnFinished(ev -> {
                if (fadeIn != activeFadeIn) {
                    return;
                }
                // 兜底：确保 opacity 为 1.0（防止动画异常未完成）
                if (targetPanel.getOpacity() < 1.0) {
                    logger.warning("fadeIn finished but opacity="
                            + targetPanel.getOpacity() + ", forcing to 1.0");
                    targetPanel.setOpacity(1.0);
                }
                // Focus search input after animation completes
                if (targetPanel == searchModePanel && pendingSearchFocus) {
                    pendingSearchFocus = false;
                    searchInputPanel.requestSearchFocus();
                }
                // Focus note input after animation completes
                if (targetPanel == noteModePanel && pendingNoteFocus) {
                    pendingNoteFocus = false;
                    noteInputPanel.requestInputFocus();
                }
            });

            fadeIn.play();

            currentPanel = targetPanel;
        });
        fadeOut.play();
    }

    private void stopActiveFadeTransitions() {
        if (activeFadeOut != null) {
            activeFadeOut.stop();
            activeFadeOut = null;
        }
        if (activeFadeIn != null) {
            activeFadeIn.stop();
            activeFadeIn = null;
        }
    }

    // Flag to track if search focus is pending
    private boolean pendingSearchFocus = false;
    // Flag to track if note input focus is pending
    private boolean pendingNoteFocus = false;

    private FadeTransition activeFadeOut;
    private FadeTransition activeFadeIn;

    /**
     * Focus on note input field
     */
    public void focusNoteInput() {
        if (currentPanel == noteModePanel && noteInputPanel != null) {
            noteInputPanel.requestInputFocus();
        } else {
            pendingNoteFocus = true;
        }
    }

    /**
     * Focus on search input field
     */
    public void focusSearchInput() {
        if (currentPanel == searchModePanel && searchInputPanel != null) {
            // Already on search panel, focus immediately
            searchInputPanel.requestSearchFocus();
        } else {
            // Set flag to focus after animation completes
            pendingSearchFocus = true;
        }
    }

    /**
     * Get settings view (for sub-navigation)
     */
    public SettingsView getSettingsView() {
        return settingsView;
    }

    private NotesDisplayPanel visibleNotesPanelForSync() {
        if (currentPanel == noteModePanel) {
            return notesDisplayPanel;
        }
        if (currentPanel == reviewModePanel) {
            return reviewNotesPanel;
        }
        if (currentPanel == onThisDayModePanel) {
            return onThisDayNotesPanel;
        }
        return null;
    }

    private void coalescedShowSyncIndicator(String message) {
        syncIndicatorShowCoalescer.runLater(() -> {
            NotesDisplayPanel panel = visibleNotesPanelForSync();
            if (panel != null) {
                panel.showSyncIndicator(message);
            }
        });
    }

    private void coalescedHideSyncIndicator() {
        syncIndicatorHideCoalescer.runLater(() -> {
            NotesDisplayPanel panel = visibleNotesPanelForSync();
            if (panel != null) {
                panel.hideSyncIndicator();
            }
        });
    }

    private static int reviewPeriodToDays(String period) {
        return switch (period) {
            case "30 days" -> 30;
            case "90 days" -> 90;
            case "All" -> 3650;
            default -> 7;
        };
    }

    private static String reviewPeriodToInfo(String period) {
        return switch (period) {
            case "7 days" -> "Last 7 days";
            case "30 days" -> "Last 30 days";
            case "90 days" -> "Last 90 days";
            case "All" -> "All time";
            default -> "Last 7 days";
        };
    }

    private static final class ReviewLoadResult {
        final ServiceManager.InitializationState state;
        final String errorMessage;
        final String period;
        final int days;
        final int totalCount;
        final String periodInfo;

        ReviewLoadResult(ServiceManager.InitializationState state, String errorMessage, String period,
                int days, int totalCount, String periodInfo) {
            this.state = state;
            this.errorMessage = errorMessage;
            this.period = period;
            this.days = days;
            this.totalCount = totalCount;
            this.periodInfo = periodInfo;
        }
    }

    private static final class OnThisDayLoadResult {
        final ServiceManager.InitializationState state;
        final String errorMessage;
        final int totalCount;

        OnThisDayLoadResult(ServiceManager.InitializationState state, String errorMessage, int totalCount) {
            this.state = state;
            this.errorMessage = errorMessage;
            this.totalCount = totalCount;
        }
    }

    private static final class SearchLoadResult {
        final ServiceManager.InitializationState state;
        final String errorMessage;
        final List<LocalCacheService.NoteData> notes;

        SearchLoadResult(ServiceManager.InitializationState state, String errorMessage,
                List<LocalCacheService.NoteData> notes) {
            this.state = state;
            this.errorMessage = errorMessage;
            this.notes = notes;
        }
    }

    private static final class RecentNotesLoadResult {
        final boolean cacheReady;
        final int totalCount;
        final LocalCacheService cache;

        RecentNotesLoadResult(boolean cacheReady, int totalCount, LocalCacheService cache) {
            this.cacheReady = cacheReady;
            this.totalCount = totalCount;
            this.cache = cache;
        }
    }
}
