package cn.keevol.keenotes.mobilefx;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Region;

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
        // Logo area
        Label logo = new Label("KeeNotes");
        logo.getStyleClass().add("sidebar-logo");
        
        // Navigation buttons
        noteButton = new NavigationButton("Note", "📝", true);
        noteButton.setOnAction(e -> onNavigationChanged.accept(DesktopMainView.ViewMode.NOTE));
        
        searchButton = new NavigationButton("Search", "🔍", false);
        searchButton.setOnAction(e -> onNavigationChanged.accept(DesktopMainView.ViewMode.SEARCH));
        
        reviewButton = new NavigationButton("Review", "📚", false);
        reviewButton.setOnAction(e -> toggleReviewPanel());
        
        VBox navigationGroup = new VBox(8, noteButton, searchButton, reviewButton);
        navigationGroup.getStyleClass().add("navigation-group");
        
        // Review periods panel (initially hidden)
        reviewPeriodsPanel = new ReviewPeriodsPanel(this::onReviewPeriodSelected);
        reviewPeriodsPanel.setVisible(false);
        reviewPeriodsPanel.setManaged(false);
        
        // Spacer to push settings to bottom
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        
        // Settings button
        settingsButton = new NavigationButton("Settings", "⚙️", false);
        settingsButton.setOnAction(e -> onNavigationChanged.accept(DesktopMainView.ViewMode.SETTINGS));
        
        // Add all components
        getChildren().addAll(
            logo,
            navigationGroup,
            reviewPeriodsPanel,
            spacer,
            settingsButton
        );
    }
    
    /**
     * Toggle review periods panel visibility
     */
    private void toggleReviewPanel() {
        boolean isVisible = reviewPeriodsPanel.isVisible();
        reviewPeriodsPanel.setVisible(!isVisible);
        reviewPeriodsPanel.setManaged(!isVisible);
        
        if (!isVisible) {
            onNavigationChanged.accept(DesktopMainView.ViewMode.REVIEW);
        }
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
        
        // Show/hide review panel based on mode
        if (mode != DesktopMainView.ViewMode.REVIEW) {
            reviewPeriodsPanel.setVisible(false);
            reviewPeriodsPanel.setManaged(false);
        }
    }
}
