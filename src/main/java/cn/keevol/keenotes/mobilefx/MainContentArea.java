package cn.keevol.keenotes.mobilefx;

import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Main content area that switches between different modes
 */
public class MainContentArea extends StackPane {
    
    // Mode panels
    private VBox noteModePanel;
    private VBox searchModePanel;
    private VBox reviewModePanel;
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
    
    // Settings mode components
    private SettingsView settingsView;
    
    // Services
    private ApiServiceV2 apiService;
    private LocalCacheService localCache;
    private WebSocketClientService webSocketService;
    
    // Current visible panel
    private VBox currentPanel;
    
    // Track displayed notes in Note mode to detect new ones
    private java.util.Set<Long> displayedNoteIds = new java.util.HashSet<>();
    
    public MainContentArea() {
        getStyleClass().add("main-content-area");
        setPadding(new Insets(16));
        
        // Get services
        this.apiService = ServiceManager.getInstance().getApiService();
        this.webSocketService = ServiceManager.getInstance().getWebSocketService();
        
        // Listen to WebSocket events for incremental updates
        webSocketService.addListener(new WebSocketClientService.SyncListener() {
            @Override
            public void onConnectionStatus(boolean connected) {
                // Not needed here
            }
            
            @Override
            public void onSyncProgress(int current, int total) {
                // Not needed here
            }
            
            @Override
            public void onSyncComplete(int total, long lastSyncId) {
                // Check for new notes (only if Note mode is visible)
                Platform.runLater(() -> {
                    if (currentPanel == noteModePanel) {
                        System.out.println("[MainContentArea] Sync complete, checking for new notes");
                        // If displayedNoteIds is empty, this is initial load after sync
                        if (displayedNoteIds.isEmpty()) {
                            loadRecentNotes();
                        } else {
                            checkAndAddNewNotes();
                        }
                    }
                });
            }
            
            @Override
            public void onRealtimeUpdate(long id, String content) {
                // Handle realtime updates (only if Note mode is visible)
                Platform.runLater(() -> {
                    if (currentPanel == noteModePanel) {
                        System.out.println("[MainContentArea] Realtime update for note " + id);
                        checkAndAddNewNotes();
                    }
                });
            }
            
            @Override
            public void onError(String error) {
                // Not needed here
            }
        });
        
        // Listen to ServiceManager for cache ready event
        ServiceManager.getInstance().addListener((status, message) -> {
            if ("local_cache_ready".equals(status)) {
                Platform.runLater(() -> {
                    // If Note mode is visible and no notes displayed yet, load them
                    if (currentPanel == noteModePanel && displayedNoteIds.isEmpty()) {
                        System.out.println("[MainContentArea] Cache ready, loading notes");
                        loadRecentNotes();
                    }
                });
            }
        });
        
        setupPanels();
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
        System.out.println("[MainContentArea] loadReviewNotes called with period: " + period);
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
                    
                    System.out.println("[MainContentArea] Loading notes for " + days + " days");
                    
                    int totalCount = localCache.getNotesCountForReview(days);
                    
                    // Format period info for display
                    String periodInfo = switch (period) {
                        case "7 days" -> "Last 7 days";
                        case "30 days" -> "Last 30 days";
                        case "90 days" -> "Last 90 days";
                        case "All" -> "All time";
                        default -> "Last 7 days";
                    };
                    
                    System.out.println("[MainContentArea] Period info: " + periodInfo + ", notes count: " + totalCount);
                    
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
        VBox panel = new VBox(1); // Minimal spacing between components (reduced from 2 to 1)
        panel.getStyleClass().add("mode-panel");
        
        // Create input panel with fixed height
        noteInputPanel = new NoteInputPanel(this::handleNoteSend);
        noteInputPanel.setMinHeight(250);
        noteInputPanel.setMaxHeight(250);
        noteInputPanel.setPrefHeight(250);
        
        // Create notes display panel (will grow to fill remaining space)
        notesDisplayPanel = new NotesDisplayPanel();
        VBox.setVgrow(notesDisplayPanel, Priority.ALWAYS);
        
        panel.getChildren().addAll(noteInputPanel, notesDisplayPanel);
        
        // Load recent notes
        loadRecentNotes();
        
        return panel;
    }
    
