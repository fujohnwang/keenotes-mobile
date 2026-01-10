package cn.keevol.keenotes.mobilefx;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Individual note card component for displaying a single note
 * Click to copy content to clipboard
 */
public class NoteCardView extends VBox {
    
    private final LocalCacheService.NoteData noteData;
    private final Label copiedPopup;
    
    public NoteCardView(LocalCacheService.NoteData noteData) {
        this.noteData = noteData;
        
        getStyleClass().add("search-result-card");
        setPadding(new Insets(16));
        setSpacing(8);
        
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
        channelLabel.setStyle("-fx-text-fill: #8B949E; -fx-font-size: 12px;");
        
        headerRow.getChildren().addAll(dateLabel, channelLabel);
        
        // Full content (always visible, no height limit)
        Label contentLabel = new Label(noteData.content);
        contentLabel.setWrapText(true);
        contentLabel.setMaxWidth(Double.MAX_VALUE);
        contentLabel.getStyleClass().add("note-content-full");
        contentLabel.setStyle("-fx-text-fill: #E6EDF3; -fx-font-size: 14px; -fx-line-spacing: 2px;");
        
        // Copied popup (initially hidden)
        copiedPopup = new Label("✓ Copied to clipboard");
        copiedPopup.getStyleClass().add("copied-popup");
        copiedPopup.setStyle(
            "-fx-background-color: rgba(0, 212, 255, 0.2); " +
            "-fx-text-fill: #00D4FF; " +
            "-fx-padding: 6 12; " +
            "-fx-background-radius: 4; " +
            "-fx-font-size: 12px;"
        );
        copiedPopup.setVisible(false);
        copiedPopup.setManaged(false);
        copiedPopup.setOpacity(0);
        
        getChildren().addAll(headerRow, contentLabel, copiedPopup);
        
        // Click to copy
        setOnMouseClicked(e -> handleCopy());
        setCursor(javafx.scene.Cursor.HAND);
        
        // Hover effect
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
    
    private void handleCopy() {
        // Copy to clipboard
        Clipboard clipboard = Clipboard.getSystemClipboard();
        ClipboardContent content = new ClipboardContent();
        content.putString(noteData.content);
        clipboard.setContent(content);
        
        // Show popup with fade in/out animation
        copiedPopup.setVisible(true);
        copiedPopup.setManaged(true);
        
        // Fade in
        FadeTransition fadeIn = new FadeTransition(Duration.millis(200), copiedPopup);
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);
        fadeIn.play();
        
        // Fade out after delay
        PauseTransition pause = new PauseTransition(Duration.millis(1500));
        pause.setOnFinished(e -> {
            FadeTransition fadeOut = new FadeTransition(Duration.millis(200), copiedPopup);
            fadeOut.setFromValue(1);
            fadeOut.setToValue(0);
            fadeOut.setOnFinished(ev -> {
                copiedPopup.setVisible(false);
                copiedPopup.setManaged(false);
            });
            fadeOut.play();
        });
        pause.play();
    }
    
    public LocalCacheService.NoteData getNoteData() {
        return noteData;
    }
}
