package cn.keevol.keenotes.mobilefx;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Preferences settings view (UI preferences, shortcuts, etc.)
 */
public class SettingsPreferencesView extends VBox {
    
    private final SettingsService settings;
    private final ToggleSwitch copyToClipboardToggle;
    private final ToggleSwitch showOverviewCardToggle;
    private final ToggleSwitch themeToggle;
    private final KeyCaptureField searchShortcutField;
    private final KeyCaptureField sendShortcutField;
    private final KeyCaptureField zoomInShortcutField;
    private final KeyCaptureField zoomOutShortcutField;
    
    public SettingsPreferencesView() {
        this.settings = SettingsService.getInstance();
        
        setPadding(new Insets(24));
        setSpacing(16);
        setAlignment(Pos.TOP_CENTER);
        
        // Copy to clipboard toggle
        copyToClipboardToggle = new ToggleSwitch();
        copyToClipboardToggle.selectedProperty().addListener((obs, oldVal, newVal) -> {
            settings.setCopyToClipboardOnPost(newVal);
            settings.save();
        });

        // Show Overview Card toggle
        showOverviewCardToggle = new ToggleSwitch();
        showOverviewCardToggle.selectedProperty().addListener((obs, oldVal, newVal) -> {
            settings.setShowOverviewCard(newVal);
            settings.save();
        });

        // Theme toggle
        themeToggle = new ToggleSwitch();
        themeToggle.selectedProperty().addListener((obs, oldVal, newVal) -> {
            ThemeService.Theme theme = newVal ? ThemeService.Theme.LIGHT : ThemeService.Theme.DARK;
            ThemeService.getInstance().setTheme(theme);
        });

        // Search shortcut
        searchShortcutField = new KeyCaptureField();
        searchShortcutField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
            if (wasFocused && !isNowFocused) {
                String shortcut = searchShortcutField.getShortcut();
                if (shortcut != null && !shortcut.isEmpty()) {
                    settings.setSearchShortcut(shortcut);
                    settings.save();
                }
            }
        });

        // Send shortcut
        sendShortcutField = new KeyCaptureField();
        sendShortcutField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
            if (wasFocused && !isNowFocused) {
                String shortcut = sendShortcutField.getShortcut();
                if (shortcut != null && !shortcut.isEmpty()) {
                    settings.setSendShortcut(shortcut);
                    settings.save();
                }
            }
        });

        // Zoom in shortcut
        zoomInShortcutField = new KeyCaptureField();
        zoomInShortcutField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
            if (wasFocused && !isNowFocused) {
                String shortcut = zoomInShortcutField.getShortcut();
                if (shortcut != null && !shortcut.isEmpty()) {
                    settings.setZoomInShortcut(shortcut);
                    settings.save();
                }
            }
        });

        // Zoom out shortcut
        zoomOutShortcutField = new KeyCaptureField();
        zoomOutShortcutField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
            if (wasFocused && !isNowFocused) {
                String shortcut = zoomOutShortcutField.getShortcut();
                if (shortcut != null && !shortcut.isEmpty()) {
                    settings.setZoomOutShortcut(shortcut);
                    settings.save();
                }
            }
        });

        // Build UI
        getChildren().addAll(
            createToggleRow("Copy to clipboard on post success", copyToClipboardToggle),
            createToggleRow("Show Overview Card", showOverviewCardToggle),
            createToggleRow("Light Theme", themeToggle),
            createShortcutRow("Search shortcut", searchShortcutField, 
                "Click the field and press your desired key combination (e.g., Ctrl+Shift+F)"),
            createShortcutRow("Send shortcut", sendShortcutField,
                "Click the field and press your desired key combination (e.g., Alt+Enter)"),
            createShortcutRow("Zoom in shortcut", zoomInShortcutField,
                "Increase Note Card font size (e.g., Meta+EQUALS)"),
            createShortcutRow("Zoom out shortcut", zoomOutShortcutField,
                "Decrease Note Card font size (e.g., Meta+MINUS)")
        );
        
        loadSettings();
    }
    
    private HBox createToggleRow(String labelText, ToggleSwitch toggle) {
        Label label = new Label(labelText);
        label.getStyleClass().add("field-label");
        label.setMinWidth(259);
        label.setMaxWidth(259);
        label.setAlignment(Pos.CENTER_RIGHT);

        HBox row = new HBox(16, label, toggle);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }
    
    private HBox createShortcutRow(String labelText, KeyCaptureField field, String hintText) {
        Label label = new Label(labelText);
        label.getStyleClass().add("field-label");
        label.setAlignment(Pos.CENTER_RIGHT);

        Label hint = new Label(hintText);
        hint.getStyleClass().add("field-hint");

        VBox fieldWithHint = new VBox(6, field, hint);
        HBox.setHgrow(fieldWithHint, javafx.scene.layout.Priority.ALWAYS);

        VBox labelWrapper = new VBox(label);
        labelWrapper.setAlignment(Pos.TOP_RIGHT);
        labelWrapper.setPadding(new Insets(8, 0, 0, 0));
        labelWrapper.setMinWidth(259);
        labelWrapper.setMaxWidth(259);

        HBox row = new HBox(16, labelWrapper, fieldWithHint);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }
    
    private void loadSettings() {
        copyToClipboardToggle.setSelected(settings.getCopyToClipboardOnPost());
        showOverviewCardToggle.setSelected(settings.getShowOverviewCard());
        themeToggle.setSelected(ThemeService.getInstance().getCurrentTheme() == ThemeService.Theme.LIGHT);
        searchShortcutField.setShortcut(settings.getSearchShortcut());
        sendShortcutField.setShortcut(settings.getSendShortcut());
        zoomInShortcutField.setShortcut(settings.getZoomInShortcut());
        zoomOutShortcutField.setShortcut(settings.getZoomOutShortcut());
    }
}
