package com.keenotes.mobile;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/**
 * Main view with note input and submission functionality.
 */
public class MainView extends BorderPane {

    private final TextArea noteInput;
    private final Button submitBtn;
    private final Label statusLabel;
    private final VBox echoContainer;
    private final ApiService apiService;
    private final Runnable onOpenSettings;

    public MainView(Runnable onOpenSettings) {
        this.onOpenSettings = onOpenSettings;
        this.apiService = new ApiService();
        getStyleClass().add("main-view");

        // Header with gear icon only
        setTop(createHeader());

        // Content
        noteInput = new TextArea();
        noteInput.setPromptText("Write your note here...");
        noteInput.getStyleClass().add("note-input");
        noteInput.setWrapText(true);

        submitBtn = new Button("Save Note");
        submitBtn.getStyleClass().addAll("action-button", "primary");
        submitBtn.setMaxWidth(Double.MAX_VALUE);
        submitBtn.setOnAction(e -> submitNote());

        // Bind button disable state to input empty
        submitBtn.disableProperty().bind(
                noteInput.textProperty().isEmpty()
        );

        statusLabel = new Label();
        statusLabel.getStyleClass().add("status-label");
        statusLabel.setWrapText(true);

        echoContainer = new VBox(8);
        echoContainer.getStyleClass().add("echo-container");

        VBox content = new VBox(16, noteInput, submitBtn, statusLabel, echoContainer);
        content.setPadding(new Insets(16));
        VBox.setVgrow(noteInput, Priority.ALWAYS);

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("content-scroll");
        setCenter(scrollPane);
    }

    private HBox createHeader() {
        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Gear icon button for settings
        Button settingsBtn = new Button("⚙");
        settingsBtn.getStyleClass().add("icon-button");
        settingsBtn.setOnAction(e -> onOpenSettings.run());

        HBox header = new HBox(spacer, settingsBtn);
        header.getStyleClass().add("header");
        header.setAlignment(Pos.CENTER_RIGHT);
        header.setPadding(new Insets(8, 12, 8, 12));
        return header;
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
