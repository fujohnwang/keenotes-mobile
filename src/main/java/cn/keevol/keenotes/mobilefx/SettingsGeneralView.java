package cn.keevol.keenotes.mobilefx;

import cn.keevol.keenotes.utils.javafx.JFX;
import javafx.animation.PauseTransition;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * General settings view (Endpoint, Token, Encryption Password)
 */
public class SettingsGeneralView extends VBox {
    
    private final TextField endpointField;
    private final PasswordField tokenField;
    private final PasswordField encryptionPasswordField;
    private final PasswordField encryptionPasswordConfirmField;
    private final Label statusLabel;
    private final SettingsService settings;
    private final Runnable onSaveSuccess;
    
    public SettingsGeneralView(Runnable onSaveSuccess) {
        this.onSaveSuccess = onSaveSuccess;
        this.settings = SettingsService.getInstance();
        
        setPadding(new Insets(24));
        setSpacing(16);
        setAlignment(Pos.TOP_CENTER);
        
        // Form fields
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

        // Reactive binding: disable Save button if any field is empty or passwords don't match
        saveButton.disableProperty().bind(Bindings.createBooleanBinding(() -> {
            String endpoint = endpointField.getText();
            String token = tokenField.getText();
            String password = encryptionPasswordField.getText();
            String confirmPassword = encryptionPasswordConfirmField.getText();

            if (endpoint == null || endpoint.trim().isEmpty() || 
                token == null || token.trim().isEmpty() || 
                password == null || password.trim().isEmpty() || 
                confirmPassword == null || confirmPassword.trim().isEmpty()) {
                return true;
            }

            return !password.equals(confirmPassword);
        }, endpointField.textProperty(), tokenField.textProperty(), 
           encryptionPasswordField.textProperty(), encryptionPasswordConfirmField.textProperty()));

        // Create save button row
        Label saveButtonSpacer = new Label();
        saveButtonSpacer.setMinWidth(259);
        saveButtonSpacer.setMaxWidth(259);

        HBox.setHgrow(saveButton, Priority.ALWAYS);

        HBox saveButtonRow = new HBox(16, saveButtonSpacer, saveButton);
        saveButtonRow.setAlignment(Pos.CENTER_LEFT);

        // Status label row
        Label statusSpacer = new Label();
        statusSpacer.setMinWidth(259);
        statusSpacer.setMaxWidth(259);

        HBox statusRow = new HBox(16, statusSpacer, statusLabel);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        // Encryption hint
        Label encryptionHint = new Label("You MUST set password to enable E2E encryption");
        encryptionHint.getStyleClass().add("field-hint");

        getChildren().addAll(
            createFieldGroup("Endpoint URL", endpointField),
            createFieldGroup("Token", tokenField),
            createFieldGroup("Encryption Password", encryptionPasswordField),
            createFieldGroupWithHint("Confirm Password", encryptionPasswordConfirmField, encryptionHint),
            saveButtonRow,
            statusRow
        );
        
        loadSettings();
    }
    
    private VBox createFieldGroup(String labelText, Control field) {
        Label label = new Label(labelText);
        label.getStyleClass().add("field-label");
        label.setMinWidth(259);
        label.setMaxWidth(259);
        label.setAlignment(Pos.CENTER_RIGHT);

        HBox.setHgrow(field, Priority.ALWAYS);
        field.setMaxWidth(Double.MAX_VALUE);

        HBox row = new HBox(16, label, field);
        row.setAlignment(Pos.CENTER_LEFT);

        return new VBox(row);
    }

