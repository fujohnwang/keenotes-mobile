package cn.keevol.keenotes.mobilefx;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.util.Duration;

/**
 * Main view with note input and search functionality in separate panes.
 */
public class MainView extends BorderPane {

    private final TextField searchField;
    private final StackPane contentPane;
    private final VBox notePane;
    private final VBox searchPane;
    private final TextArea noteInput;
    private final Button submitBtn;
    private final Label statusLabel;
    private final VBox echoContainer;
    private final VBox searchResultsContainer;
    private final ApiService apiService;
    private final Runnable onOpenSettings;
    private final Button clearSearchBtn;
    private final PauseTransition debounce;

    public MainView(Runnable onOpenSettings) {
        this.onOpenSettings = onOpenSettings;
        this.apiService = new ApiService();
        getStyleClass().add("main-view");

        // Initialize all components first (required for final fields)
        searchField = new TextField();
        clearSearchBtn = new Button("✕");
        searchResultsContainer = new VBox(12);
        contentPane = new StackPane();
        noteInput = new TextArea();
        submitBtn = new Button("Save Note");
        statusLabel = new Label();
        echoContainer = new VBox(8);
        
        // Initialize debounce timer (reusable)
        debounce = new PauseTransition(Duration.millis(500));
        debounce.setOnFinished(e -> performSearch());

        // Header with search box and gear icon
        setTop(createHeader());

        // Create note input pane
        notePane = createNotePane();
        
        // Create search results pane
        searchPane = createSearchPane();
        searchPane.setVisible(false);

        // Stack both panes, note pane on top by default
        contentPane.getChildren().addAll(searchPane, notePane);
        setCenter(contentPane);

        // Set initial focus to note input after scene is ready
        Platform.runLater(() -> noteInput.requestFocus());
    }

