package cn.keevol.keenotes.mobilefx;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.shape.Circle;
import javafx.scene.paint.Color;

import java.util.function.Consumer;

/**
 * Left sidebar with navigation and controls
 */
public class SidebarView extends VBox {
    
    private final Consumer<DesktopMainView.ViewMode> onNavigationChanged;
    
    // Navigation buttons
    private NavigationButton noteButton;
    private NavigationButton searchButton;
    private NavigationButton reviewButton;
    private NavigationButton settingsButton;
    
    // Review periods panel
    private ReviewPeriodsPanel reviewPeriodsPanel;
    
    // Status indicators
    private Circle sendChannelIndicator;
    private Circle syncChannelIndicator;
    private Circle dataSyncIndicator;
    private Label dataSyncLabel;
    
    public SidebarView(Consumer<DesktopMainView.ViewMode> onNavigationChanged) {
        this.onNavigationChanged = onNavigationChanged;
        
        getStyleClass().add("sidebar");
        setPadding(new Insets(16));
        setSpacing(16);
        
        setupComponents();
    }
    
    private void setupComponents() {
        // Logo area with icon and text
        HBox logoArea = createLogoArea();
        
        // Navigation buttons with SVG icons
        noteButton = new NavigationButton("Note", createNoteIcon(), true);
        noteButton.setOnAction(e -> onNavigationChanged.accept(DesktopMainView.ViewMode.NOTE));
        
        searchButton = new NavigationButton("Search", createSearchIcon(), false);
        searchButton.setOnAction(e -> onNavigationChanged.accept(DesktopMainView.ViewMode.SEARCH));
        
        reviewButton = new NavigationButton("Review", createReviewIcon(), false);
        reviewButton.setOnAction(e -> toggleReviewPanel());
        
        // Review periods panel (initially hidden, placed right after Review button)
        reviewPeriodsPanel = new ReviewPeriodsPanel(this::onReviewPeriodSelected);
        reviewPeriodsPanel.setVisible(false);
        reviewPeriodsPanel.setManaged(false);
        
        settingsButton = new NavigationButton("Settings", createSettingsIcon(), false);
        settingsButton.setOnAction(e -> onNavigationChanged.accept(DesktopMainView.ViewMode.SETTINGS));
        
        VBox navigationGroup = new VBox(8, noteButton, searchButton, reviewButton, reviewPeriodsPanel, settingsButton);
        navigationGroup.getStyleClass().add("navigation-group");
        
        // Spacer to push status to bottom
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        
        // Status indicators at bottom (replacing footer)
        VBox statusArea = createStatusArea();
        
        // Add all components
        getChildren().addAll(
            logoArea,
            navigationGroup,
            spacer,
            statusArea
        );
    }
    
    /**
     * Create logo area with icon and bold text
     */
    private HBox createLogoArea() {
        HBox logoArea = new HBox(8);
        logoArea.setAlignment(Pos.CENTER_LEFT);
        logoArea.setPadding(new Insets(0, 0, 8, 0));
        
        // Load KeeNotes icon from resources
        try {
            javafx.scene.image.Image iconImage = new javafx.scene.image.Image(
                getClass().getResourceAsStream("/icons/app-icon.png")
            );
            javafx.scene.image.ImageView iconView = new javafx.scene.image.ImageView(iconImage);
            iconView.setFitWidth(28);
            iconView.setFitHeight(28);
            iconView.setPreserveRatio(true);
            
            Label logoText = new Label("KeeNotes");
            logoText.getStyleClass().add("sidebar-logo-text");
            
            logoArea.getChildren().addAll(iconView, logoText);
        } catch (Exception e) {
            // Fallback if icon not found
            System.err.println("Could not load app icon: " + e.getMessage());
            Label logoText = new Label("KeeNotes");
            logoText.getStyleClass().add("sidebar-logo-text");
            logoArea.getChildren().add(logoText);
        }
        
        return logoArea;
    }
    
    /**
     * Create note icon from PNG
     */
    private javafx.scene.Node createNoteIcon() {
        return createIconFromResource("/icons/take_note.png");
    }
    
    /**
     * Create search icon from PNG
     */
    private javafx.scene.Node createSearchIcon() {
        return createIconFromResource("/icons/search.png");
    }
    
    /**
     * Create review icon from PNG
     */
    private javafx.scene.Node createReviewIcon() {
        return createIconFromResource("/icons/review.png");
    }
    