    private VBox createFieldGroupWithHint(String labelText, Control field, Label hint) {
        Label label = new Label(labelText);
        label.getStyleClass().add("field-label");
        label.setMinWidth(259);
        label.setMaxWidth(259);
        label.setAlignment(Pos.CENTER_RIGHT);

        field.setMaxWidth(Double.MAX_VALUE);

        VBox fieldWithHint = new VBox(6, field, hint);
        HBox.setHgrow(fieldWithHint, Priority.ALWAYS);

        VBox labelWrapper = new VBox(label);
        labelWrapper.setAlignment(Pos.TOP_RIGHT);
        labelWrapper.setPadding(new Insets(8, 0, 0, 0));
        labelWrapper.setMinWidth(259);
        labelWrapper.setMaxWidth(259);

        HBox row = new HBox(16, labelWrapper, fieldWithHint);
        row.setAlignment(Pos.TOP_LEFT);

        return new VBox(row);
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

        if (!password.equals(confirmPassword)) {
            encryptionPasswordField.clear();
            encryptionPasswordConfirmField.clear();
            encryptionPasswordField.requestFocus();
            statusLabel.setText("Passwords do not match");
            statusLabel.getStyleClass().removeAll("error", "success");
            statusLabel.getStyleClass().add("error");
            return;
        }

        String oldEndpoint = settings.getEndpointUrl();
        String oldToken = settings.getToken();
        String oldPassword = settings.getEncryptionPassword();
        boolean wasConfiguredBefore = settings.isConfigured();

        String newEndpoint = endpointField.getText().trim();
        String newToken = tokenField.getText();
        String newPassword = password;

        boolean endpointChanged = !java.util.Objects.equals(oldEndpoint, newEndpoint);
        boolean tokenChanged = !java.util.Objects.equals(oldToken, newToken);
        boolean passwordChanged = !java.util.Objects.equals(oldPassword, newPassword);
        boolean configurationChanged = endpointChanged || tokenChanged || passwordChanged;

        settings.setEndpointUrl(newEndpoint);
        settings.setToken(newToken);
        settings.setEncryptionPassword(newPassword);
        settings.save();

        String msg = settings.isEncryptionEnabled() ? 
            "Settings saved ✓ (E2E encryption enabled)" : "Settings saved ✓";

        if (configurationChanged && wasConfiguredBefore) {
            System.out.println("[SettingsGeneralView] Configuration changed, reinitializing services...");

            statusLabel.setText("Configuration changed, reconnecting...");
            statusLabel.getStyleClass().removeAll("error", "success");
            statusLabel.getStyleClass().add("success");

            final boolean clearNotes = endpointChanged || tokenChanged;

            new Thread(() -> {
                try {
                    ServiceManager.getInstance().reinitializeServices(clearNotes);
                    javafx.application.Platform.runLater(() -> {
                        statusLabel.setText(msg + " (Reconnected)");
                        PauseTransition delay = new PauseTransition(Duration.millis(500));
                        delay.setOnFinished(e -> onSaveSuccess.run());
                        delay.play();
                    });
                } catch (Exception e) {
                    System.err.println("[SettingsGeneralView] Reinitialization failed: " + e.getMessage());
                    javafx.application.Platform.runLater(() -> {
                        statusLabel.setText("Settings saved, but reconnection failed");
                        statusLabel.getStyleClass().removeAll("error", "success");
                        statusLabel.getStyleClass().add("error");
                    });
                }
            }, "SettingsReinit").start();

        } else if (!wasConfiguredBefore && settings.isConfigured()) {
            System.out.println("[SettingsGeneralView] New configuration detected, initializing services...");

            statusLabel.setText(msg);
            statusLabel.getStyleClass().removeAll("error", "success");
            statusLabel.getStyleClass().add("success");

            new Thread(() -> {
                try {
                    Thread.sleep(100);
                    ServiceManager.getInstance().initializeServices();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "SettingsInit").start();

            PauseTransition delay = new PauseTransition(Duration.millis(500));
            delay.setOnFinished(e -> onSaveSuccess.run());
            delay.play();

        } else {
            statusLabel.setText(msg);
            statusLabel.getStyleClass().removeAll("error", "success");
            statusLabel.getStyleClass().add("success");

            PauseTransition delay = new PauseTransition(Duration.millis(500));
            delay.setOnFinished(e -> onSaveSuccess.run());
            delay.play();
        }
    }
}
