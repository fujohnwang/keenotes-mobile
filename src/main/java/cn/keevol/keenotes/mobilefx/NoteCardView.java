package cn.keevol.keenotes.mobilefx;

import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Individual note card component for displaying a single note
 */
public class NoteCardView extends VBox {
    
    private final LocalCacheService.NoteData noteData;
    private final Label dateLabel;
    private final Label previewLabel;
    private final Label contentLabel;
    private final VBox contentBox;
    private final Button copyButton;
    private final Label copiedLabel;
    private final HBox actionRow;
    private boolean expanded = false;
    
    public NoteCardView(LocalCacheService.NoteData noteData) {
        this.noteData = noteData;
        
        getStyleClass().add("search-result-card");
        setPadding(new Insets(16));
        setSpacing(8);
        
        // Date label
        dateLabel = new Label(noteData.createdAt);
        dateLabel.getStyleClass().add("note-date");
        
        // Preview label (collapsed state)
        String previewText = getPreviewText(noteData.content, 100);
        previewLabel = new Label(previewText);
        previewLabel.getStyleClass().add("search-result-preview");
        previewLabel.setWrapText(true);
        
        // Full content (expanded state)
        contentLabel = new Label(noteData.content);
        contentLabel.setWrapText(true);
        contentLabel.setMaxWidth(Double.MAX_VALUE);
        contentLabel.getStyleClass().add("search-result-content-text");
        
        contentBox = new VBox(contentLabel);
        contentBox.getStyleClass().add("search-result-content");
        contentBox.setPadding(new Insets(12));
        contentBox.setVisible(false);
        contentBox.setManaged(false);
        
        // Copy button
        copyButton = new Button("Copy");
        copyButton.getStyleClass().add("copy-button");
        copyButton.setOnAction(e -> handleCopy());
        
        copiedLabel = new Label("✓ Copied!");
        copiedLabel.getStyleClass().add("copied-label");
        copiedLabel.setVisible(false);
        copiedLabel.setManaged(false);
        
        actionRow = new HBox(12, copyButton, copiedLabel);
        actionRow.setAlignment(Pos.CENTER_LEFT);
        actionRow.setPadding(new Insets(8, 0, 0, 0));
        actionRow.setVisible(false);
        actionRow.setManaged(false);
        
        getChildren().addAll(dateLabel, previewLabel, contentBox, actionRow);
        
        // Click to expand/collapse
        setOnMouseClicked(e -> {
            if (e.getTarget() == copyButton || copyButton.isHover()) {
                return;
            }
            toggleExpanded();
        });
        
        setCursor(javafx.scene.Cursor.HAND);
    }
    
    private void toggleExpanded() {
        expanded = !expanded;
        
        previewLabel.setVisible(!expanded);
        previewLabel.setManaged(!expanded);
        contentBox.setVisible(expanded);
        contentBox.setManaged(expanded);
        actionRow.setVisible(expanded);
        actionRow.setManaged(expanded);
        
        if (expanded) {
            getStyleClass().add("expanded");
        } else {
            getStyleClass().remove("expanded");
        }
    }
    
    private void handleCopy() {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        ClipboardContent content = new ClipboardContent();
        content.putString(noteData.content);
        clipboard.setContent(content);
        
        copiedLabel.setVisible(true);
        copiedLabel.setManaged(true);
        
        PauseTransition pause = new PauseTransition(Duration.millis(1500));
        pause.setOnFinished(e -> {
            copiedLabel.setVisible(false);
            copiedLabel.setManaged(false);
        });
        pause.play();
    }
    
    private String getPreviewText(String content, int maxLength) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        String preview = content.replace("\n", " ").trim();
        if (preview.length() <= maxLength) {
            return preview;
        }
        return preview.substring(0, maxLength) + "...";
    }
    
    public LocalCacheService.NoteData getNoteData() {
        return noteData;
    }
}
