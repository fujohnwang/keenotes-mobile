package cn.keevol.keenotes.mobilefx;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;

/**
 * Review view for displaying notes from the past N days.
 */
public class ReviewView extends BorderPane {

    private final TextField daysField;
    private final VBox notesContainer;
    private final ApiService apiService;
    private final SettingsService settings;

    public ReviewView() {
        this.apiService = new ApiService();
        this.settings = SettingsService.getInstance();
        getStyleClass().add("main-view");

        // Header with days input and refresh button
        setTop(createHeader());

        // Notes list
        notesContainer = new VBox(12);
        notesContainer.setPadding(new Insets(16));
        notesContainer.getStyleClass().add("notes-container");

        ScrollPane scrollPane = new ScrollPane(notesContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("content-scroll");
        setCenter(scrollPane);

        // Initialize days field
        daysField = (TextField) ((HBox) getTop()).getChildren().get(0);
        
        // Load notes on init
        Platform.runLater(this::loadNotes);
    }

    private HBox createHeader() {
        // Days input field
        TextField daysInput = new TextField(String.valueOf(settings.getReviewDays()));
        daysInput.setPromptText("天数");
        daysInput.getStyleClass().add("days-input");
        daysInput.setPrefWidth(60);

        // Save days when changed
        daysInput.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (!newVal) {
                saveDays(daysInput.getText());
            }
        });

        Label daysLabel = new Label("天内的笔记");
        daysLabel.getStyleClass().add("days-label");

        // Refresh button
        Button refreshBtn = new Button("刷新");
        refreshBtn.getStyleClass().add("refresh-button");
        refreshBtn.setOnAction(e -> {
            saveDays(daysInput.getText());
            loadNotes();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(8, daysInput, daysLabel, spacer, refreshBtn);
        header.getStyleClass().add("header");
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(8, 12, 8, 12));
        return header;
    }

    private void saveDays(String text) {
        try {
            int days = Integer.parseInt(text.trim());
            if (days > 0 && days <= 365) {
                settings.setReviewDays(days);
                settings.save();
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private void loadNotes() {
        notesContainer.getChildren().clear();

        Label loadingLabel = new Label("加载中...");
        loadingLabel.getStyleClass().add("search-loading");
        notesContainer.getChildren().add(loadingLabel);

        int days = settings.getReviewDays();
        apiService.getNotes(days).thenAccept(notes -> Platform.runLater(() -> {
            notesContainer.getChildren().clear();

            if (notes.isEmpty()) {
                Label emptyLabel = new Label("暂无笔记");
                emptyLabel.getStyleClass().add("no-results");
                notesContainer.getChildren().add(emptyLabel);
            } else {
                Label countLabel = new Label("共 " + notes.size() + " 条笔记");
                countLabel.getStyleClass().add("search-count");
                notesContainer.getChildren().add(countLabel);

                for (ApiService.Note note : notes) {
                    VBox noteCard = createNoteCard(note);
                    notesContainer.getChildren().add(noteCard);
                }
            }
        }));
    }

    private VBox createNoteCard(ApiService.Note note) {
        // Date label
        Label dateLabel = new Label(note.createdAt());
        dateLabel.getStyleClass().add("note-date");

        // Content preview
        String previewText = getPreviewText(note.content(), 100);
        Label previewLabel = new Label(previewText);
        previewLabel.getStyleClass().add("search-result-preview");
        previewLabel.setWrapText(true);

        // Full content (hidden by default)
        Label contentLabel = new Label(note.content());
        contentLabel.setWrapText(true);
        contentLabel.setMaxWidth(Double.MAX_VALUE);
        contentLabel.getStyleClass().add("search-result-content-text");

        VBox contentBox = new VBox(contentLabel);
        contentBox.getStyleClass().add("search-result-content");
        contentBox.setPadding(new Insets(12));
        contentBox.setVisible(false);
        contentBox.setManaged(false);

        // Copy button
        Button copyBtn = new Button("Copy");
        copyBtn.getStyleClass().add("copy-button");

        Label copiedLabel = new Label("✓ Copied!");
        copiedLabel.getStyleClass().add("copied-label");
        copiedLabel.setVisible(false);
        copiedLabel.setManaged(false);

        HBox actionRow = new HBox(12, copyBtn, copiedLabel);
        actionRow.setAlignment(Pos.CENTER_LEFT);
        actionRow.setPadding(new Insets(8, 0, 0, 0));
        actionRow.setVisible(false);
        actionRow.setManaged(false);

        VBox card = new VBox(8, dateLabel, previewLabel, contentBox, actionRow);
        card.getStyleClass().add("search-result-card");
        card.setPadding(new Insets(16));

        // Track expanded state
        final boolean[] expanded = {false};

        // Click to expand/collapse
        card.setOnMouseClicked(e -> {
            if (e.getTarget() == copyBtn || copyBtn.isHover()) {
                return;
            }
            expanded[0] = !expanded[0];
            previewLabel.setVisible(!expanded[0]);
            previewLabel.setManaged(!expanded[0]);
            contentBox.setVisible(expanded[0]);
            contentBox.setManaged(expanded[0]);
            actionRow.setVisible(expanded[0]);
            actionRow.setManaged(expanded[0]);

            if (expanded[0]) {
                card.getStyleClass().add("expanded");
            } else {
                card.getStyleClass().remove("expanded");
            }
        });

        // Copy button action
        copyBtn.setOnAction(e -> {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            ClipboardContent clipboardContent = new ClipboardContent();
            clipboardContent.putString(note.content());
            clipboard.setContent(clipboardContent);

            copiedLabel.setVisible(true);
            copiedLabel.setManaged(true);

            new Thread(() -> {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {
                }
                Platform.runLater(() -> {
                    copiedLabel.setVisible(false);
                    copiedLabel.setManaged(false);
                });
            }).start();
        });

        return card;
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

    public void refresh() {
        loadNotes();
    }
}