    /**
     * Create settings icon from PNG
     */
    private javafx.scene.Node createSettingsIcon() {
        return createIconFromResource("/icons/settings.png");
    }
    
    /**
     * Helper method to create ImageView from resource path
     */
    private javafx.scene.Node createIconFromResource(String resourcePath) {
        try {
            javafx.scene.image.Image image = new javafx.scene.image.Image(
                getClass().getResourceAsStream(resourcePath)
            );
            javafx.scene.image.ImageView imageView = new javafx.scene.image.ImageView(image);
            imageView.setFitWidth(20);
            imageView.setFitHeight(20);
            imageView.setPreserveRatio(true);
            return imageView;
        } catch (Exception e) {
            System.err.println("Could not load icon: " + resourcePath + " - " + e.getMessage());
            // Fallback to empty region
            Region placeholder = new Region();
            placeholder.setPrefSize(20, 20);
            return placeholder;
        }
    }
    
    /**
     * Create status area with connection and sync status (with indicator dots)
     */
    private VBox createStatusArea() {
        VBox statusArea = new VBox(4);
        statusArea.getStyleClass().add("sidebar-status-area");
        statusArea.setPadding(new Insets(8, 0, 0, 0));
        
        // Send Channel status with indicator dot
        sendChannelIndicator = new Circle(4);
        sendChannelIndicator.setFill(Color.web("#3FB950")); // Green
        sendChannelIndicator.getStyleClass().add("status-indicator");
        
        Label sendChannelLabel = new Label("Send Channel: ✓");
        sendChannelLabel.getStyleClass().addAll("status-label", "connected");
        sendChannelLabel.setId("sendChannelStatus");
        
        HBox sendChannelRow = new HBox(8, sendChannelIndicator, sendChannelLabel);
        sendChannelRow.setAlignment(Pos.CENTER_LEFT);
        
        // Sync Channel status with indicator dot
        syncChannelIndicator = new Circle(4);
        syncChannelIndicator.setFill(Color.web("#3FB950")); // Green
        syncChannelIndicator.getStyleClass().add("status-indicator");
        
        Label syncChannelLabel = new Label("Sync Channel: ✓");
        syncChannelLabel.getStyleClass().addAll("status-label", "connected");
        syncChannelLabel.setId("syncChannelStatus");
        
        HBox syncChannelRow = new HBox(8, syncChannelIndicator, syncChannelLabel);
        syncChannelRow.setAlignment(Pos.CENTER_LEFT);
        
        // Data sync status (global initialization status)
        dataSyncIndicator = new Circle(4);
        dataSyncIndicator.setFill(Color.web("#848D97")); // Gray initially
        dataSyncIndicator.getStyleClass().add("status-indicator");
        
        dataSyncLabel = new Label("Data: --");
        dataSyncLabel.getStyleClass().addAll("status-label");
        dataSyncLabel.setId("dataSyncStatus");
        
        HBox dataSyncRow = new HBox(8, dataSyncIndicator, dataSyncLabel);
        dataSyncRow.setAlignment(Pos.CENTER_LEFT);
        
        statusArea.getChildren().addAll(sendChannelRow, syncChannelRow, dataSyncRow);
        
        // Listen to ServiceManager for global sync status
        setupGlobalSyncStatusListener();
        
        return statusArea;
    }
    
    /**
     * Setup listener for global data sync status
     */
    private void setupGlobalSyncStatusListener() {
        ServiceManager serviceManager = ServiceManager.getInstance();
        
        // Add listener for service status changes
        serviceManager.addListener((status, message) -> {
            javafx.application.Platform.runLater(() -> {
                switch (status) {
                    case "local_cache_ready" -> updateDataSyncStatus("Ready", true);
                    case "local_cache_error" -> updateDataSyncStatus("Error", false);
                    case "sync_complete" -> updateDataSyncStatus("Synced", true);
                    case "reinitializing" -> updateDataSyncStatus("Syncing...", false);
                    case "not_configured" -> updateDataSyncStatus("Not configured", false);
                }
            });
        });
        
        // Also listen to WebSocket sync events for progress
        WebSocketClientService webSocketService = serviceManager.getWebSocketService();
        webSocketService.addListener(new WebSocketClientService.SyncListener() {
            @Override
            public void onConnectionStatus(boolean connected) {
                // Handled by existing status
            }
            
            @Override
            public void onSyncProgress(int current, int total) {
                javafx.application.Platform.runLater(() -> {
                    updateDataSyncStatus("Syncing...", false);
                });
            }
            
            @Override
            public void onSyncComplete(int total, long lastSyncId) {
                javafx.application.Platform.runLater(() -> {
                    updateDataSyncStatus("Synced", true);
                });
            }
            
            @Override
            public void onRealtimeUpdate(long id, String content) {
                // Not needed here
            }
            
            @Override
            public void onError(String error) {
                javafx.application.Platform.runLater(() -> {
                    updateDataSyncStatus("Sync error", false);
                });
            }
        });
        
        // Check initial state
        ServiceManager.InitializationState state = serviceManager.getLocalCacheState();
        switch (state) {
            case READY -> updateDataSyncStatus("Ready", true);
            case INITIALIZING -> updateDataSyncStatus("Initializing...", false);
            case ERROR -> updateDataSyncStatus("Error", false);
            case NOT_STARTED -> updateDataSyncStatus("--", false);
        }
    }
    
