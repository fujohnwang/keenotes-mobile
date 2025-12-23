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

import java.sql.SQLException;
import java.util.List;

/**
 * Main view V2 - 使用本地缓存进行搜索和回顾
 * - 搜索: 使用LocalCacheService.searchNotes()
 * - 回顾: 使用LocalCacheService.getNotesForReview()
 * - 写入: 使用ApiServiceV2.postNote() + WebSocket实时同步
 */
public class MainViewV2 extends BorderPane {

    private final StackPane contentPane;
    private final VBox notePane;
    private final VBox reviewPane;
    private final TextArea noteInput;
    private final Button submitBtn;
    private final Label statusLabel;
    private final VBox echoContainer;
    private final VBox reviewResultsContainer;

    private final ApiServiceV2 apiService;
    private final LocalCacheService localCache;
    private final Runnable onOpenSettings;
    private final Runnable onOpenSearch;

    public MainViewV2(Runnable onOpenSettings, Runnable onOpenSearch) {
        this.onOpenSettings = onOpenSettings;
        this.onOpenSearch = onOpenSearch;
        this.apiService = new ApiServiceV2();
        this.localCache = LocalCacheService.getInstance();
        getStyleClass().add("main-view");

        // Initialize components
        reviewResultsContainer = new VBox(12);
        contentPane = new StackPane();
        noteInput = new TextArea();
        submitBtn = new Button("Save Note");
        statusLabel = new Label();
        echoContainer = new VBox(8);

        // Header with tabs and settings
        setTop(createHeader());

        // Create panes
        notePane = createNotePane();
        reviewPane = createReviewPane();

        // Stack all panes
        contentPane.getChildren().addAll(reviewPane, notePane);
        setCenter(contentPane);

        // Show note pane by default
        showNotePane();

        // Set initial focus
        Platform.runLater(() -> noteInput.requestFocus());
    }

