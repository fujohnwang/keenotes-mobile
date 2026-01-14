package cn.keevol.keenotes.mobilefx;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.util.Duration;

/**
 * Individual note card component for displaying a single note
 * Click card to copy entire content to clipboard
 * Select text with mouse, right-click or Ctrl+C to copy selection
 */
public class NoteCardView extends StackPane {
    
    private final LocalCacheService.NoteData noteData;
    private final Label copiedPopup;
    private final TextArea contentArea;
    private final SettingsService settings;
    private final Text textMeasure; // Hidden text node for measuring height
    
    public NoteCardView(LocalCacheService.NoteData noteData) {
        this.noteData = noteData;
        this.settings = SettingsService.getInstance();
        
        getStyleClass().add("search-result-card");
        
        // Listen to theme changes
        ThemeService.getInstance().currentThemeProperty().addListener((obs, oldTheme, newTheme) -> {
            javafx.application.Platform.runLater(this::updateThemeColors);
        });
        
        // Listen to font size changes
        settings.noteFontSizeProperty().addListener((obs, oldSize, newSize) -> {
            javafx.application.Platform.runLater(() -> updateFontSize(newSize.intValue()));
        });
        
        // Main content container
        VBox contentBox = new VBox(8);
        contentBox.setPadding(new Insets(16));
        
        // Header row: date and channel
        HBox headerRow = new HBox(12);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        
        Label dateLabel = new Label(noteData.createdAt);
        dateLabel.getStyleClass().add("note-date");
        
        // Channel/source label
        String channelText = (noteData.channel != null && !noteData.channel.isEmpty()) 
            ? noteData.channel 
            : "default";
        Label channelLabel = new Label("• " + channelText);
        channelLabel.getStyleClass().add("note-channel");
        
        boolean isDark = ThemeService.getInstance().isDarkTheme();
        String secondaryColor = isDark ? "#8B949E" : "#57606A";
        channelLabel.setStyle("-fx-text-fill: " + secondaryColor + "; -fx-font-size: 12px;");
        
        headerRow.getChildren().addAll(dateLabel, channelLabel);
        
        // Full content using TextArea (read-only, selectable)
        contentArea = new TextArea(noteData.content);
        contentArea.setEditable(false);
        contentArea.setWrapText(true);
        contentArea.getStyleClass().add("note-content-area");
        
        String textColor = isDark ? "#E6EDF3" : "#24292F";
        int fontSize = settings.getNoteFontSize();
        contentArea.setStyle(
            "-fx-control-inner-background: transparent; " +
            "-fx-background-color: transparent; " +
            "-fx-text-fill: " + textColor + "; " +
            "-fx-font-size: " + fontSize + "px; " +
            "-fx-border-width: 0; " +
            "-fx-focus-color: transparent; " +
            "-fx-faint-focus-color: transparent; " +
            "-fx-cursor: hand;"
        );
        
        // Disable scrollbars completely
        contentArea.setScrollTop(0);
        contentArea.setScrollLeft(0);
        
        // Create hidden Text node for accurate height measurement
        textMeasure = new Text();
        textMeasure.setFont(Font.font("MiSans", fontSize)); // Use same font family
        textMeasure.setWrappingWidth(500); // Will be updated based on actual width
        textMeasure.textProperty().bind(contentArea.textProperty());
        
        // Bind TextArea height to Text measurement
        textMeasure.layoutBoundsProperty().addListener((obs, oldBounds, newBounds) -> {
            double textHeight = newBounds.getHeight();
            double padding = 20; // Minimal padding
            double height = Math.max(30, textHeight + padding);
            contentArea.setPrefHeight(height);
            contentArea.setMinHeight(height);
            // Re-hide scrollbars after height change
            javafx.application.Platform.runLater(this::hideScrollBars);
        });
        
        // Update wrapping width when card width changes
        widthProperty().addListener((obs, oldWidth, newWidth) -> {
            if (newWidth.doubleValue() > 64) { // Ensure we have valid width
                // Card padding (16*2) + content box padding (16*2) = 64
                double wrappingWidth = newWidth.doubleValue() - 64;
                textMeasure.setWrappingWidth(wrappingWidth);
            }
        });
        
        // Initial wrapping width setup after layout
        javafx.application.Platform.runLater(() -> {
            if (getWidth() > 64) {
                textMeasure.setWrappingWidth(getWidth() - 64);
            }
            hideScrollBars();
        });
        
        // Custom context menu for copy
        ContextMenu contextMenu = new ContextMenu();
        contextMenu.getStyleClass().add("note-context-menu");
        MenuItem copyItem = new MenuItem("Copy");
        copyItem.setOnAction(e -> copySelectedOrAll());
        MenuItem copyAllItem = new MenuItem("Copy All");
        copyAllItem.setOnAction(e -> handleCopy());
        contextMenu.getItems().addAll(copyItem, copyAllItem);
        contentArea.setContextMenu(contextMenu);
        
        // Keyboard shortcut Ctrl+C / Cmd+C for copy
        contentArea.setOnKeyPressed(e -> {
            if (new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN).match(e)) {
                copySelectedOrAll();
                e.consume();
            }
        });
        