    /**
     * Handle note send action
     */
    private void handleNoteSend(String content) {
        noteInputPanel.showStatus("Encrypting and sending...", false);
        noteInputPanel.setSendButtonEnabled(false);
        
        apiService.postNote(content).thenAccept(result -> Platform.runLater(() -> {
            if (result.success()) {
                noteInputPanel.showStatus("✓ " + result.message(), false);
                noteInputPanel.clearInput();
                
                // Copy to clipboard if enabled
                if (SettingsService.getInstance().getCopyToClipboardOnPost()) {
                    javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
                    javafx.scene.input.ClipboardContent clipContent = new javafx.scene.input.ClipboardContent();
                    clipContent.putString(content);
                    clipboard.setContent(clipContent);
                }
                
                // Note: Don't manually add note here
                // Wait for WebSocket sync/realtime update to add it automatically
                // This ensures consistent behavior for all new notes (sent or received)
                
                // Hide status after 2 seconds
                new Thread(() -> {
                    try {
                        Thread.sleep(2000);
                        Platform.runLater(() -> noteInputPanel.hideStatus());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }).start();
            } else {
                noteInputPanel.showStatus("✗ " + result.message(), true);
            }
            
            noteInputPanel.setSendButtonEnabled(true);
        }));
    }
    
    /**
     * Check for new notes and add them incrementally with animation
     * Only checks the most recent notes (not all notes)
     */
    private void checkAndAddNewNotes() {
        new Thread(() -> {
            try {
                ServiceManager serviceManager = ServiceManager.getInstance();
                ServiceManager.InitializationState state = serviceManager.getLocalCacheState();
                
                if (state == ServiceManager.InitializationState.READY) {
                    localCache = serviceManager.getLocalCacheService();
                    
                    // Only check the most recent 10 notes (not all notes)
                    var recentNotes = localCache.getNotesPaged(0, 10);
                    
                    // Find new notes (not in displayedNoteIds)
                    java.util.List<LocalCacheService.NoteData> newNotes = new java.util.ArrayList<>();
                    for (var note : recentNotes) {
                        if (!displayedNoteIds.contains(note.id)) {
                            newNotes.add(note);
                        }
                    }
                    
                    if (!newNotes.isEmpty()) {
                        System.out.println("[MainContentArea] Found " + newNotes.size() + " new notes");
                        
                        // Sort by timestamp (newest first)
                        newNotes.sort((a, b) -> b.createdAt.compareTo(a.createdAt));
                        
                        // Add new notes sequentially with animation (single thread, no race condition)
                        for (LocalCacheService.NoteData note : newNotes) {
                            Platform.runLater(() -> {
                                notesDisplayPanel.addNoteAtTop(note);
                                displayedNoteIds.add(note.id);
                            });
                            
                            // Small delay between each note for visual effect
                            Thread.sleep(150);
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[MainContentArea] Error checking for new notes: " + e.getMessage());
                e.printStackTrace();
            }
        }).start();
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
                    Platform.runLater(() -> {
                        notesDisplayPanel.showEmptyState("Waiting for data sync...");
                    });
                    return;
                }
                
                int totalCount = localCache.getLocalNoteCount();
                
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
                    System.out.println("[MainContentArea] Note list loaded with " + totalCount + " total notes");
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
                // Focus search input after animation completes
                if (targetPanel == searchModePanel && pendingSearchFocus) {
                    pendingSearchFocus = false;
                    searchInputPanel.requestSearchFocus();
                }
            });
            
            fadeIn.play();
            
            currentPanel = targetPanel;
        });
        fadeOut.play();
    }
    
    // Flag to track if search focus is pending
    private boolean pendingSearchFocus = false;
    
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
}
