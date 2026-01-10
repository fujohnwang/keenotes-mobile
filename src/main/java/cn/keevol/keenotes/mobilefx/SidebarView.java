package cn.keevol.keenotes.mobilefx;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.Priority;
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
     * Create note icon (document with pen)
     */
    private javafx.scene.Node createNoteIcon() {
        javafx.scene.shape.SVGPath icon = new javafx.scene.shape.SVGPath();
        icon.setContent("M14 2H6C4.9 2 4 2.9 4 4V20C4 21.1 4.9 22 6 22H18C19.1 22 20 21.1 20 20V8L14 2M18 20H6V4H13V9H18V20M15 11H8V13H15V11M17 15H8V17H17V15");
        return icon;
    }
    
    /**
     * Create search icon (magnifying glass)
     */
    private javafx.scene.Node createSearchIcon() {
        javafx.scene.shape.SVGPath icon = new javafx.scene.shape.SVGPath();
        icon.setContent("M15.5 14H14.71L14.43 13.73C15.41 12.59 16 11.11 16 9.5C16 5.91 13.09 3 9.5 3C5.91 3 3 5.91 3 9.5C3 13.09 5.91 16 9.5 16C11.11 16 12.59 15.41 13.73 14.43L14 14.71V15.5L19 20.49L20.49 19L15.5 14M9.5 14C7.01 14 5 11.99 5 9.5C5 7.01 7.01 5 9.5 5C11.99 5 14 7.01 14 9.5C14 11.99 11.99 14 9.5 14");
        return icon;
    }
    
    /**
     * Create review icon (book/library)
     */
    private javafx.scene.Node createReviewIcon() {
        javafx.scene.shape.SVGPath icon = new javafx.scene.shape.SVGPath();
        icon.setContent("M18 2H12V9L9.5 7.5L7 9V2H6C4.9 2 4 2.9 4 4V20C4 21.1 4.9 22 6 22H18C19.1 22 20 21.1 20 20V4C20 2.9 19.1 2 18 2");
        return icon;
    }
    
    /**
     * Create settings icon (gear)
     */
    private javafx.scene.Node createSettingsIcon() {
        javafx.scene.shape.SVGPath icon = new javafx.scene.shape.SVGPath();
        icon.setContent("M12 15.5C10.07 15.5 8.5 13.93 8.5 12C8.5 10.07 10.07 8.5 12 8.5C13.93 8.5 15.5 10.07 15.5 12C15.5 13.93 13.93 15.5 12 15.5M19.43 12.97C19.47 12.65 19.5 12.33 19.5 12C19.5 11.67 19.47 11.34 19.43 11L21.54 9.37C21.73 9.22 21.78 8.95 21.66 8.73L19.66 5.27C19.54 5.05 19.27 4.96 19.05 5.05L16.56 6.05C16.04 5.66 15.5 5.32 14.87 5.07L14.5 2.42C14.46 2.18 14.25 2 14 2H10C9.75 2 9.54 2.18 9.5 2.42L9.13 5.07C8.5 5.32 7.96 5.66 7.44 6.05L4.95 5.05C4.73 4.96 4.46 5.05 4.34 5.27L2.34 8.73C2.21 8.95 2.27 9.22 2.46 9.37L4.57 11C4.53 11.34 4.5 11.67 4.5 12C4.5 12.33 4.53 12.65 4.57 12.97L2.46 14.63C2.27 14.78 2.21 15.05 2.34 15.27L4.34 18.73C4.46 18.95 4.73 19.03 4.95 18.95L7.44 17.94C7.96 18.34 8.5 18.68 9.13 18.93L9.5 21.58C9.54 21.82 9.75 22 10 22H14C14.25 22 14.46 21.82 14.5 21.58L14.87 18.93C15.5 18.67 16.04 18.34 16.56 17.94L19.05 18.95C19.27 19.03 19.54 18.95 19.66 18.73L21.66 15.27C21.78 15.05 21.73 14.78 21.54 14.63L19.43 12.97Z");
        return icon;
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
        
        statusArea.getChildren().addAll(sendChannelRow, syncChannelRow);
        return statusArea;
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
        // Get parent DesktopMainView and notify
        if (getParent() != null && getParent().getParent() instanceof DesktopMainView) {
            ((DesktopMainView) getParent().getParent()).onReviewPeriodSelected(period);
        }
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
