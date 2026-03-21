package cn.keevol.keenotes.mobilefx;

import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Main content area that switches between different modes
 */
public class MainContentArea extends StackPane {
    
    private static final Logger logger = AppLogger.getLogger(MainContentArea.class);
    
    // Mode panels
    private VBox noteModePanel;
    private VBox searchModePanel;
    private VBox reviewModePanel;
    private VBox settingsModePanel;
    
    // Note mode components
    private NoteInputPanel noteInputPanel;
    private NotesDisplayPanel notesDisplayPanel;
    
    // Optimistic UI: 追踪正在发送中的临时卡片（content+ts → card）
    private NoteCardView optimisticCard;
    
    // Search mode components
    private SearchInputPanel searchInputPanel;
    private NotesDisplayPanel searchResultsPanel;
    
    // Review mode components
    private NotesDisplayPanel reviewNotesPanel;
    private String currentReviewPeriod = "7 days";
    
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
    private final java.util.Set<Long> displayedNoteIds = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    
    // Flag to prevent duplicate listener registration
    private boolean localCacheListenerRegistered = false;
    
    // Pending notes UI components
    private HBox pendingBanner;
    private Label pendingLabel;
    private boolean showingPendingList = false;
    
    public MainContentArea() {
        getStyleClass().add("main-content-area");
        setPadding(new Insets(16));
        
        // Get services
        this.apiService = ServiceManager.getInstance().getApiService();
        this.webSocketService = ServiceManager.getInstance().getWebSocketService();
        
        // Register WebSocket listener
        registerWebSocketListener();
        
        // Listen to account switch events to re-register WebSocket listener
        ServiceManager.getInstance().accountSwitchedProperty().addListener((obs, oldVal, newVal) -> {
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
        });
        
        // Listen to ServiceManager for cache ready event
        ServiceManager.getInstance().addListener((status, message) -> {
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
        });
        
        setupPanels();
    }
    