    private HBox createHeader() {
        // Search button (left) - calls onOpenSearch
        Button searchBtn = new Button("Search");
        searchBtn.getStyleClass().add("tab-button");
        searchBtn.setOnAction(e -> onOpenSearch.run());

        // Settings button (right)
        javafx.scene.shape.SVGPath gearIcon = new javafx.scene.shape.SVGPath();
        gearIcon.setContent("M12 15.5A3.5 3.5 0 0 1 8.5 12 3.5 3.5 0 0 1 12 8.5a3.5 3.5 0 0 1 3.5 3.5 3.5 3.5 0 0 1-3.5 3.5m7.43-2.53c.04-.32.07-.64.07-.97 0-.33-.03-.66-.07-1l2.11-1.63c.19-.15.24-.42.12-.64l-2-3.46c-.12-.22-.39-.31-.61-.22l-2.49 1c-.52-.39-1.06-.73-1.69-.98l-.37-2.65A.506.506 0 0 0 14 2h-4c-.25 0-.46.18-.5.42l-.37 2.65c-.63.25-1.17.59-1.69.98l-2.49-1c-.22-.09-.49 0-.61.22l-2 3.46c-.13.22-.07.49.12.64L4.57 11c-.04.34-.07.67-.07 1 0 .33.03.65.07.97l-2.11 1.66c-.19.15-.25.42-.12.64l2 3.46c.12.22.39.3.61.22l2.49-1.01c.52.4 1.06.74 1.69.99l.37 2.65c.04.24.25.42.5.42h4c.25 0 .46-.18.5-.42l.37-2.65c.63-.26 1.17-.59 1.69-.99l2.49 1.01c.22.08.49 0 .61-.22l2-3.46c.12-.22.07-.49-.12-.64l-2.11-1.66z");
        gearIcon.setFill(javafx.scene.paint.Color.web("#8B949E"));
        gearIcon.setScaleX(1.2);
        gearIcon.setScaleY(1.2);

        Button settingsBtn = new Button();
        settingsBtn.setGraphic(gearIcon);
        settingsBtn.getStyleClass().add("icon-button");
        settingsBtn.setOnAction(e -> onOpenSettings.run());

        settingsBtn.setOnMouseEntered(e -> gearIcon.setFill(javafx.scene.paint.Color.web("#00D4FF")));
        settingsBtn.setOnMouseExited(e -> gearIcon.setFill(javafx.scene.paint.Color.web("#8B949E")));

        // Left side: Search button
        HBox leftBox = new HBox(8, searchBtn);
        leftBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(leftBox, Priority.ALWAYS);

        // Right side: Settings button
        HBox rightBox = new HBox(8, settingsBtn);
        rightBox.setAlignment(Pos.CENTER_RIGHT);

        // Header: Left (search button) + Right (settings button)
        HBox header = new HBox(8, leftBox, rightBox);
        header.getStyleClass().add("header");
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(8, 12, 8, 12));
        return header;
    }

    private VBox createNotePane() {
        noteInput.setPromptText("Write your note here...\nAll content will be encrypted before leaving your device.");
        noteInput.getStyleClass().add("note-input");
        noteInput.setWrapText(true);

        submitBtn.getStyleClass().addAll("action-button", "primary");
        submitBtn.setMaxWidth(Double.MAX_VALUE);
        submitBtn.setOnAction(e -> submitNote());

        submitBtn.disableProperty().bind(noteInput.textProperty().isEmpty());

        statusLabel.getStyleClass().add("status-label");
        statusLabel.setWrapText(true);

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

    private VBox createReviewPane() {
        // Period selection buttons (7 days, 30 days, 90 days, All)
        HBox periodControls = new HBox(8);
        periodControls.setPadding(new Insets(8, 16, 8, 16));

        String[] periods = {"7 days", "30 days", "90 days", "All"};
        for (String period : periods) {
            Button btn = new Button(period);
            btn.getStyleClass().add("review-period-btn");
            btn.setOnAction(e -> loadReviewNotes(period));
            periodControls.getChildren().add(btn);
        }

        // Results container
        reviewResultsContainer.setPadding(new Insets(8, 16, 16, 16));
        reviewResultsContainer.getStyleClass().add("review-results");

        ScrollPane scrollPane = new ScrollPane(reviewResultsContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("content-scroll");

        VBox pane = new VBox(8, periodControls, scrollPane);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        pane.getStyleClass().add("review-pane");
        return pane;
    }

    public void showNotePane() {
        reviewPane.setVisible(false);
        notePane.setVisible(true);
        notePane.toFront();
        Platform.runLater(() -> noteInput.requestFocus());
    }

    public void showReviewPane() {
        notePane.setVisible(false);
        reviewPane.setVisible(true);
        reviewPane.toFront();
        // Load review notes when showing the pane
        loadReviewNotes();
    }

    private void loadReviewNotes() {
        loadReviewNotes("7 days");
    }

    private void loadReviewNotes(String period) {
        reviewResultsContainer.getChildren().clear();
        Label loadingLabel = new Label("Loading notes...");
        loadingLabel.getStyleClass().add("search-loading");
        reviewResultsContainer.getChildren().add(loadingLabel);

        int days = switch (period) {
            case "30 days" -> 30;
            case "90 days" -> 90;
            case "All" -> 3650; // 10 years
            default -> 7;
        };

        System.out.println("[DEBUG loadReviewNotes] period=" + period + ", days=" + days);

        // 使用本地回顾
        List<LocalCacheService.NoteData> results = localCache.getNotesForReview(days);

        Platform.runLater(() -> {
            reviewResultsContainer.getChildren().clear();

            System.out.println("[DEBUG loadReviewNotes] results.size=" + results.size());

            if (results.isEmpty()) {
                Label noResults = new Label("No notes found for " + period);
                noResults.getStyleClass().add("no-results");
                reviewResultsContainer.getChildren().add(noResults);
            } else {
                Label countLabel = new Label(results.size() + " note(s) in " + period);
                countLabel.getStyleClass().add("search-count");
                reviewResultsContainer.getChildren().add(countLabel);

                for (LocalCacheService.NoteData result : results) {
                    VBox card = createResultCard(result);
                    reviewResultsContainer.getChildren().add(card);
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

    private void submitNote() {
        String content = noteInput.getText();
        if (content.trim().isEmpty()) {
            return;
        }

        statusLabel.getStyleClass().removeAll("error", "success");
        statusLabel.setText("Encrypting and sending...");

        // Disable button during request
        submitBtn.disableProperty().unbind();
        submitBtn.setDisable(true);

        apiService.postNote(content).thenAccept(result -> Platform.runLater(() -> {
            if (result.success()) {
                statusLabel.setText("✓ " + result.message());
                statusLabel.getStyleClass().add("success");
                showEcho(result.echoContent());

                // 如果有WebSocket连接，会自动同步回来
                // 这里我们也可以立即在本地缓存中添加（如果需要）
                if (result.noteId() != null) {
                    // 可选：立即添加到本地缓存（等待WebSocket同步更可靠）
                    // 注意：实际应该等待WebSocket的实时更新
                }

                noteInput.clear();
            } else {
                statusLabel.setText("✗ " + result.message());
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

        Label copiedHint = new Label("✓ Copied!");
        copiedHint.getStyleClass().add("copied-label");
        copiedHint.setVisible(false);
        copiedHint.setManaged(false);

        VBox card = new VBox(4, echoTitle, echoContent, copiedHint);
        card.getStyleClass().add("note-card");
        card.setPadding(new Insets(12));

        // Double-click to copy content
        card.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                Clipboard clipboard = Clipboard.getSystemClipboard();
                ClipboardContent clipboardContent = new ClipboardContent();
                clipboardContent.putString(content);
                clipboard.setContent(clipboardContent);

                copiedHint.setVisible(true);
                copiedHint.setManaged(true);

                PauseTransition pause = new PauseTransition(Duration.millis(1500));
                pause.setOnFinished(ev -> {
                    copiedHint.setVisible(false);
                    copiedHint.setManaged(false);
                });
                pause.play();
            }
        });

        echoContainer.getChildren().add(card);
    }

    /**
     * 获取本地笔记统计信息
     */
    public String getLocalStats() {
        int count = localCache.getLocalNoteCount();
        String lastSync = localCache.getLastSyncTime();
        return String.format("Local notes: %d\nLast sync: %s", count, lastSync != null ? lastSync : "Never");
    }

    /**
     * 切换到记录/笔记面板
     */
    public void showRecordTab() {
        showNotePane();
    }

    /**
     * 检查是否在搜索面板 (for back navigation compatibility)
     */
    public boolean isInSearchPane() {
        return false;  // Search is now a separate view
    }

    /**
     * 从搜索面板返回 (for back navigation compatibility)
     */
    public void goBackFromSearch() {
        // No longer used - search is handled separately
    }
}
