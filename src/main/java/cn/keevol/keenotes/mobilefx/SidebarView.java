package cn.keevol.keenotes.mobilefx;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

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
        
        // Bind visibility to settings
        SettingsService settings = SettingsService.getInstance();
        overviewCard.setVisible(settings.getShowOverviewCard());
        overviewCard.setManaged(settings.getShowOverviewCard());
        
        // Navigation buttons with PNG icons
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
        
        // Spacer (no status area at bottom anymore)
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        
        // Add all components (no status area)
        getChildren().addAll(
            logoArea,
            overviewCard,
            navigationGroup,
            spacer
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