        // Click on TextArea (when no text selected) to copy all
        contentArea.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 1) {
                String selected = contentArea.getSelectedText();
                if (selected == null || selected.isEmpty()) {
                    handleCopy();
                }
            }
        });
        
        contentBox.getChildren().addAll(headerRow, contentArea);
        
        // Copied popup (positioned at top-right)
        copiedPopup = new Label("✓ Copied");
        copiedPopup.getStyleClass().add("copied-popup");
        
        boolean isDarkPopup = ThemeService.getInstance().isDarkTheme();
        String primaryColor = isDarkPopup ? "#00D4FF" : "#0969DA";
        String popupBg = isDarkPopup ? "rgba(0, 212, 255, 0.2)" : "rgba(9, 105, 218, 0.2)";
        copiedPopup.setStyle(
            "-fx-background-color: " + popupBg + "; " +
            "-fx-text-fill: " + primaryColor + "; " +
            "-fx-padding: 4 8; " +
            "-fx-background-radius: 4; " +
            "-fx-font-size: 11px;"
        );
        copiedPopup.setVisible(false);
        copiedPopup.setOpacity(0);
        StackPane.setAlignment(copiedPopup, Pos.TOP_RIGHT);
        StackPane.setMargin(copiedPopup, new Insets(8, 8, 0, 0));
        
        getChildren().addAll(contentBox, copiedPopup);
        
        // Click anywhere on card (outside TextArea) to copy all
        setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                handleCopy();
            }
        });
        setCursor(javafx.scene.Cursor.HAND);
        
        // Hover effect on card
        setOnMouseEntered(e -> {
            setStyle("-fx-background-color: rgba(255, 255, 255, 0.05); " +
                    "-fx-border-color: #30363D; " +
                    "-fx-border-radius: 6; " +
                    "-fx-background-radius: 6;");
        });
        
        setOnMouseExited(e -> {
            setStyle("");
        });
    }
    
    /**
     * Hide scrollbars from TextArea
     */
    private void hideScrollBars() {
        javafx.scene.Node scrollPane = contentArea.lookup(".scroll-pane");
        if (scrollPane != null) {
            scrollPane.setStyle("-fx-background-color: transparent;");
        }
        javafx.scene.Node vbar = contentArea.lookup(".scroll-bar:vertical");
        javafx.scene.Node hbar = contentArea.lookup(".scroll-bar:horizontal");
        if (vbar != null) {
            vbar.setVisible(false);
            vbar.setManaged(false);
        }
        if (hbar != null) {
            hbar.setVisible(false);
            hbar.setManaged(false);
        }
    }
    
    /**
     * Copy selected text, or all content if nothing selected
     */
    private void copySelectedOrAll() {
        String selected = contentArea.getSelectedText();
        if (selected != null && !selected.isEmpty()) {
            // Copy selection
            Clipboard clipboard = Clipboard.getSystemClipboard();
            ClipboardContent content = new ClipboardContent();
            content.putString(selected);
            clipboard.setContent(content);
            showCopiedPopup();
            // Deselect text after copy
            contentArea.deselect();
        } else {
            // Copy all
            handleCopy();
        }
    }
    
    private void handleCopy() {
        // Copy entire content to clipboard
        Clipboard clipboard = Clipboard.getSystemClipboard();
        ClipboardContent content = new ClipboardContent();
        content.putString(noteData.content);
        clipboard.setContent(content);
        
        showCopiedPopup();
        // Deselect text after copy
        contentArea.deselect();
    }
    
    private void showCopiedPopup() {
        // Show popup with fade in/out animation
        copiedPopup.setVisible(true);
        
        // Fade in
        FadeTransition fadeIn = new FadeTransition(Duration.millis(150), copiedPopup);
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);
        fadeIn.play();
        
        // Fade out after delay
        PauseTransition pause = new PauseTransition(Duration.millis(1200));
        pause.setOnFinished(e -> {
            FadeTransition fadeOut = new FadeTransition(Duration.millis(150), copiedPopup);
            fadeOut.setFromValue(1);
            fadeOut.setToValue(0);
            fadeOut.setOnFinished(ev -> {
                copiedPopup.setVisible(false);
            });
            fadeOut.play();
        });
        pause.play();
    }
    
    public LocalCacheService.NoteData getNoteData() {
        return noteData;
    }
    
    /**
     * Update colors based on current theme
     */
    private void updateThemeColors() {
        boolean isDark = ThemeService.getInstance().isDarkTheme();
        String secondaryColor = isDark ? "#8B949E" : "#57606A";
        String textColor = isDark ? "#E6EDF3" : "#24292F";
        String primaryColor = isDark ? "#00D4FF" : "#0969DA";
        String popupBg = isDark ? "rgba(0, 212, 255, 0.2)" : "rgba(9, 105, 218, 0.2)";
        
        // Update channel label color
        if (getChildren().size() > 0 && getChildren().get(0) instanceof VBox) {
            VBox contentBox = (VBox) getChildren().get(0);
            if (contentBox.getChildren().size() > 0 && contentBox.getChildren().get(0) instanceof HBox) {
                HBox headerRow = (HBox) contentBox.getChildren().get(0);
                if (headerRow.getChildren().size() > 1) {
                    javafx.scene.Node channelNode = headerRow.getChildren().get(1);
                    if (channelNode instanceof Label) {
                        ((Label) channelNode).setStyle("-fx-text-fill: " + secondaryColor + "; -fx-font-size: 12px;");
                    }
                }
            }
        }
        
        // Update content area text color
        int fontSize = settings.getNoteFontSize();
        contentArea.setStyle(
            "-fx-control-inner-background: transparent; " +
            "-fx-background-color: transparent; " +
            "-fx-text-fill: " + textColor + "; " +
            "-fx-font-size: " + fontSize + "px; " +
            "-fx-border-width: 0; " +
            "-fx-focus-color: transparent; " +
            "-fx-faint-focus-color: transparent; " +
            "-fx-cursor: hand;"
        );
        
        // Update copied popup colors
        copiedPopup.setStyle(
            "-fx-background-color: " + popupBg + "; " +
            "-fx-text-fill: " + primaryColor + "; " +
            "-fx-padding: 4 8; " +
            "-fx-background-radius: 4; " +
            "-fx-font-size: 11px; " +
            "-fx-font-weight: bold;"
        );
    }
    
    /**
     * Update font size for content area
     */
    private void updateFontSize(int fontSize) {
        boolean isDark = ThemeService.getInstance().isDarkTheme();
        String textColor = isDark ? "#E6EDF3" : "#24292F";
        contentArea.setStyle(
            "-fx-control-inner-background: transparent; " +
            "-fx-background-color: transparent; " +
            "-fx-text-fill: " + textColor + "; " +
            "-fx-font-size: " + fontSize + "px; " +
            "-fx-border-width: 0; " +
            "-fx-focus-color: transparent; " +
            "-fx-faint-focus-color: transparent; " +
            "-fx-cursor: hand;"
        );
        // Update text measure font
        textMeasure.setFont(Font.font("MiSans", fontSize));
    }
}
