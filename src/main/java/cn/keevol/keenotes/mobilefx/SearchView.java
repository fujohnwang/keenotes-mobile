package cn.keevol.keenotes.mobilefx;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.util.Duration;

import java.util.List;

/**
 * Search view - displays search input and results in the main area.
 */
public class SearchView extends VBox {

    private final TextField searchField;
    private final VBox resultsContainer;
    private final LocalCacheService localCache;
    private final Runnable onBack;
    private final PauseTransition debounce;

    public SearchView(Runnable onBack) {
        this.localCache = LocalCacheService.getInstance();
        this.onBack = onBack;
        getStyleClass().add("search-view");

        // Results area
        resultsContainer = new VBox(12);
        resultsContainer.setPadding(new Insets(8, 16, 16, 16));
        resultsContainer.getStyleClass().add("search-results");

        ScrollPane scrollPane = new ScrollPane(resultsContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("content-scroll");

        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        // Initialize search field
        searchField = new TextField();
        searchField.setPromptText("Search notes...");
        searchField.getStyleClass().add("search-input");
        searchField.setPadding(new Insets(8, 12, 8, 12));

        // Debounce timer for search
        debounce = new PauseTransition(Duration.millis(500));
        debounce.setOnFinished(e -> performSearch(searchField.getText().trim()));

        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.trim().isEmpty()) {
                debounceSearch();
            } else {
                cancelDebounce();
                resultsContainer.getChildren().clear();
            }
        });
        searchField.setOnAction(e -> {
            cancelDebounce();
            performSearch(searchField.getText().trim());
        });

        // Header with back button, search field, and scroll pane
        getChildren().addAll(createHeader(), searchField, scrollPane);
    }

    private HBox createHeader() {
        Label title = new Label("Search");
        title.getStyleClass().add("search-title");

        Button backBtn = new Button("Back");
        backBtn.getStyleClass().add("back-btn");
        backBtn.setOnAction(e -> onBack.run());

        HBox header = new HBox(8, title, backBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(12, 16, 8, 16));
        header.getStyleClass().add("header");
        return header;
    }

    private void debounceSearch() {
        debounce.playFromStart();
    }

    private void cancelDebounce() {
        debounce.stop();
    }

    private void performSearch(String query) {
        if (query.isEmpty()) {
            resultsContainer.getChildren().clear();
            return;
        }

        resultsContainer.getChildren().clear();
        Label loadingLabel = new Label("Searching locally...");
        loadingLabel.getStyleClass().add("search-loading");
        resultsContainer.getChildren().add(loadingLabel);

        List<LocalCacheService.NoteData> results = localCache.searchNotes(query);

        Platform.runLater(() -> {
            resultsContainer.getChildren().clear();

            if (results.isEmpty()) {
                Label noResults = new Label("No results found for \"" + query + "\"");
                noResults.getStyleClass().add("no-results");
                resultsContainer.getChildren().add(noResults);
            } else {
                Label countLabel = new Label(results.size() + " result(s) found");
                countLabel.getStyleClass().add("search-count");
                resultsContainer.getChildren().add(countLabel);

                for (LocalCacheService.NoteData result : results) {
                    VBox card = createResultCard(result);
                    resultsContainer.getChildren().add(card);
                }
            }
        });
    }

    private VBox createResultCard(LocalCacheService.NoteData result) {
        // Date label
        Label dateLabel = new Label(result.createdAt);
        dateLabel.getStyleClass().add("note-date");

        // Content preview
        String previewText = getPreviewText(result.content, 100);
        Label previewLabel = new Label(previewText);
        previewLabel.getStyleClass().add("search-result-preview");
        previewLabel.setWrapText(true);

        // Full content (hidden by default)
        Label contentLabel = new Label(result.content);
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
            clipboardContent.putString(result.content);
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
}