    /**
     * Register WebSocket listener for sync events
     */
    private void registerWebSocketListener() {
        logger.info("Registering WebSocket listener");
        // Listen to WebSocket events for sync status display only
        // Note: UI updates are now driven by LocalCacheService change listeners
        webSocketService.addListener(new WebSocketClientService.SyncListener() {
            @Override
            public void onConnectionStatus(boolean connected) {
                // Not needed here
            }
            
            @Override
            public void onSyncProgress(int current, int total) {
                // Show sync indicator on all NotesDisplayPanel instances
                Platform.runLater(() -> {
                    if (currentPanel == noteModePanel && notesDisplayPanel != null) {
                        notesDisplayPanel.showSyncIndicator("Syncing...");
                    }
                    if (currentPanel == reviewModePanel && reviewNotesPanel != null) {
                        reviewNotesPanel.showSyncIndicator("Syncing...");
                    }
                    // Search results panel doesn't need sync indicator (it's for manual search)
                });
            }
            
            @Override
            public void onSyncComplete(int total, long lastSyncId) {
                // Hide sync indicator on all NotesDisplayPanel instances
                Platform.runLater(() -> {
                    if (notesDisplayPanel != null) {
                        notesDisplayPanel.hideSyncIndicator();
                    }
                    if (reviewNotesPanel != null) {
                        reviewNotesPanel.hideSyncIndicator();
                    }
                    
                    // Note: Data refresh is handled by LocalCacheService change listener
                    // Don't reload here to avoid duplicate loading
                });
            }
            
            @Override
            public void onRealtimeUpdate(long id, String content) {
                // Realtime update - UI will be updated via LocalCacheService listener
                // Show brief sync indicator for realtime updates
                Platform.runLater(() -> {
                    if (currentPanel == noteModePanel && notesDisplayPanel != null) {
                        notesDisplayPanel.showSyncIndicator("Syncing...");
                        // Hide after short delay (same as NotesDisplayPanel's own listener)
                        javafx.animation.PauseTransition hideDelay = new javafx.animation.PauseTransition(javafx.util.Duration.millis(500));
                        hideDelay.setOnFinished(e -> notesDisplayPanel.hideSyncIndicator());
                        hideDelay.play();
                    }
                });
            }
            
            @Override
            public void onError(String error) {
                // Hide sync indicator on error
                Platform.runLater(() -> {
                    if (notesDisplayPanel != null) {
                        notesDisplayPanel.hideSyncIndicator();
                    }
                    if (reviewNotesPanel != null) {
                        reviewNotesPanel.hideSyncIndicator();
                    }
                });
            }
        });
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
        
        localCache.addChangeListener(new LocalCacheService.NoteChangeListener() {
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
        });
        
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
        
        // 匹配 optimistic card：内容 + 时间戳（秒级）
        if (optimisticCard != null) {
            LocalCacheService.NoteData tempData = optimisticCard.getNoteData();
            if (tempData.content.equals(note.content) 
                    && tempData.createdAt != null && note.createdAt != null
                    && tempData.createdAt.equals(note.createdAt)) {
                logger.info("Matched optimistic card for note " + note.id + ", completing border animation");
                optimisticCard.completeBorderAnimation();
                optimisticCard = null;
                displayedNoteIds.add(note.id);
                return;
            }
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
     * Handle batch notes from database change notification
     * For batch sync (>1 notes), reload the list instead of adding one by one
     */
    private void handleBatchNotesFromDb(java.util.List<LocalCacheService.NoteData> notes) {
        // Update UI based on current panel
        if (currentPanel == noteModePanel) {
            logger.info("Batch sync completed with " + notes.size() + " notes, reloading Note list");
            loadRecentNotes();
        } else if (currentPanel == reviewModePanel) {
            logger.info("Batch sync completed with " + notes.size() + " notes, reloading Review list");
            loadReviewNotes(currentReviewPeriod);
        } else {
            logger.fine("Batch sync completed but current panel is not Note/Review mode, skipping UI update");
        }
    }
    
    private void setupPanels() {
        // Note mode panel - split view with input on left, recent notes on right
        noteModePanel = createNoteModePanel();
        
        // Search mode panel
        searchModePanel = createSearchModePanel();
        
        // Review mode panel
        reviewModePanel = createReviewModePanel();
        
        // Settings mode panel
        settingsModePanel = createSettingsModePanel();
        
        // Add all panels (initially hidden)
        getChildren().addAll(noteModePanel, searchModePanel, reviewModePanel, settingsModePanel);
        
        // Hide all except note mode
        noteModePanel.setVisible(true);
        searchModePanel.setVisible(false);
        reviewModePanel.setVisible(false);
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
    
    /**
     * Load review notes for a specific period
     */
    public void loadReviewNotes(String period) {
        logger.info("loadReviewNotes called with period: " + period);
        currentReviewPeriod = period;
        Platform.runLater(() -> reviewNotesPanel.showLoading("Loading notes"));
        
        new Thread(() -> {
            try {
                ServiceManager serviceManager = ServiceManager.getInstance();
                ServiceManager.InitializationState state = serviceManager.getLocalCacheState();
                
                if (state == ServiceManager.InitializationState.READY) {
                    localCache = serviceManager.getLocalCacheService();
                    
                    int days = switch (period) {
                        case "30 days" -> 30;
                        case "90 days" -> 90;
                        case "All" -> 3650; // 10 years
                        default -> 7;
                    };
                    
                    logger.info("Loading notes for " + days + " days");
                    
                    int totalCount = localCache.getNotesCountForReview(days);
                    
                    // Format period info for display
                    String periodInfo = switch (period) {
                        case "7 days" -> "Last 7 days";
                        case "30 days" -> "Last 30 days";
                        case "90 days" -> "Last 90 days";
                        case "All" -> "All time";
                        default -> "Last 7 days";
                    };
                    
                    logger.info("Period info: " + periodInfo + ", notes count: " + totalCount);
                    
                    Platform.runLater(() -> {
                        if (totalCount == 0) {
                            reviewNotesPanel.showEmptyState("No notes found for " + period);
                        } else {
                            reviewNotesPanel.displayNotesWithPagination(totalCount, localCache, days, periodInfo);
                        }
                    });
                } else if (state == ServiceManager.InitializationState.INITIALIZING) {
                    Platform.runLater(() -> reviewNotesPanel.showLoading("Cache is initializing"));
                    // Retry after delay
                    new Thread(() -> {
                        try {
                            Thread.sleep(2000);
                            loadReviewNotes(period);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }).start();
                } else if (state == ServiceManager.InitializationState.ERROR) {
                    String errorMsg = serviceManager.getLocalCacheErrorMessage();
                    Platform.runLater(() -> reviewNotesPanel.showError("Cache error: " + errorMsg));
                } else {
                    // NOT_STARTED - trigger initialization
                    Platform.runLater(() -> reviewNotesPanel.showLoading("Initializing cache"));
                    serviceManager.getLocalCacheService();
                    new Thread(() -> {
                        try {
                            Thread.sleep(1000);
                            loadReviewNotes(period);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }).start();
                }
            } catch (Exception e) {
                Platform.runLater(() -> reviewNotesPanel.showError("Error loading notes: " + e.getMessage()));
            }
        }).start();
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
            // Clear results without showing any text
            searchResultsPanel.clear();
            return;
        }
        
        Platform.runLater(() -> searchResultsPanel.showLoading("Searching"));
        
        new Thread(() -> {
            try {
                ServiceManager serviceManager = ServiceManager.getInstance();
                ServiceManager.InitializationState state = serviceManager.getLocalCacheState();
                
                if (state == ServiceManager.InitializationState.READY) {
                    localCache = serviceManager.getLocalCacheService();
                    var results = localCache.searchNotes(query);
                    
                    Platform.runLater(() -> {
                        if (results.isEmpty()) {
                            searchResultsPanel.showEmptyState("No results found for \"" + query + "\"");
                        } else {
                            searchResultsPanel.displayNotes(results);
                        }
                    });
                } else if (state == ServiceManager.InitializationState.INITIALIZING) {
                    Platform.runLater(() -> searchResultsPanel.showLoading("Cache is initializing"));
                    // Retry after delay
                    new Thread(() -> {
                        try {
                            Thread.sleep(2000);
                            handleSearch(query);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }).start();
                } else if (state == ServiceManager.InitializationState.ERROR) {
                    String errorMsg = serviceManager.getLocalCacheErrorMessage();
                    Platform.runLater(() -> searchResultsPanel.showError("Cache error: " + errorMsg));
                } else {
                    // NOT_STARTED - trigger initialization
                    Platform.runLater(() -> searchResultsPanel.showLoading("Initializing cache"));
                    serviceManager.getLocalCacheService();
                    new Thread(() -> {
                        try {
                            Thread.sleep(1000);
                            handleSearch(query);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }).start();
                }
            } catch (Exception e) {
                Platform.runLater(() -> searchResultsPanel.showError("Error searching notes: " + e.getMessage()));
            }
        }).start();
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
                Bindings.concat("📤 ", pendingService.pendingCountProperty().asString(), " note(s) pending")
        );
        
        return banner;
    }
    
    private void togglePendingListView() {
        if (showingPendingList) {
            // 返回正常 Note 视图
            showingPendingList = false;
            noteModePanel.getChildren().set(
                    noteModePanel.getChildren().indexOf(notesDisplayPanel) >= 0 
                        ? noteModePanel.getChildren().size() - 1 : 2,
                    notesDisplayPanel
            );
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
        
        // 加载 pending notes
        java.util.List<LocalCacheService.PendingNoteData> pendingNotes = 
                ServiceManager.getInstance().getPendingNoteService().getPendingNotes();
        
        if (pendingNotes.isEmpty()) {
            Label emptyLabel = new Label("No pending notes");
            emptyLabel.getStyleClass().add("search-loading");
            pendingListView.getChildren().add(emptyLabel);
        } else {
            javafx.scene.control.ScrollPane scrollPane = new javafx.scene.control.ScrollPane();
            scrollPane.setFitToWidth(true);
            scrollPane.getStyleClass().add("content-scroll");
            VBox.setVgrow(scrollPane, Priority.ALWAYS);
            
            VBox cardsContainer = new VBox(12);
            cardsContainer.setPadding(new Insets(8, 0, 8, 0));
            cardsContainer.getStyleClass().add("notes-container");
            
            for (LocalCacheService.PendingNoteData note : pendingNotes) {
                // 转换为 NoteData，复用 NoteCardView
                LocalCacheService.NoteData noteData = new LocalCacheService.NoteData(
                        note.id, note.content, note.channel, note.createdAt, null
                );
                cardsContainer.getChildren().add(new NoteCardView(noteData));
            }
            
            scrollPane.setContent(cardsContainer);
            pendingListView.getChildren().add(scrollPane);
        }
        
        // 替换 notesDisplayPanel 为 pendingListView
        int idx = noteModePanel.getChildren().indexOf(notesDisplayPanel);
        if (idx >= 0) {
            noteModePanel.getChildren().set(idx, pendingListView);
        }
    }
    
    /**
     * Handle note send action
     */
    private void handleNoteSend(String content) {
        // 防重入：阻止快速连续触发导致重复提交
        if (!sending.compareAndSet(false, true)) return;

        PendingNoteService pendingService = ServiceManager.getInstance().getPendingNoteService();
        String ts = java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String channel = getDesktopChannel();
        
        // Optimistic UI: 立即清空输入 + 插入临时卡片 + 启动边缘动画
        noteInputPanel.clearInput();
        noteInputPanel.setSendButtonEnabled(false);
        
        LocalCacheService.NoteData tempNote = new LocalCacheService.NoteData(
                -1, content, channel, ts, null);
        
        if (displayedNoteIds.isEmpty()) {
            notesDisplayPanel.clearEmptyState();
        }
        notesDisplayPanel.addNoteAtTop(tempNote);
        
        // 获取刚插入的卡片并启动边缘动画
        var container = notesDisplayPanel.getNotesContainer();
        if (!container.getChildren().isEmpty() 
                && container.getChildren().get(0) instanceof NoteCardView card) {
            optimisticCard = card;
            optimisticCard.startBorderAnimation();
        }
        
        noteInputPanel.setSendButtonEnabled(true);
        sending.set(false);
        
        // 网络不可用：存入 pending，取消动画，反向移除卡片
        if (!pendingService.isNetworkAvailable()) {
            pendingService.savePendingNote(content, channel);
            removeOptimisticCard();
            return;
        }
        
        // 网络可用：后台发送
        apiService.postNote(content, channel, ts).thenAccept(result -> Platform.runLater(() -> {
            if (result.success()) {
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
                pendingService.savePendingNote(content, channel);
                removeOptimisticCard();
            }
        }));
    }
    
    /**
     * 取消 optimistic card 的边缘动画并反向动画移除
     */
    private void removeOptimisticCard() {
        if (optimisticCard != null) {
            optimisticCard.cancelBorderAnimation();
            notesDisplayPanel.removeNoteWithAnimation(optimisticCard, null);
            optimisticCard = null;
        }
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
    private void loadRecentNotes() {
        Platform.runLater(() -> notesDisplayPanel.showLoading("Loading recent notes"));
        
        new Thread(() -> {
            try {
                ServiceManager serviceManager = ServiceManager.getInstance();
                localCache = serviceManager.getLocalCacheService();
                
                // If cache not ready yet, show empty state (global init will handle it)
                if (localCache == null || !localCache.isInitialized()) {
                    logger.warning("loadRecentNotes: cache not ready (localCache=" 
                        + (localCache == null ? "null" : "notNull") + ", initialized=" 
                        + (localCache != null && localCache.isInitialized()) + ")");
                    Platform.runLater(() -> {
                        notesDisplayPanel.showEmptyState("Waiting for data sync...");
                    });
                    return;
                }
                
                // Register change listener if not already registered
                Platform.runLater(this::registerLocalCacheListener);
                
                int totalCount = localCache.getLocalNoteCount();
                logger.info("loadRecentNotes: totalCount=" + totalCount);
                
                // Track displayed note IDs for incremental updates
                displayedNoteIds.clear();
                
                Platform.runLater(() -> {
                    if (totalCount == 0) {
                        notesDisplayPanel.showEmptyState("No notes found");
                    } else {
                        notesDisplayPanel.displayNotesWithPagination(totalCount, localCache, 0, null, 
                            (notes) -> {
                                // Callback: track loaded note IDs
                                for (var note : notes) {
                                    displayedNoteIds.add(note.id);
                                }
                            });
                    }
                    logger.info("Note list loaded with " + totalCount + " total notes, tracking " + displayedNoteIds.size() + " IDs"
                        + ", noteModePanel.opacity=" + noteModePanel.getOpacity()
                        + ", noteModePanel.visible=" + noteModePanel.isVisible());
                });
            } catch (Exception e) {
                e.printStackTrace();
                Platform.runLater(() -> notesDisplayPanel.showError("Error loading notes: " + e.getMessage()));
            }
        }).start();
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
            case SEARCH -> searchModePanel;
            case REVIEW -> reviewModePanel;
            case SETTINGS -> settingsModePanel;
        };
        
        if (targetPanel == currentPanel) {
            return;
        }
        
        // Fade out current panel
        FadeTransition fadeOut = new FadeTransition(Duration.millis(150), currentPanel);
        fadeOut.setFromValue(1.0);
        fadeOut.setToValue(0.0);
        fadeOut.setOnFinished(e -> {
            currentPanel.setVisible(false);
            
            // Fade in target panel
            targetPanel.setVisible(true);
            targetPanel.setOpacity(0.0);
            
            FadeTransition fadeIn = new FadeTransition(Duration.millis(150), targetPanel);
            fadeIn.setFromValue(0.0);
            fadeIn.setToValue(1.0);
            
            // Reload Note list when entering Note mode
            if (targetPanel == noteModePanel) {
                loadRecentNotes();
            }
            
            fadeIn.setOnFinished(ev -> {
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
    
    // Flag to track if search focus is pending
    private boolean pendingSearchFocus = false;
    // Flag to track if note input focus is pending
    private boolean pendingNoteFocus = false;
    
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
}