    /**
     * Update data sync status display
     */
    private void updateDataSyncStatus(String status, boolean isReady) {
        if (dataSyncLabel != null) {
            dataSyncLabel.setText("Data: " + status);
        }
        if (dataSyncIndicator != null) {
            if (isReady) {
                dataSyncIndicator.setFill(Color.web("#3FB950")); // Green
            } else if (status.contains("Error")) {
                dataSyncIndicator.setFill(Color.web("#F85149")); // Red
            } else {
                dataSyncIndicator.setFill(Color.web("#D29922")); // Yellow/Orange for in-progress
            }
        }
    }
    
    /**
     * Update status labels and indicator dots (called from Main)
     */
    public void updateStatus(String statusId, String message, boolean isConnected) {
        Label statusLabel = (Label) lookup("#" + statusId);
        if (statusLabel != null) {
            statusLabel.setText(message);
            statusLabel.getStyleClass().removeAll("connected", "disconnected", "connecting");
            statusLabel.getStyleClass().add(isConnected ? "connected" : "disconnected");
            
            // Update corresponding indicator dot
            Circle indicator = statusId.equals("sendChannelStatus") ? sendChannelIndicator : syncChannelIndicator;
            if (indicator != null) {
                indicator.setFill(isConnected ? Color.web("#3FB950") : Color.web("#F85149"));
            }
        }
    }
    
    /**
     * Toggle review periods panel visibility
     */
    private void toggleReviewPanel() {
        boolean isVisible = reviewPeriodsPanel.isVisible();
        reviewPeriodsPanel.setVisible(!isVisible);
        reviewPeriodsPanel.setManaged(!isVisible);
        
        // Always notify mode change to REVIEW when clicking the button
        onNavigationChanged.accept(DesktopMainView.ViewMode.REVIEW);
    }
    
    /**
     * Handle review period selection
     */
    private void onReviewPeriodSelected(String period) {
        System.out.println("[SidebarView] Period selected: " + period);
        
        // Navigate up the scene graph to find DesktopMainView
        javafx.scene.Node node = this;
        while (node != null) {
            System.out.println("[SidebarView] Checking node: " + node.getClass().getSimpleName());
            if (node instanceof DesktopMainView) {
                System.out.println("[SidebarView] Found DesktopMainView, calling onReviewPeriodSelected");
                ((DesktopMainView) node).onReviewPeriodSelected(period);
                return;
            }
            node = node.getParent();
        }
        System.err.println("[SidebarView] ERROR: Could not find DesktopMainView in parent hierarchy");
    }
    
    /**
     * Update selected navigation button
     */
    public void setSelectedMode(DesktopMainView.ViewMode mode) {
        noteButton.setSelected(mode == DesktopMainView.ViewMode.NOTE);
        searchButton.setSelected(mode == DesktopMainView.ViewMode.SEARCH);
        reviewButton.setSelected(mode == DesktopMainView.ViewMode.REVIEW);
        settingsButton.setSelected(mode == DesktopMainView.ViewMode.SETTINGS);
        
        // Show review panel when in REVIEW mode, hide for other modes
        if (mode == DesktopMainView.ViewMode.REVIEW) {
            reviewPeriodsPanel.setVisible(true);
            reviewPeriodsPanel.setManaged(true);
        } else {
            reviewPeriodsPanel.setVisible(false);
            reviewPeriodsPanel.setManaged(false);
        }
    }
}
