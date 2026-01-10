package cn.keevol.keenotes.mobilefx;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.*;
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
    
    // Dots animation
    private PauseTransition dotsAnimation;
    private String baseStatusText;
    
    public NoteInputPanel(Consumer<String> onSendNote) {
        this.onSendNote = onSendNote;
        
        getStyleClass().add("note-input-panel");
        setSpacing(0); // Remove internal spacing
        setPadding(new Insets(16, 16, 1, 16)); // Minimal bottom padding (reduced from 2 to 1)
        
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
        
        // Status label (embedded in bottom-left corner of input container)
        statusLabel = new Label();
        statusLabel.getStyleClass().add("unified-status-label");
        statusLabel.setWrapText(true);
        statusLabel.setVisible(false);
        statusLabel.setMaxWidth(400); // Limit width to not overlap with send button
        
        // Position status label in bottom-left corner
        StackPane.setAlignment(statusLabel, Pos.BOTTOM_LEFT);
        StackPane.setMargin(statusLabel, new Insets(0, 0, 12, 12));
        
        inputContainer.getChildren().addAll(noteInput, statusLabel, sendButton);
        
        getChildren().add(inputContainer);
        VBox.setVgrow(inputContainer, Priority.NEVER);
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
     * Show status message with fade-in animation (embedded in input container)
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
}
