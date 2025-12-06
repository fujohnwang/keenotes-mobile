package com.keenotes.mobile;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/**
 * Settings view for configuring API endpoint and token.
 */
public class SettingsView extends BorderPane {

    private final TextField endpointField;
    private final PasswordField tokenField;
    private final Label statusLabel;
    private final SettingsService settings;
    private final Runnable onBack;

    public SettingsView(Runnable onBack) {
        this.onBack = onBack;
        this.settings = SettingsService.getInstance();
        getStyleClass().add("main-view");

        // Header
        setTop(createHeader());

        // Form content
        endpointField = new TextField();
        endpointField.setPromptText("https://api.example.com/notes");
        endpointField.getStyleClass().add("input-field");

        tokenField = new PasswordField();
        tokenField.setPromptText("Your API token");
        tokenField.getStyleClass().add("input-field");

        statusLabel = new Label();
        statusLabel.getStyleClass().add("status-label");

        Button saveButton = new Button("Save");
        saveButton.getStyleClass().addAll("action-button", "primary");
        saveButton.setMaxWidth(Double.MAX_VALUE);
        saveButton.setOnAction(e -> saveSettings());

        VBox form = new VBox(16,
                createFieldGroup("Endpoint URL", endpointField),
                createFieldGroup("Token", tokenField),
                saveButton,
                statusLabel
        );
        form.setPadding(new Insets(24));
        form.setAlignment(Pos.TOP_CENTER);

        ScrollPane scrollPane = new ScrollPane(form);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("content-scroll");
        setCenter(scrollPane);

        loadSettings();
    }

    private HBox createHeader() {
        Button backBtn = new Button("←");
        backBtn.getStyleClass().add("header-button-text");
        backBtn.setOnAction(e -> onBack.run());

        Label title = new Label("Settings");
        title.getStyleClass().add("header-title");

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(8, backBtn, title, spacer);
        header.getStyleClass().add("header");
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(8, 12, 8, 12));
        return header;
    }

    private VBox createFieldGroup(String labelText, Control field) {
        Label label = new Label(labelText);
        label.getStyleClass().add("field-label");
        VBox group = new VBox(6, label, field);
        return group;
    }

    private void loadSettings() {
        endpointField.setText(settings.getEndpointUrl());
        tokenField.setText(settings.getToken());
    }

    private void saveSettings() {
        settings.setEndpointUrl(endpointField.getText().trim());
        settings.setToken(tokenField.getText());
        settings.save();
        
        statusLabel.setText("Settings saved ✓");
        statusLabel.getStyleClass().removeAll("error", "success");
        statusLabel.getStyleClass().add("success");
    }
}
