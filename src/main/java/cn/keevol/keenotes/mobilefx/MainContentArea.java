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
    
    // Current visible panel
    private VBox currentPanel;
    
    public MainContentArea() {
        getStyleClass().add("main-content-area");
        setPadding(new Insets(16));
        
        // Get services
        this.apiService = ServiceManager.getInstance().getApiService();
        
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
                    
                    var notes = localCache.getNotesForReview(days);
                    
                    Platform.runLater(() -> {
                        if (notes.isEmpty()) {
                            reviewNotesPanel.showEmptyState("No notes found for " + period);
                        } else {
                            reviewNotesPanel.displayNotes(notes);
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
                
                // Reload recent notes after a short delay
                new Thread(() -> {
                    try {
                        Thread.sleep(1000);
                        Platform.runLater(this::loadRecentNotes);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }).start();
                
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
     * Load recent notes (last 7 days)
     */
    private void loadRecentNotes() {
        Platform.runLater(() -> notesDisplayPanel.showLoading("Loading recent notes"));
        
        new Thread(() -> {
            try {
                // Wait a bit for cache to initialize
                Thread.sleep(500);
                
                ServiceManager serviceManager = ServiceManager.getInstance();
                ServiceManager.InitializationState state = serviceManager.getLocalCacheState();
                
                System.out.println("[MainContentArea] Cache state: " + state);
                
                if (state == ServiceManager.InitializationState.READY) {
                    localCache = serviceManager.getLocalCacheService();
                    var notes = localCache.getNotesForReview(7);
                    
                    Platform.runLater(() -> {
                        if (notes.isEmpty()) {
                            notesDisplayPanel.showEmptyState("No recent notes (last 7 days)");
                        } else {
                            notesDisplayPanel.displayNotes(notes);
                        }
                    });
                } else if (state == ServiceManager.InitializationState.INITIALIZING) {
                    Platform.runLater(() -> notesDisplayPanel.showLoading("Cache is initializing"));
                    // Retry after a delay
                    new Thread(() -> {
                        try {
                            Thread.sleep(2000);
                            loadRecentNotes();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }).start();
                } else if (state == ServiceManager.InitializationState.ERROR) {
                    String errorMsg = serviceManager.getLocalCacheErrorMessage();
                    Platform.runLater(() -> notesDisplayPanel.showError("Cache initialization failed: " + errorMsg));
                } else {
                    // NOT_STARTED - trigger initialization
                    Platform.runLater(() -> notesDisplayPanel.showLoading("Initializing cache"));
                    serviceManager.getLocalCacheService();
                    // Retry after initialization
                    new Thread(() -> {
                        try {
                            Thread.sleep(1000);
                            loadRecentNotes();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }).start();
                }
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
            fadeIn.play();
            
            currentPanel = targetPanel;
        });
        fadeOut.play();
    }
}
