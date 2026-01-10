package cn.keevol.keenotes.mobilefx;

import javafx.geometry.Insets;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Desktop-style main view with sidebar navigation
 */
public class DesktopMainView extends BorderPane {
    
    // Components
    private final SidebarView sidebar;
    private final MainContentArea mainContent;
    
    // Current mode
    private ViewMode currentMode = ViewMode.NOTE;
    
    public enum ViewMode {
        NOTE,       // Note input and recent notes
        SEARCH,     // Search notes
        REVIEW,     // Review notes by period
        SETTINGS    // Settings
    }
    
    public DesktopMainView() {
        getStyleClass().add("desktop-main-view");
        
        // Create components
        sidebar = new SidebarView(this::onNavigationChanged);
        mainContent = new MainContentArea();
        
        // Setup layout
        setupLayout();
        
        // Initialize with Note mode
        switchToMode(ViewMode.NOTE);
    }
    
    private void setupLayout() {
        // Left: Sidebar (fixed width ~250px)
        setLeft(sidebar);
        sidebar.setPrefWidth(250);
        sidebar.setMinWidth(250);
        sidebar.setMaxWidth(250);
        
        // Center: Main content area (flexible, no footer)
        setCenter(mainContent);
    }
    
    /**
     * Handle navigation button clicks
     */
    private void onNavigationChanged(ViewMode mode) {
        if (currentMode != mode) {
            switchToMode(mode);
        }
    }
    
    /**
     * Switch to a different view mode
     */
    private void switchToMode(ViewMode mode) {
        switchToMode(mode, true);
    }
    
    /**
     * Switch to a different view mode
     * @param mode The view mode to switch to
     * @param loadDefaultData Whether to load default data for the mode
     */
    private void switchToMode(ViewMode mode, boolean loadDefaultData) {
        System.out.println("[DesktopMainView] Switching to mode: " + mode + ", loadDefaultData: " + loadDefaultData);
        currentMode = mode;
        
        // Update sidebar selection
        sidebar.setSelectedMode(mode);
        
        // Update main content
        mainContent.showMode(mode);
        
        // Load data for specific modes
        if (mode == ViewMode.REVIEW && loadDefaultData) {
            // Load default period (7 days) when entering review mode
            mainContent.loadReviewNotes("7 days");
        }
    }
    
    /**
     * Handle review period selection
     */
    public void onReviewPeriodSelected(String period) {
        System.out.println("[DesktopMainView] Review period selected: " + period);
        // Switch to REVIEW mode without loading default data
        switchToMode(ViewMode.REVIEW, false);
        // Load the selected period
        mainContent.loadReviewNotes(period);
    }
    
    /**
     * Get current view mode
     */
    public ViewMode getCurrentMode() {
        return currentMode;
    }
    
    /**
     * Switch to Note mode (public method for external calls)
     */
    public void switchToNoteMode() {
        switchToMode(ViewMode.NOTE);
    }
    
    /**
     * Get the sidebar (for status updates from Main)
     */
    public SidebarView getSidebar() {
        return sidebar;
    }
}
