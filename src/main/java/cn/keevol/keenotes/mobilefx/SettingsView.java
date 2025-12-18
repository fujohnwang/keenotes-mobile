package cn.keevol.keenotes.mobilefx;

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
    private final PasswordField encryptionPasswordField;
    private final PasswordField encryptionPasswordConfirmField;
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

        encryptionPasswordField = new PasswordField();
        encryptionPasswordField.setPromptText("E2E encryption password (optional)");
        encryptionPasswordField.getStyleClass().add("input-field");

        encryptionPasswordConfirmField = new PasswordField();
        encryptionPasswordConfirmField.setPromptText("Confirm encryption password");
        encryptionPasswordConfirmField.getStyleClass().add("input-field");

        statusLabel = new Label();
        statusLabel.getStyleClass().add("status-label");

        Button saveButton = new Button("Save");
        saveButton.getStyleClass().addAll("action-button", "primary");
        saveButton.setMaxWidth(Double.MAX_VALUE);
        saveButton.setOnAction(e -> saveSettings());

        // Copyright footer
        Label copyrightLabel = new Label("©2025 王福强(Fuqiang Wang)  All Rights Reserved");
        copyrightLabel.getStyleClass().add("copyright-label");

        Label websiteLabel = new Label("https://afoo.me");
        websiteLabel.getStyleClass().add("copyright-link");

        VBox footer = new VBox(4, copyrightLabel, websiteLabel);
        footer.setAlignment(Pos.CENTER);

        // Encryption hint
        Label encryptionHint = new Label("Leave both empty to disable E2E encryption");
        encryptionHint.getStyleClass().add("field-hint");

        VBox form = new VBox(16,
                createFieldGroup("Endpoint URL", endpointField),
                createFieldGroup("Token", tokenField),
                createFieldGroup("Encryption Password", encryptionPasswordField),
                createFieldGroupWithHint("Confirm Password", encryptionPasswordConfirmField, encryptionHint),
                saveButton,
                statusLabel,
                footer
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

    private VBox createFieldGroupWithHint(String labelText, Control field, Label hint) {
        Label label = new Label(labelText);
        label.getStyleClass().add("field-label");
        VBox group = new VBox(6, label, field, hint);
        return group;
    }

    private void loadSettings() {
        endpointField.setText(settings.getEndpointUrl());
        tokenField.setText(settings.getToken());
        String savedPassword = settings.getEncryptionPassword();
        encryptionPasswordField.setText(savedPassword);
        encryptionPasswordConfirmField.setText(savedPassword);
    }

    private void saveSettings() {
        String password = encryptionPasswordField.getText();
        String confirmPassword = encryptionPasswordConfirmField.getText();
        
        // Validate password match
        if (!password.equals(confirmPassword)) {
            encryptionPasswordField.clear();
            encryptionPasswordConfirmField.clear();
            encryptionPasswordField.requestFocus();
            statusLabel.setText("Passwords do not match");
            statusLabel.getStyleClass().removeAll("error", "success");
            statusLabel.getStyleClass().add("error");
            return;
        }
        
        settings.setEndpointUrl(endpointField.getText().trim());
        settings.setToken(tokenField.getText());
        settings.setEncryptionPassword(password);
        settings.save();
        
        String msg = settings.isEncryptionEnabled() 
            ? "Settings saved ✓ (E2E encryption enabled)"
            : "Settings saved ✓";
        statusLabel.setText(msg);
        statusLabel.getStyleClass().removeAll("error", "success");
        statusLabel.getStyleClass().add("success");
    }
}
