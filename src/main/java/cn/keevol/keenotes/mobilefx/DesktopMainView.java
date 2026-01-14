package cn.keevol.keenotes.mobilefx;

import javafx.geometry.Insets;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
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
        
        // Setup keyboard shortcuts
        setupKeyboardShortcuts();
        
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
     * Setup keyboard shortcuts
     */
    private void setupKeyboardShortcuts() {
        // Register key event handler on the scene when it becomes available
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                newScene.setOnKeyPressed(event -> {
                    SettingsService settings = SettingsService.getInstance();
                    
                    // Search shortcut
                    KeyCodeCombination searchShortcut = parseShortcut(settings.getSearchShortcut());
                    if (searchShortcut != null && searchShortcut.match(event)) {
                        event.consume();
                        switchToSearchAndFocus();
                        return;
                    }
                    
                    // Zoom in shortcut (CMD+= or CMD++)
                    KeyCodeCombination zoomInShortcut = parseShortcut(settings.getZoomInShortcut());
                    if (zoomInShortcut != null && zoomInShortcut.match(event)) {
                        event.consume();
                        settings.zoomIn();
                        return;
                    }
                    
                    // Zoom out shortcut (CMD+-)
                    KeyCodeCombination zoomOutShortcut = parseShortcut(settings.getZoomOutShortcut());
                    if (zoomOutShortcut != null && zoomOutShortcut.match(event)) {
                        event.consume();
                        settings.zoomOut();
                        return;
                    }
                });
            }
        });
    }
    
    /**
     * Parse shortcut string to KeyCodeCombination
     * Format: "Alt+Shift+S", "Ctrl+Shift+F", etc.
     */
    private KeyCodeCombination parseShortcut(String shortcut) {
        if (shortcut == null || shortcut.isEmpty()) {
            return null;
        }
        
        try {
            String[] parts = shortcut.split("\\+");
            if (parts.length < 2) {
                return null;
            }
            
            // Last part is the key
            String keyPart = parts[parts.length - 1].trim().toUpperCase();
            KeyCode keyCode = KeyCode.valueOf(keyPart);
            
            // Parse modifiers
            KeyCombination.Modifier[] modifiers = new KeyCombination.Modifier[parts.length - 1];
            for (int i = 0; i < parts.length - 1; i++) {
                String mod = parts[i].trim().toLowerCase();
                modifiers[i] = switch (mod) {
                    case "alt" -> KeyCombination.ALT_DOWN;
                    case "ctrl", "control" -> KeyCombination.CONTROL_DOWN;
                    case "shift" -> KeyCombination.SHIFT_DOWN;
                    case "meta", "cmd", "command" -> KeyCombination.META_DOWN;
                    default -> null;
                };
                if (modifiers[i] == null) {
                    return null;
                }
            }
            
            return new KeyCodeCombination(keyCode, modifiers);
        } catch (Exception e) {
            System.err.println("[DesktopMainView] Failed to parse shortcut: " + shortcut + " - " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Switch to search mode and focus on search input
     */
    public void switchToSearchAndFocus() {
        switchToMode(ViewMode.SEARCH);
        // Request focus on search input after mode switch
        javafx.application.Platform.runLater(() -> {
            mainContent.focusSearchInput();
        });
    }
    
    /**
     * Handle navigation button clicks
     */
    private void onNavigationChanged(ViewMode mode) {
        if (mode == ViewMode.SEARCH) {
            // Always focus search input when switching to search mode
            switchToSearchAndFocus();
        } else if (currentMode != mode) {
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
