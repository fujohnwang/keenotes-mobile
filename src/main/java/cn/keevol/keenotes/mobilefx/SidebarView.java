package cn.keevol.keenotes.mobilefx;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
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
    
    // Settings sub panel
    private SettingsSubPanel settingsSubPanel;
    
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
        
        // Overview Card (conditionally shown)
        OverviewCard overviewCard = new OverviewCard();
        overviewCard.setMaxWidth(Double.MAX_VALUE);
        
        // Bind visibility to settings property (reactive)
        SettingsService settings = SettingsService.getInstance();
        overviewCard.visibleProperty().bind(settings.showOverviewCardProperty());
        overviewCard.managedProperty().bind(settings.showOverviewCardProperty());
        
        // Add more spacing between logo and overview card
        VBox.setMargin(overviewCard, new Insets(8, 0, 0, 0));
        
        // Navigation buttons with line art icons
        noteButton = new NavigationButton("Take Note", createNoteIcon(), true);
        noteButton.setOnAction(e -> onNavigationChanged.accept(DesktopMainView.ViewMode.NOTE));
        
        reviewButton = new NavigationButton("Review Notes", createReviewIcon(), false);
        reviewButton.setOnAction(e -> toggleReviewPanel());
        
        // Review periods panel (initially hidden, placed right after Review button)
        reviewPeriodsPanel = new ReviewPeriodsPanel(this::onReviewPeriodSelected);
        reviewPeriodsPanel.setVisible(false);
        reviewPeriodsPanel.setManaged(false);
        
        searchButton = new NavigationButton("Search Notes", createSearchIcon(), false);
        searchButton.setOnAction(e -> onNavigationChanged.accept(DesktopMainView.ViewMode.SEARCH));
        
        settingsButton = new NavigationButton("Settings", createSettingsIcon(), false);
        settingsButton.setOnAction(e -> toggleSettingsPanel());
        
        // Settings sub panel (initially hidden, placed right after Settings button)
        settingsSubPanel = new SettingsSubPanel(this::onSettingsSubItemSelected);
        settingsSubPanel.setVisible(false);
        settingsSubPanel.setManaged(false);
        
        VBox navigationGroup = new VBox(6, noteButton, reviewButton, reviewPeriodsPanel, searchButton, settingsButton, settingsSubPanel);
        navigationGroup.getStyleClass().add("navigation-group");
        
        // Add top margin to navigation group for breathing room
        VBox.setMargin(navigationGroup, new Insets(12, 0, 0, 0));
        
        // Spacer
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        
        // Update notification area (initially hidden)
        VBox updateNotification = createUpdateNotification();
        updateNotification.setVisible(false);
        updateNotification.setManaged(false);
        
        // Add all components
        getChildren().addAll(
            logoArea,
            overviewCard,
            navigationGroup,
            spacer,
            updateNotification
        );
    }
    
    /**
     * Create update notification area
     */
    private VBox createUpdateNotification() {
        VBox notification = new VBox(8);
        notification.getStyleClass().add("update-notification");
        notification.setPadding(new Insets(12));
        notification.setStyle("-fx-background-color: rgba(0, 212, 255, 0.1); " +
                             "-fx-background-radius: 8; " +
                             "-fx-border-color: #00D4FF; " +
                             "-fx-border-width: 1; " +
                             "-fx-border-radius: 8;");
        
        Label titleLabel = new Label("🎉 New Version Available");
        titleLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #00D4FF; -fx-font-size: 12px;");
        
        Label versionLabel = new Label();
        versionLabel.setStyle("-fx-text-fill: #8B949E; -fx-font-size: 11px;");
        versionLabel.setUserData("version"); // Tag for later update
        
        Label downloadLabel = new Label("Click to download");
        downloadLabel.setStyle("-fx-text-fill: #00D4FF; -fx-font-size: 11px; -fx-cursor: hand; -fx-underline: true;");
        downloadLabel.setUserData("download"); // Tag for later update
        
        notification.getChildren().addAll(titleLabel, versionLabel, downloadLabel);
        notification.setUserData(notification); // Store reference for showUpdateNotification
        
        return notification;
    }
    
    /**
     * Show update notification with version info
     */
    public void showUpdateNotification(String version, String downloadUrl) {
        // Find update notification in children
        VBox updateNotification = null;
        for (javafx.scene.Node child : getChildren()) {
            if (child instanceof VBox && child.getStyleClass().contains("update-notification")) {
                updateNotification = (VBox) child;
                break;
            }
        }
        
        if (updateNotification == null) {
            return;
        }
        
        // Update version label
        for (javafx.scene.Node node : updateNotification.getChildren()) {
            if (node instanceof Label) {
                Label label = (Label) node;
                if ("version".equals(label.getUserData())) {
                    label.setText("Version " + version);
                } else if ("download".equals(label.getUserData())) {
                    label.setOnMouseClicked(e -> {
                        try {
                            java.awt.Desktop.getDesktop().browse(new java.net.URI(downloadUrl));
                        } catch (Exception ex) {
                            System.err.println("Failed to open download URL: " + ex.getMessage());
                        }
                    });
                }
            }
        }
        
        // Show notification
        updateNotification.setVisible(true);
        updateNotification.setManaged(true);
    }
    
    /**
     * Create logo area with icon and bold text
     */
    private HBox createLogoArea() {
        HBox logoArea = new HBox(10); // Increased spacing
        logoArea.setAlignment(Pos.CENTER_LEFT);
        logoArea.setPadding(new Insets(0, 0, 16, 0)); // Increased bottom padding
        
        // Load KeeNotes icon from resources
        try {
            javafx.scene.image.Image iconImage = new javafx.scene.image.Image(
                getClass().getResourceAsStream("/icons/app-icon.png")
            );
            javafx.scene.image.ImageView iconView = new javafx.scene.image.ImageView(iconImage);
            iconView.setFitWidth(32); // Slightly larger icon
            iconView.setFitHeight(32);
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
     * Create note icon - simple pen/pencil line art
     */
    private javafx.scene.Node createNoteIcon() {
        javafx.scene.shape.SVGPath icon = new javafx.scene.shape.SVGPath();
        // Pen/pencil icon - simple line art
        icon.setContent("M20.71 7.04c.39-.39.39-1.04 0-1.41l-2.34-2.34c-.37-.39-1.02-.39-1.41 0l-1.84 1.83 3.75 3.75M3 17.25V21h3.75L17.81 9.93l-3.75-3.75L3 17.25z");
        icon.setFill(Color.web("#8B949E"));
        icon.setScaleX(0.7);
        icon.setScaleY(0.7);
        return icon;
    }
    
    /**
     * Create review icon - simple clock/history line art
     */
    private javafx.scene.Node createReviewIcon() {
        javafx.scene.shape.SVGPath icon = new javafx.scene.shape.SVGPath();
        // Clock/history icon - simple line art
        icon.setContent("M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10 10-4.5 10-10S17.5 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8zm.5-13H11v6l5.2 3.2.8-1.3-4.5-2.7V7z");
        icon.setFill(Color.web("#8B949E"));
        icon.setScaleX(0.7);
        icon.setScaleY(0.7);
        return icon;
    }
    
    /**
     * Create search icon - simple magnifying glass line art
     */
    private javafx.scene.Node createSearchIcon() {
        javafx.scene.shape.SVGPath icon = new javafx.scene.shape.SVGPath();
        // Magnifying glass icon - simple line art
        icon.setContent("M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z");
        icon.setFill(Color.web("#8B949E"));
        icon.setScaleX(0.7);
        icon.setScaleY(0.7);
        return icon;
    }
    
    /**
     * Create settings icon - simple gear line art
     */
    private javafx.scene.Node createSettingsIcon() {
        javafx.scene.shape.SVGPath icon = new javafx.scene.shape.SVGPath();
        // Gear icon - simple line art
        icon.setContent("M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z");
        icon.setFill(Color.web("#8B949E"));
        icon.setScaleX(0.7);
        icon.setScaleY(0.7);
        return icon;
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
     * Toggle settings sub panel visibility
     */
    private void toggleSettingsPanel() {
        boolean isVisible = settingsSubPanel.isVisible();
        settingsSubPanel.setVisible(!isVisible);
        settingsSubPanel.setManaged(!isVisible);
        
        // Always notify mode change to SETTINGS when clicking the button
        onNavigationChanged.accept(DesktopMainView.ViewMode.SETTINGS);
    }
    
    /**
     * Handle settings sub item selection
     */
    private void onSettingsSubItemSelected(String subItem) {
        System.out.println("[SidebarView] Settings sub item selected: " + subItem);
        
        // Navigate up the scene graph to find DesktopMainView
        javafx.scene.Node node = this;
        while (node != null) {
            if (node instanceof DesktopMainView) {
                System.out.println("[SidebarView] Found DesktopMainView, calling onSettingsSubItemSelected");
                ((DesktopMainView) node).onSettingsSubItemSelected(subItem);
                return;
            }
            node = node.getParent();
        }
        System.err.println("[SidebarView] ERROR: Could not find DesktopMainView in parent hierarchy");
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
        
        // Show settings panel when in SETTINGS mode, hide for other modes
        if (mode == DesktopMainView.ViewMode.SETTINGS) {
            settingsSubPanel.setVisible(true);
            settingsSubPanel.setManaged(true);
        } else {
            settingsSubPanel.setVisible(false);
            settingsSubPanel.setManaged(false);
        }
    }
}
