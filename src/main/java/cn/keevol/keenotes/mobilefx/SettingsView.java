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
    private final Runnable onOpenDebug;
    
    // Easter egg: tap copyright 7 times to show debug
    private int copyrightTapCount = 0;
    private long lastTapTime = 0;
    private VBox debugSection;

    public SettingsView(Runnable onBack, Runnable onOpenDebug) {
        this.onBack = onBack;
        this.onOpenDebug = onOpenDebug;
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

        // Copyright footer with easter egg
        Label copyrightLabel = new Label("©2025 王福强(Fuqiang Wang)  All Rights Reserved");
        copyrightLabel.getStyleClass().add("copyright-label");
        copyrightLabel.setOnMouseClicked(e -> onCopyrightTap());

        Label websiteLabel = new Label("https://afoo.me");
        websiteLabel.getStyleClass().add("copyright-link");

        VBox footer = new VBox(4, copyrightLabel, websiteLabel);
        footer.setAlignment(Pos.CENTER);

        // Encryption hint
        Label encryptionHint = new Label("Leave both empty to disable E2E encryption");
        encryptionHint.getStyleClass().add("field-hint");

        // Debug entry (hidden by default)
        Button debugBtn = new Button("Debug");
        debugBtn.getStyleClass().add("debug-entry-btn");
        debugBtn.setMaxWidth(Double.MAX_VALUE);
        debugBtn.setOnAction(e -> onOpenDebug.run());

        Label debugHint = new Label("Click to access debug tools (for development)");
        debugHint.getStyleClass().add("field-hint");

        debugSection = new VBox(4, debugBtn, debugHint);
        debugSection.setPadding(new Insets(8, 0, 0, 0));
        debugSection.setVisible(false);
        debugSection.setManaged(false);

        VBox form = new VBox(16,
                createFieldGroup("Endpoint URL", endpointField),
                createFieldGroup("Token", tokenField),
                createFieldGroup("Encryption Password", encryptionPasswordField),
                createFieldGroupWithHint("Confirm Password", encryptionPasswordConfirmField, encryptionHint),
                saveButton,
                statusLabel,
                debugSection,
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
        title.setMaxWidth(Double.MAX_VALUE);
        title.setAlignment(Pos.CENTER);

        // Use StackPane for true centering - title centered, back button overlaid on left
        StackPane headerStack = new StackPane();
        headerStack.getChildren().addAll(title, backBtn);
        StackPane.setAlignment(backBtn, Pos.CENTER_LEFT);
        StackPane.setAlignment(title, Pos.CENTER);
        HBox.setHgrow(headerStack, Priority.ALWAYS);

        HBox header = new HBox(headerStack);
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

    /**
     * Easter egg: tap copyright 7 times to reveal debug section
     */
    private void onCopyrightTap() {
        long now = System.currentTimeMillis();
        
        // Reset count if more than 1 second since last tap
        if (now - lastTapTime > 1000) {
            copyrightTapCount = 0;
        }
        lastTapTime = now;
        copyrightTapCount++;
        
        if (copyrightTapCount >= 7 && !debugSection.isVisible()) {
            debugSection.setVisible(true);
            debugSection.setManaged(true);
            statusLabel.setText("Debug mode enabled!");
            statusLabel.getStyleClass().removeAll("error", "success");
            statusLabel.getStyleClass().add("success");
        } else if (copyrightTapCount >= 4 && copyrightTapCount < 7) {
            int remaining = 7 - copyrightTapCount;
            statusLabel.setText(remaining + " more tap(s) to enable debug mode");
            statusLabel.getStyleClass().removeAll("error", "success");
        }
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

        // 保存变更前的状态
        String oldEndpoint = settings.getEndpointUrl();
        String oldToken = settings.getToken();
        String oldPassword = settings.getEncryptionPassword();
        boolean wasConfiguredBefore = settings.isConfigured();

        // 获取新的配置值
        String newEndpoint = endpointField.getText().trim();
        String newToken = tokenField.getText();
        String newPassword = password;

        // 检查关键配置是否变更
        boolean endpointChanged = !java.util.Objects.equals(oldEndpoint, newEndpoint);
        boolean tokenChanged = !java.util.Objects.equals(oldToken, newToken);
        boolean passwordChanged = !java.util.Objects.equals(oldPassword, newPassword);
        boolean configurationChanged = endpointChanged || tokenChanged || passwordChanged;

        // 保存新设置
        settings.setEndpointUrl(newEndpoint);
        settings.setToken(newToken);
        settings.setEncryptionPassword(newPassword);
        settings.save();

        String msg = settings.isEncryptionEnabled()
            ? "Settings saved ✓ (E2E encryption enabled)"
            : "Settings saved ✓";

        if (configurationChanged && wasConfiguredBefore) {
            // 配置变更：需要清理旧状态并重新初始化
            System.out.println("[SettingsView] Configuration changed, reinitializing services...");
            System.out.println("[SettingsView] - Endpoint changed: " + endpointChanged);
            System.out.println("[SettingsView] - Token changed: " + tokenChanged);
            System.out.println("[SettingsView] - Password changed: " + passwordChanged);
            
            statusLabel.setText("Configuration changed, reconnecting...");
            statusLabel.getStyleClass().removeAll("error", "success");
            statusLabel.getStyleClass().add("success");

            new Thread(() -> {
                try {
                    ServiceManager.getInstance().reinitializeServices();
                    // 更新UI状态
                    javafx.application.Platform.runLater(() -> {
                        statusLabel.setText(msg + " (Reconnected)");
                    });
                } catch (Exception e) {
                    System.err.println("[SettingsView] Reinitialization failed: " + e.getMessage());
                    javafx.application.Platform.runLater(() -> {
                        statusLabel.setText("Settings saved, but reconnection failed");
                        statusLabel.getStyleClass().removeAll("error", "success");
                        statusLabel.getStyleClass().add("error");
                    });
                }
            }, "SettingsReinit").start();

        } else if (!wasConfiguredBefore && settings.isConfigured()) {
            // 首次配置：正常初始化
            System.out.println("[SettingsView] New configuration detected, initializing services...");
            
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

        } else {
            // 无关键配置变更或配置为空
            statusLabel.setText(msg);
            statusLabel.getStyleClass().removeAll("error", "success");
            statusLabel.getStyleClass().add("success");
        }
    }
}