    private HBox createHeader() {
        // Configure search field
        searchField.setPromptText("Search notes...");
        searchField.getStyleClass().add("search-field");
        HBox.setHgrow(searchField, Priority.ALWAYS);

        // Clear button (hidden when search field is empty)
        clearSearchBtn.getStyleClass().add("clear-search-btn");
        clearSearchBtn.setVisible(false);
        clearSearchBtn.setManaged(false);
        clearSearchBtn.setOnAction(e -> {
            searchField.clear();
            searchField.requestFocus();
        });

        // Search field container with clear button overlay
        StackPane searchContainer = new StackPane(searchField, clearSearchBtn);
        StackPane.setAlignment(clearSearchBtn, Pos.CENTER_RIGHT);
        StackPane.setMargin(clearSearchBtn, new Insets(0, 8, 0, 0));
        HBox.setHgrow(searchContainer, Priority.ALWAYS);

        // Listen for text changes - debounce search and show/hide clear button
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            boolean hasText = newVal != null && !newVal.trim().isEmpty();
            clearSearchBtn.setVisible(hasText);
            clearSearchBtn.setManaged(hasText);
            
            if (!hasText) {
                cancelDebounce();
                showNotePane();
            } else {
                debounceSearch();
            }
        });

        // Also allow Enter key for immediate search
        searchField.setOnAction(e -> {
            cancelDebounce();
            performSearch();
        });

        // Gear icon button for settings
        Button settingsBtn = new Button("⚙");
        settingsBtn.getStyleClass().add("icon-button");
        settingsBtn.setOnAction(e -> onOpenSettings.run());

        HBox header = new HBox(8, searchContainer, settingsBtn);
        header.getStyleClass().add("header");
        header.setAlignment(Pos.CENTER);
        header.setPadding(new Insets(8, 12, 8, 12));
        return header;
    }
    
    private void debounceSearch() {
        debounce.playFromStart();
    }

    private void cancelDebounce() {
        debounce.stop();
    }

    private VBox createNotePane() {
        // Configure note input (already initialized in constructor)
        noteInput.setPromptText("Write your note here...");
        noteInput.getStyleClass().add("note-input");
        noteInput.setWrapText(true);

        // Configure submit button
        submitBtn.getStyleClass().addAll("action-button", "primary");
        submitBtn.setMaxWidth(Double.MAX_VALUE);
        submitBtn.setOnAction(e -> submitNote());

        // Bind button disable state to input empty
        submitBtn.disableProperty().bind(noteInput.textProperty().isEmpty());

        // Configure status label
        statusLabel.getStyleClass().add("status-label");
        statusLabel.setWrapText(true);

        // Configure echo container
        echoContainer.getStyleClass().add("echo-container");

        VBox content = new VBox(16, noteInput, submitBtn, statusLabel, echoContainer);
        content.setPadding(new Insets(16));
        VBox.setVgrow(noteInput, Priority.ALWAYS);

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("content-scroll");

        VBox pane = new VBox(scrollPane);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        pane.getStyleClass().add("note-pane");
        return pane;
    }

    private VBox createSearchPane() {
        Label titleLabel = new Label("Search Results");
        titleLabel.getStyleClass().add("search-pane-title");

        // Back button to return to note pane
        Button backBtn = new Button("← Back");
        backBtn.getStyleClass().add("back-button");
        backBtn.setOnAction(e -> {
            searchField.clear();
            showNotePane();
        });

        HBox topBar = new HBox(backBtn);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(8, 16, 0, 16));

        searchResultsContainer.setPadding(new Insets(8, 16, 16, 16));
        searchResultsContainer.getStyleClass().add("search-results");

        ScrollPane scrollPane = new ScrollPane(searchResultsContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("content-scroll");

        VBox pane = new VBox(8, topBar, scrollPane);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        pane.getStyleClass().add("search-pane");
        return pane;
    }

    private void showNotePane() {
        searchPane.setVisible(false);
        notePane.setVisible(true);
        notePane.toFront();
        Platform.runLater(() -> noteInput.requestFocus());
    }

    private void showSearchPane() {
        notePane.setVisible(false);
        searchPane.setVisible(true);
        searchPane.toFront();
    }

    private static final int PAGE_SIZE = 20;
    private int currentPage = 0;
    private String currentQuery = "";

    private void performSearch() {
        String query = searchField.getText().trim();
        if (query.isEmpty()) {
            showNotePane();
            return;
        }

        // Reset pagination for new query
        if (!query.equals(currentQuery)) {
            currentPage = 0;
            currentQuery = query;
        }

        // Switch to search pane and show loading
        showSearchPane();
        searchResultsContainer.getChildren().clear();

        Label loadingLabel = new Label("Searching for \"" + query + "\"...");
        loadingLabel.getStyleClass().add("search-loading");
        searchResultsContainer.getChildren().add(loadingLabel);

        // Call mock search API
        apiService.searchNotes(query).thenAccept(results -> Platform.runLater(() -> {
            searchResultsContainer.getChildren().clear();

            if (results.isEmpty()) {
                Label noResults = new Label("No results found for \"" + query + "\"");
                noResults.getStyleClass().add("no-results");
                searchResultsContainer.getChildren().add(noResults);
            } else {
                int totalResults = results.size();
                int startIndex = currentPage * PAGE_SIZE;
                int endIndex = Math.min(startIndex + PAGE_SIZE, totalResults);
                
                // Show count info
                String countText = totalResults <= PAGE_SIZE 
                    ? totalResults + " result(s) found"
                    : String.format("Showing %d-%d of %d results", startIndex + 1, endIndex, totalResults);
                Label countLabel = new Label(countText);
                countLabel.getStyleClass().add("search-count");
                searchResultsContainer.getChildren().add(countLabel);

                // Display current page results
                for (int i = startIndex; i < endIndex; i++) {
                    VBox resultCard = createSearchResultCard(results.get(i));
                    searchResultsContainer.getChildren().add(resultCard);
                }

                // Add "Load More" button if there are more results
                if (endIndex < totalResults) {
                    Button loadMoreBtn = new Button("Load More (" + (totalResults - endIndex) + " remaining)");
                    loadMoreBtn.getStyleClass().add("load-more-button");
                    loadMoreBtn.setMaxWidth(Double.MAX_VALUE);
                    loadMoreBtn.setOnAction(e -> {
                        currentPage++;
                        loadMoreResults(results);
                    });
                    searchResultsContainer.getChildren().add(loadMoreBtn);
                }
            }
        }));
    }

    private void loadMoreResults(java.util.List<ApiService.SearchResult> results) {
        // Remove the "Load More" button
        searchResultsContainer.getChildren().removeIf(node -> 
            node instanceof Button && ((Button) node).getStyleClass().contains("load-more-button"));

        int totalResults = results.size();
        int startIndex = currentPage * PAGE_SIZE;
        int endIndex = Math.min(startIndex + PAGE_SIZE, totalResults);

        // Update count label
        if (!searchResultsContainer.getChildren().isEmpty() && 
            searchResultsContainer.getChildren().get(0) instanceof Label) {
            Label countLabel = (Label) searchResultsContainer.getChildren().get(0);
            String countText = String.format("Showing %d-%d of %d results", 1, endIndex, totalResults);
            countLabel.setText(countText);
        }

        // Add more results
        for (int i = startIndex; i < endIndex; i++) {
            VBox resultCard = createSearchResultCard(results.get(i));
            searchResultsContainer.getChildren().add(resultCard);
        }

        // Add new "Load More" button if there are still more results
        if (endIndex < totalResults) {
            Button loadMoreBtn = new Button("Load More (" + (totalResults - endIndex) + " remaining)");
            loadMoreBtn.getStyleClass().add("load-more-button");
            loadMoreBtn.setMaxWidth(Double.MAX_VALUE);
            loadMoreBtn.setOnAction(e -> {
                currentPage++;
                loadMoreResults(results);
            });
            searchResultsContainer.getChildren().add(loadMoreBtn);
        }
    }

    private VBox createSearchResultCard(ApiService.SearchResult result) {
        // Expand indicator
        Label expandIcon = new Label("▶");
        expandIcon.getStyleClass().add("expand-icon");

        // Preview text (first 2-3 lines of content, collapsed state)
        String previewText = getPreviewText(result.content(), 100);
        Label previewLabel = new Label(previewText);
        previewLabel.getStyleClass().add("search-result-preview");
        previewLabel.setWrapText(true);
        HBox.setHgrow(previewLabel, Priority.ALWAYS);

        HBox previewRow = new HBox(8, expandIcon, previewLabel);
        previewRow.setAlignment(Pos.TOP_LEFT);

        // Full content (hidden by default)
        Label contentLabel = new Label(result.content());
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

        VBox card = new VBox(8, previewRow, contentBox, actionRow);
        card.getStyleClass().add("search-result-card");
        card.setPadding(new Insets(16));

        // Track expanded state
        final boolean[] expanded = {false};

        // Click anywhere on card to expand/collapse (except copy button)
        card.setOnMouseClicked(e -> {
            if (e.getTarget() == copyBtn || copyBtn.isHover()) {
                return;
            }
            expanded[0] = !expanded[0];
            expandIcon.setText(expanded[0] ? "▼" : "▶");
            previewRow.setVisible(!expanded[0]);
            previewRow.setManaged(!expanded[0]);
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
            clipboardContent.putString(result.content());
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

    private void submitNote() {
        String content = noteInput.getText();
        statusLabel.getStyleClass().removeAll("error", "success");
        statusLabel.setText("Sending...");

        // Disable button during request
        submitBtn.disableProperty().unbind();
        submitBtn.setDisable(true);

        apiService.postNote(content).thenAccept(result -> Platform.runLater(() -> {
            if (result.success()) {
                statusLabel.setText(result.message());
                statusLabel.getStyleClass().add("success");
                showEcho(result.echoContent());
                noteInput.clear();
            } else {
                statusLabel.setText(result.message());
                statusLabel.getStyleClass().add("error");
            }

            // Re-bind button disable state to input empty
            submitBtn.disableProperty().bind(noteInput.textProperty().isEmpty());
        }));
    }

    private void showEcho(String content) {
        echoContainer.getChildren().clear();

        Label echoTitle = new Label("Last saved:");
        echoTitle.getStyleClass().add("echo-title");

        Label echoContent = new Label(content);
        echoContent.getStyleClass().add("echo-content");
        echoContent.setWrapText(true);

        VBox card = new VBox(4, echoTitle, echoContent);
        card.getStyleClass().add("note-card");
        card.setPadding(new Insets(12));

        echoContainer.getChildren().add(card);
    }
}
