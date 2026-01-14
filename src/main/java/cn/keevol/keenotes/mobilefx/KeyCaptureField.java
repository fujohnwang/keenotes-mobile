package cn.keevol.keenotes.mobilefx;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import java.util.HashSet;
import java.util.Set;

/**
 * Custom field for capturing keyboard shortcuts
 * Click to enter recording mode, press keys to capture shortcut
 */
public class KeyCaptureField extends HBox {
    
    private final Label displayLabel;
    private boolean isRecording = false;
    private String currentShortcut = "";
    private final Set<KeyCode> pressedKeys = new HashSet<>();
    
    public KeyCaptureField() {
        getStyleClass().add("key-capture-field");
        setAlignment(Pos.CENTER);
        setPadding(new Insets(8, 12, 8, 12));
        setPrefWidth(200);
        setMaxWidth(200);
        
        // Listen to theme changes
        ThemeService.getInstance().currentThemeProperty().addListener((obs, oldTheme, newTheme) -> {
            javafx.application.Platform.runLater(this::updateStyle);
        });
        
        displayLabel = new Label("Click to set");
        displayLabel.getStyleClass().add("key-capture-label");
        displayLabel.setMaxWidth(Double.MAX_VALUE);
        displayLabel.setAlignment(Pos.CENTER);
        HBox.setHgrow(displayLabel, Priority.ALWAYS);
        
        getChildren().add(displayLabel);
        
        // Make focusable
        setFocusTraversable(true);
        
        // Click to enter recording mode
        setOnMouseClicked(e -> startRecording());
        
        // Handle key events
        setOnKeyPressed(this::handleKeyPressed);
        setOnKeyReleased(this::handleKeyReleased);
        
        // Lose focus to stop recording
        focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
            if (!isNowFocused && isRecording) {
                stopRecording(false);
            }
        });
        
        updateStyle();
    }
    
    /**
     * Start recording keyboard shortcut
     */
    private void startRecording() {
        isRecording = true;
        pressedKeys.clear();
        displayLabel.setText("Press keys...");
        updateStyle();
        requestFocus();
    }
    
    /**
     * Stop recording and optionally save the shortcut
     */
    private void stopRecording(boolean save) {
        isRecording = false;
        if (!save) {
            // Restore previous shortcut display
            if (currentShortcut.isEmpty()) {
                displayLabel.setText("Click to set");
            } else {
                displayLabel.setText(currentShortcut);
            }
        }
        pressedKeys.clear();
        updateStyle();
    }
    
    /**
     * Handle key press event
     */
    private void handleKeyPressed(KeyEvent event) {
        if (!isRecording) {
            return;
        }
        
        event.consume();
        KeyCode code = event.getCode();
        
        // ESC to cancel
        if (code == KeyCode.ESCAPE) {
            stopRecording(false);
            return;
        }
        
        // Ignore modifier-only presses
        if (isModifierKey(code)) {
            pressedKeys.add(code);
            updateDisplay();
            return;
        }
        
        // Valid shortcut: at least one modifier + one main key
        if (!pressedKeys.isEmpty() || event.isShortcutDown() || event.isShiftDown() || event.isAltDown()) {
            pressedKeys.add(code);
            String shortcut = buildShortcutString(event, code);
            setShortcut(shortcut);
            stopRecording(true);
        }
    }
    
    /**
     * Handle key release event
     */
    private void handleKeyReleased(KeyEvent event) {
        if (!isRecording) {
            return;
        }
        event.consume();
        pressedKeys.remove(event.getCode());
        updateDisplay();
    }
    
    /**
     * Update display during recording
     */
    private void updateDisplay() {
        if (!isRecording || pressedKeys.isEmpty()) {
            return;
        }
        
        StringBuilder sb = new StringBuilder();
        if (pressedKeys.contains(KeyCode.CONTROL) || pressedKeys.contains(KeyCode.COMMAND) || pressedKeys.contains(KeyCode.META)) {
            sb.append("Ctrl+");
        }
        if (pressedKeys.contains(KeyCode.ALT)) {
            sb.append("Alt+");
        }
        if (pressedKeys.contains(KeyCode.SHIFT)) {
            sb.append("Shift+");
        }
        
        // Find non-modifier key
        for (KeyCode code : pressedKeys) {
            if (!isModifierKey(code)) {
                sb.append(code.getName());
                break;
            }
        }
        
        if (sb.length() > 0) {
            displayLabel.setText(sb.toString());
        } else {
            displayLabel.setText("Press keys...");
        }
    }
    
    /**
     * Build shortcut string from key event
     */
    private String buildShortcutString(KeyEvent event, KeyCode mainKey) {
        StringBuilder sb = new StringBuilder();
        
        // Add modifiers in standard order
        if (event.isControlDown() || event.isShortcutDown() || event.isMetaDown()) {
            sb.append("Ctrl+");
        }
        if (event.isAltDown()) {
            sb.append("Alt+");
        }
        if (event.isShiftDown()) {
            sb.append("Shift+");
        }
        
        // Add main key
        sb.append(mainKey.getName());
        
        return sb.toString();
    }
    
    /**
     * Check if key is a modifier
     */
    private boolean isModifierKey(KeyCode code) {
        return code == KeyCode.CONTROL ||
               code == KeyCode.SHIFT ||
               code == KeyCode.ALT ||
               code == KeyCode.META ||
               code == KeyCode.COMMAND ||
               code == KeyCode.WINDOWS ||
               code == KeyCode.SHORTCUT;
    }
    
    /**
     * Update visual style based on recording state
     */
    private void updateStyle() {
        boolean isDark = ThemeService.getInstance().isDarkTheme();
        String primaryColor = isDark ? "#00D4FF" : "#0969DA";
        String surfaceColor = isDark ? "#161B22" : "#F6F8FA";
        String borderColor = isDark ? "#30363D" : "#D0D7DE";
        String textColor = isDark ? "#E6EDF3" : "#24292F";
        String accentBg = isDark ? "rgba(0, 212, 255, 0.15)" : "rgba(9, 105, 218, 0.15)";
        
        if (isRecording) {
            setStyle("-fx-background-color: " + accentBg + "; " +
                    "-fx-border-color: " + primaryColor + "; " +
                    "-fx-border-width: 2; " +
                    "-fx-border-radius: 8; " +
                    "-fx-background-radius: 8;");
            displayLabel.setStyle("-fx-text-fill: " + primaryColor + "; -fx-font-weight: bold;");
        } else {
            setStyle("-fx-background-color: " + surfaceColor + "; " +
                    "-fx-border-color: " + borderColor + "; " +
                    "-fx-border-width: 1; " +
                    "-fx-border-radius: 8; " +
                    "-fx-background-radius: 8; " +
                    "-fx-cursor: hand;");
            displayLabel.setStyle("-fx-text-fill: " + textColor + ";");
        }
    }
    
    /**
     * Set the shortcut value
     */
    public void setShortcut(String shortcut) {
        this.currentShortcut = shortcut;
        displayLabel.setText(shortcut.isEmpty() ? "Click to set" : formatShortcutForDisplay(shortcut));
    }
    
    /**
     * Get the current shortcut value
     */
    public String getShortcut() {
        return currentShortcut;
    }
    
    /**
     * Format shortcut string for user-friendly display
     * e.g., "Meta+EQUALS" -> "⌘ + =" on macOS, "Ctrl + =" on Windows/Linux
     */
    private String formatShortcutForDisplay(String shortcut) {
        if (shortcut == null || shortcut.isEmpty()) {
            return shortcut;
        }
        
        boolean isMac = System.getProperty("os.name", "").toLowerCase().contains("mac");
        
        String result = shortcut;
        
        // Replace modifier keys with symbols
        if (isMac) {
            result = result.replace("Meta+", "⌘ + ");
            result = result.replace("Ctrl+", "⌃ + ");
            result = result.replace("Alt+", "⌥ + ");
            result = result.replace("Shift+", "⇧ + ");
        } else {
            result = result.replace("Meta+", "Win + ");
            result = result.replace("Ctrl+", "Ctrl + ");
            result = result.replace("Alt+", "Alt + ");
            result = result.replace("Shift+", "Shift + ");
        }
        
        // Replace key names with symbols
        result = result.replace("EQUALS", "=");
        result = result.replace("Equals", "=");
        result = result.replace("MINUS", "-");
        result = result.replace("Minus", "-");
        result = result.replace("PLUS", "+");
        result = result.replace("Plus", "+");
        result = result.replace("SLASH", "/");
        result = result.replace("Slash", "/");
        result = result.replace("BACKSLASH", "\\");
        result = result.replace("Backslash", "\\");
        result = result.replace("COMMA", ",");
        result = result.replace("Comma", ",");
        result = result.replace("PERIOD", ".");
        result = result.replace("Period", ".");
        result = result.replace("SEMICOLON", ";");
        result = result.replace("Semicolon", ";");
        result = result.replace("QUOTE", "'");
        result = result.replace("Quote", "'");
        result = result.replace("OPEN_BRACKET", "[");
        result = result.replace("Open Bracket", "[");
        result = result.replace("CLOSE_BRACKET", "]");
        result = result.replace("Close Bracket", "]");
        result = result.replace("BACK_QUOTE", "`");
        result = result.replace("Back Quote", "`");
        result = result.replace("ENTER", "↵");
        result = result.replace("Enter", "↵");
        result = result.replace("SPACE", "Space");
        result = result.replace("TAB", "Tab");
        result = result.replace("ESCAPE", "Esc");
        result = result.replace("Escape", "Esc");
        result = result.replace("DELETE", "Del");
        result = result.replace("Delete", "Del");
        result = result.replace("BACK_SPACE", "⌫");
        result = result.replace("Backspace", "⌫");
        
        return result;
    }
}
