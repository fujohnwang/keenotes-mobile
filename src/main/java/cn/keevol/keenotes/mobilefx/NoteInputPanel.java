package cn.keevol.keenotes.mobilefx;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

import java.util.function.Consumer;

/**
 * Modern note input panel with integrated send button
 * Features a unified design with button and status embedded in the input area
 */
public class NoteInputPanel extends VBox {
    
    private final TextArea noteInput;
    private final Button sendButton;
    private final Label statusLabel;
    private final StackPane inputContainer;
    private final Consumer<String> onSendNote;
    
    // Send channel status
    private final Circle sendChannelIndicator;
    private final Label sendChannelLabel;
    
    // Dots animation
    private PauseTransition dotsAnimation;
    private String baseStatusText;
    
    public NoteInputPanel(Consumer<String> onSendNote) {
        this.onSendNote = onSendNote;
        
        getStyleClass().add("note-input-panel");
        setSpacing(0); // Remove internal spacing
        setPadding(new Insets(16, 16, 16, 16)); // Uniform padding on all sides
        
        // Listen to theme changes
        ThemeService.getInstance().currentThemeProperty().addListener((obs, oldTheme, newTheme) -> {
            javafx.application.Platform.runLater(this::updateThemeColors);
        });
        
        // Create the unified input container
        inputContainer = new StackPane();
        inputContainer.getStyleClass().add("unified-input-container");
        inputContainer.setMinHeight(150);
        inputContainer.setMaxHeight(150);
        inputContainer.setPrefHeight(150);
        
        // Note input area
        noteInput = new TextArea();
        noteInput.setPromptText("Write your note here...\nAll content will be encrypted before leaving your device.");
        noteInput.getStyleClass().add("unified-note-input");
        noteInput.setWrapText(true);
        
        // Add keyboard shortcut handler for Send
        noteInput.setOnKeyPressed(event -> {
            String sendShortcut = SettingsService.getInstance().getSendShortcut();
            if (matchesShortcut(event, sendShortcut)) {
                event.consume(); // Prevent default behavior (like newline)
                if (!noteInput.getText().trim().isEmpty()) {
                    handleSend();
                }
            }
        });
        
        // Send button with icon (embedded in bottom-right corner)
        sendButton = new Button("Send");
        sendButton.getStyleClass().addAll("unified-send-button");
        
        // Create paper plane icon (Telegram-style send icon)
        SVGPath sendIcon = new SVGPath();
        // Paper plane path - pointing to upper right
        sendIcon.setContent("M2 21 L23 12 L2 3 L2 10 L17 12 L2 14 Z");
        sendIcon.getStyleClass().add("send-icon");
        sendIcon.setRotate(-30); // Rotate -30 degrees (counter-clockwise) to point upper-right
        // Adjust position to visually center the rotated icon
        sendIcon.setTranslateX(1);
        sendIcon.setTranslateY(-1);
        sendButton.setGraphic(sendIcon);
        sendButton.setContentDisplay(javafx.scene.control.ContentDisplay.LEFT); // Icon on left, text on right
        
        sendButton.setOnAction(e -> handleSend());
        sendButton.disableProperty().bind(noteInput.textProperty().isEmpty());
        
        // Position send button in bottom-right corner
        StackPane.setAlignment(sendButton, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(sendButton, new Insets(0, 8, 8, 0));
        
        // Send Channel status (bottom-left corner)
        sendChannelIndicator = new Circle(4);
        sendChannelIndicator.setFill(Color.web("#3FB950")); // Green by default
        
        sendChannelLabel = new Label("Send Channel: ✓");
        sendChannelLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #3FB950;");
        
        HBox sendChannelBox = new HBox(6, sendChannelIndicator, sendChannelLabel);
        sendChannelBox.setAlignment(Pos.CENTER_LEFT);
        sendChannelBox.getStyleClass().add("send-channel-status");
        
        StackPane.setAlignment(sendChannelBox, Pos.BOTTOM_LEFT);
        StackPane.setMargin(sendChannelBox, new Insets(0, 0, 8, 12)); // Same bottom margin as send button
        
        // Status label (centered in input container for send status)
        statusLabel = new Label();
        statusLabel.getStyleClass().add("unified-status-label");
        statusLabel.setWrapText(true);
        statusLabel.setVisible(false);
        statusLabel.setMaxWidth(300);
        
        // Position status label in center
        StackPane.setAlignment(statusLabel, Pos.CENTER);
        
        inputContainer.getChildren().addAll(noteInput, statusLabel, sendChannelBox, sendButton);
        
        getChildren().add(inputContainer);
        VBox.setVgrow(inputContainer, Priority.NEVER);
        
        // Listen to API service status (simplified - check periodically or on action)
        setupSendChannelListener();
        
        // Initialize theme colors
        updateThemeColors();
    }
    
    /**
     * Setup listener for send channel status
     */
    private void setupSendChannelListener() {
        // Check if settings are configured
        boolean configured = SettingsService.getInstance().isConfigured();
        updateSendChannelStatus(configured);
    }
    
    /**
     * Update send channel status display
     */
    public void updateSendChannelStatus(boolean connected) {
        javafx.application.Platform.runLater(() -> {
            boolean isDark = ThemeService.getInstance().isDarkTheme();
            String successColor = isDark ? "#3FB950" : "#1A7F37";
            String errorColor = isDark ? "#F85149" : "#CF222E";
            
            if (connected) {
                sendChannelIndicator.setFill(Color.web(successColor));
                sendChannelLabel.setText("Send Channel: ✓");
                sendChannelLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + successColor + ";");
            } else {
                sendChannelIndicator.setFill(Color.web(errorColor));
                sendChannelLabel.setText("Send Channel: ✗");
                sendChannelLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + errorColor + ";");
            }
        });
    }
    
    /**
     * Update colors based on current theme
     */
    private void updateThemeColors() {
        // Re-apply current connection status with new theme colors
        boolean isConnected = SettingsService.getInstance().isConfigured();
        updateSendChannelStatus(isConnected);
    }
    
    private void handleSend() {
        String content = noteInput.getText().trim();
        if (!content.isEmpty() && onSendNote != null) {
            onSendNote.accept(content);
        }
    }
    
    /**
     * Clear the input field
     */
    public void clearInput() {
        noteInput.clear();
    }
    
    /**
     * Show status message with fade-in animation (centered in input container)
     * If not an error, shows animated dots
     */
    public void showStatus(String message, boolean isError) {
        stopDotsAnimation();
        
        statusLabel.setText(message);
        statusLabel.getStyleClass().removeAll("error", "success");
        statusLabel.getStyleClass().add(isError ? "error" : "success");
        statusLabel.setVisible(true);
        
        // Fade in animation
        statusLabel.setOpacity(0);
        FadeTransition fadeIn = new FadeTransition(Duration.millis(200), statusLabel);
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);
        fadeIn.play();
        
        // Start dots animation for non-error messages (like "Encrypting and sending...")
        if (!isError && !message.startsWith("✓") && !message.startsWith("✗")) {
            startDotsAnimation(message);
        }
    }
    
    /**
     * Hide status message with fade-out animation
     */
    public void hideStatus() {
        stopDotsAnimation();
        
        FadeTransition fadeOut = new FadeTransition(Duration.millis(200), statusLabel);
        fadeOut.setFromValue(1);
        fadeOut.setToValue(0);
        fadeOut.setOnFinished(e -> {
            statusLabel.setVisible(false);
        });
        fadeOut.play();
    }
    
    /**
     * Start animated dots for status
     */
    private void startDotsAnimation(String baseText) {
        stopDotsAnimation();
        baseStatusText = baseText;
        
        final int[] dotCount = {0};
        dotsAnimation = new PauseTransition(Duration.millis(400));
        dotsAnimation.setOnFinished(e -> {
            dotCount[0] = (dotCount[0] + 1) % 4;
            String dots = ".".repeat(dotCount[0]);
            statusLabel.setText(baseStatusText + dots);
            dotsAnimation.playFromStart();
        });
        dotsAnimation.play();
    }
    
    /**
     * Stop dots animation
     */
    private void stopDotsAnimation() {
        if (dotsAnimation != null) {
            dotsAnimation.stop();
            dotsAnimation = null;
        }
    }
    
    /**
     * Set button enabled state
     */
    public void setSendButtonEnabled(boolean enabled) {
        if (enabled) {
            sendButton.disableProperty().bind(noteInput.textProperty().isEmpty());
        } else {
            sendButton.disableProperty().unbind();
            sendButton.setDisable(true);
        }
    }
    
    /**
     * Request focus on input field
     */
    public void requestInputFocus() {
        noteInput.requestFocus();
    }
    
    /**
     * Check if KeyEvent matches the shortcut string (e.g., "Alt+Enter", "Ctrl+Shift+S")
     */
    private boolean matchesShortcut(javafx.scene.input.KeyEvent event, String shortcut) {
        if (shortcut == null || shortcut.isEmpty()) {
            return false;
        }
        
        String[] parts = shortcut.split("\\+");
        boolean ctrlRequired = false;
        boolean altRequired = false;
        boolean shiftRequired = false;
        String mainKey = "";
        
        for (String part : parts) {
            String normalized = part.trim();
            if (normalized.equalsIgnoreCase("Ctrl") || normalized.equalsIgnoreCase("Control")) {
                ctrlRequired = true;
            } else if (normalized.equalsIgnoreCase("Alt")) {
                altRequired = true;
            } else if (normalized.equalsIgnoreCase("Shift")) {
                shiftRequired = true;
            } else {
                mainKey = normalized;
            }
        }
        
        // Check modifiers match
        if (event.isControlDown() != ctrlRequired) return false;
        if (event.isAltDown() != altRequired) return false;
        if (event.isShiftDown() != shiftRequired) return false;
        
        // Check main key matches
        String eventKeyName = event.getCode().getName();
        return eventKeyName.equalsIgnoreCase(mainKey);
    }
}
