package cn.keevol.keenotes.mobilefx;

import javafx.animation.PauseTransition;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;

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
    private final ToggleSwitch copyToClipboardToggle;
    private final ToggleSwitch showOverviewCardToggle;
    private final ToggleSwitch themeToggle;
    private final KeyCaptureField searchShortcutField;
    private final KeyCaptureField sendShortcutField;
    private final KeyCaptureField zoomInShortcutField;
    private final KeyCaptureField zoomOutShortcutField;

    private final TextField localImportServerPortField;


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

        localImportServerPortField = new TextField();
        localImportServerPortField.getStyleClass().add("input-field");
        Runnable localImportServerPortFieldAction = () -> {
            saveSettings();
            localImportServerPortField.setText(String.valueOf(settings.getLocalImportServerPort()));
        };
        localImportServerPortField.setOnAction((e) -> localImportServerPortFieldAction.run());
        localImportServerPortField.focusedProperty().addListener((o, oldValue, newValue) -> {
            if (!newValue) {
                localImportServerPortFieldAction.run();
            }
        });

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

            // Check if any field is empty
            if (endpoint == null || endpoint.trim().isEmpty() || token == null || token.trim().isEmpty() || password == null || password.trim().isEmpty() || confirmPassword == null || confirmPassword.trim().isEmpty()) {
                return true;
            }

            // Check if passwords match
            return !password.equals(confirmPassword);
        }, endpointField.textProperty(), tokenField.textProperty(), encryptionPasswordField.textProperty(), encryptionPasswordConfirmField.textProperty()));

        // Create save button row with same layout as input fields
        Label saveButtonSpacer = new Label();
        saveButtonSpacer.setMinWidth(259);
        saveButtonSpacer.setMaxWidth(259);

        HBox.setHgrow(saveButton, Priority.ALWAYS);

        HBox saveButtonRow = new HBox(16, saveButtonSpacer, saveButton);
        saveButtonRow.setAlignment(Pos.CENTER_LEFT);

        // Status label row with same layout
        Label statusSpacer = new Label();
        statusSpacer.setMinWidth(259);
        statusSpacer.setMaxWidth(259);

        HBox statusRow = new HBox(16, statusSpacer, statusLabel);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        // Debug section (hidden by default) - DEFINE FIRST before using in easter egg
        VBox debugSection = new VBox(12);
        debugSection.setVisible(false);
        debugSection.setManaged(false);
        debugSection.setAlignment(Pos.CENTER);

        Label debugLabel = new Label("Debug Tools");
        debugLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: -fx-text-primary;");

        Button clearDataButton = new Button("Clear Local Notes & Reset Sync");
        clearDataButton.getStyleClass().addAll("action-button");
        clearDataButton.setMaxWidth(Double.MAX_VALUE);
        clearDataButton.setOnAction(e -> clearLocalData());

        debugSection.getChildren().addAll(debugLabel, clearDataButton);

        // Copyright footer
        Label copyrightLabel = new Label("©2025 王福强(Fuqiang Wang)  All Rights Reserved");
        copyrightLabel.getStyleClass().add("copyright-label");

        // Easter egg: click 7 times to show debug section
        final int[] clickCount = {0};
        final long[] lastClickTime = {0};
        copyrightLabel.setOnMouseClicked(e -> {
            long now = System.currentTimeMillis();
            if (now - lastClickTime[0] > 1000) {
                clickCount[0] = 0;
            }
            lastClickTime[0] = now;
            clickCount[0]++;

            if (clickCount[0] >= 7 && !debugSection.isVisible()) {
                debugSection.setVisible(true);
                debugSection.setManaged(true);
                statusLabel.setText("Debug mode enabled!");
                statusLabel.getStyleClass().removeAll("error", "success");
                statusLabel.getStyleClass().add("success");
            }
        });
        copyrightLabel.setStyle("-fx-cursor: hand;");

        Label websiteLabel = new Label("https://keenotes.afoo.me");
        websiteLabel.getStyleClass().add("copyright-link");

        VBox footer = new VBox(4, copyrightLabel, websiteLabel);
        footer.setAlignment(Pos.CENTER);
        footer.setPadding(new Insets(12, 0, 12, 0));
        footer.getStyleClass().add("footer-fixed");

        // Encryption hint
        Label encryptionHint = new Label("You MUST set password to enable E2E encryption");
        encryptionHint.getStyleClass().add("field-hint");

        // Preferences section
        Label preferencesLabel = new Label("Preferences");
        preferencesLabel.getStyleClass().add("field-label");
        preferencesLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        copyToClipboardToggle = new ToggleSwitch();

        // Auto-save on change
        copyToClipboardToggle.selectedProperty().addListener((obs, oldVal, newVal) -> {
            settings.setCopyToClipboardOnPost(newVal);
            settings.save();
        });

        Label toggleLabel = new Label("Copy to clipboard on post success");
        toggleLabel.getStyleClass().add("field-label");
        toggleLabel.setMinWidth(259);
        toggleLabel.setMaxWidth(259);
        toggleLabel.setAlignment(Pos.CENTER_RIGHT);

        HBox toggleRow = new HBox(16, toggleLabel, copyToClipboardToggle);
        toggleRow.setAlignment(Pos.CENTER_LEFT);

        // Show Overview Card toggle
        showOverviewCardToggle = new ToggleSwitch();
        showOverviewCardToggle.selectedProperty().addListener((obs, oldVal, newVal) -> {
            settings.setShowOverviewCard(newVal);
            settings.save();
        });

        Label overviewCardLabel = new Label("Show Overview Card");
        overviewCardLabel.getStyleClass().add("field-label");
        overviewCardLabel.setMinWidth(259);
        overviewCardLabel.setMaxWidth(259);
        overviewCardLabel.setAlignment(Pos.CENTER_RIGHT);

        HBox overviewCardRow = new HBox(16, overviewCardLabel, showOverviewCardToggle);
        overviewCardRow.setAlignment(Pos.CENTER_LEFT);

        // Theme toggle
        themeToggle = new ToggleSwitch();
        themeToggle.selectedProperty().addListener((obs, oldVal, newVal) -> {
            ThemeService.Theme theme = newVal ? ThemeService.Theme.LIGHT : ThemeService.Theme.DARK;
            ThemeService.getInstance().setTheme(theme);
        });

        Label themeLabel = new Label("Light Theme");
        themeLabel.getStyleClass().add("field-label");
        themeLabel.setMinWidth(259);
        themeLabel.setMaxWidth(259);
        themeLabel.setAlignment(Pos.CENTER_RIGHT);

        HBox themeRow = new HBox(16, themeLabel, themeToggle);
        themeRow.setAlignment(Pos.CENTER_LEFT);

        // Search shortcut configuration
        Label shortcutLabel = new Label("Search shortcut");
        shortcutLabel.getStyleClass().add("field-label");
        shortcutLabel.setAlignment(Pos.CENTER_RIGHT);

        searchShortcutField = new KeyCaptureField();

        Label shortcutHint = new Label("Click the field and press your desired key combination (e.g., Ctrl+Shift+F)");
        shortcutHint.getStyleClass().add("field-hint");

        VBox shortcutFieldWithHint = new VBox(6, searchShortcutField, shortcutHint);
        HBox.setHgrow(shortcutFieldWithHint, Priority.ALWAYS);

        // Wrap label in VBox with top padding to align with field
        VBox shortcutLabelWrapper = new VBox(shortcutLabel);
        shortcutLabelWrapper.setAlignment(Pos.TOP_RIGHT);
        shortcutLabelWrapper.setPadding(new Insets(8, 0, 0, 0)); // Align with field's vertical center
        shortcutLabelWrapper.setMinWidth(259);
        shortcutLabelWrapper.setMaxWidth(259);

        HBox shortcutRow = new HBox(16, shortcutLabelWrapper, shortcutFieldWithHint);
        shortcutRow.setAlignment(Pos.TOP_LEFT);

        // Send shortcut configuration
        Label sendShortcutLabel = new Label("Send shortcut");
        sendShortcutLabel.getStyleClass().add("field-label");
        sendShortcutLabel.setAlignment(Pos.CENTER_RIGHT);

        sendShortcutField = new KeyCaptureField();

        Label sendShortcutHint = new Label("Click the field and press your desired key combination (e.g., Alt+Enter)");
        sendShortcutHint.getStyleClass().add("field-hint");

        VBox sendShortcutFieldWithHint = new VBox(6, sendShortcutField, sendShortcutHint);
        HBox.setHgrow(sendShortcutFieldWithHint, Priority.ALWAYS);

        // Wrap label in VBox with top padding to align with field
        VBox sendShortcutLabelWrapper = new VBox(sendShortcutLabel);
        sendShortcutLabelWrapper.setAlignment(Pos.TOP_RIGHT);
        sendShortcutLabelWrapper.setPadding(new Insets(8, 0, 0, 0)); // Align with field's vertical center
        sendShortcutLabelWrapper.setMinWidth(259);
        sendShortcutLabelWrapper.setMaxWidth(259);

        HBox sendShortcutRow = new HBox(16, sendShortcutLabelWrapper, sendShortcutFieldWithHint);
        sendShortcutRow.setAlignment(Pos.TOP_LEFT);

        // Zoom in shortcut configuration
        Label zoomInShortcutLabel = new Label("Zoom in shortcut");
        zoomInShortcutLabel.getStyleClass().add("field-label");
        zoomInShortcutLabel.setAlignment(Pos.CENTER_RIGHT);

        zoomInShortcutField = new KeyCaptureField();

        Label zoomInShortcutHint = new Label("Increase Note Card font size (e.g., Meta+EQUALS)");
        zoomInShortcutHint.getStyleClass().add("field-hint");

        VBox zoomInShortcutFieldWithHint = new VBox(6, zoomInShortcutField, zoomInShortcutHint);
        HBox.setHgrow(zoomInShortcutFieldWithHint, Priority.ALWAYS);

        VBox zoomInShortcutLabelWrapper = new VBox(zoomInShortcutLabel);
        zoomInShortcutLabelWrapper.setAlignment(Pos.TOP_RIGHT);
        zoomInShortcutLabelWrapper.setPadding(new Insets(8, 0, 0, 0));
        zoomInShortcutLabelWrapper.setMinWidth(259);
        zoomInShortcutLabelWrapper.setMaxWidth(259);

        HBox zoomInShortcutRow = new HBox(16, zoomInShortcutLabelWrapper, zoomInShortcutFieldWithHint);
        zoomInShortcutRow.setAlignment(Pos.TOP_LEFT);

        // Zoom out shortcut configuration
        Label zoomOutShortcutLabel = new Label("Zoom out shortcut");
        zoomOutShortcutLabel.getStyleClass().add("field-label");
        zoomOutShortcutLabel.setAlignment(Pos.CENTER_RIGHT);

        zoomOutShortcutField = new KeyCaptureField();

        Label zoomOutShortcutHint = new Label("Decrease Note Card font size (e.g., Meta+MINUS)");
        zoomOutShortcutHint.getStyleClass().add("field-hint");

        VBox zoomOutShortcutFieldWithHint = new VBox(6, zoomOutShortcutField, zoomOutShortcutHint);
        HBox.setHgrow(zoomOutShortcutFieldWithHint, Priority.ALWAYS);

        VBox zoomOutShortcutLabelWrapper = new VBox(zoomOutShortcutLabel);
        zoomOutShortcutLabelWrapper.setAlignment(Pos.TOP_RIGHT);
        zoomOutShortcutLabelWrapper.setPadding(new Insets(8, 0, 0, 0));
        zoomOutShortcutLabelWrapper.setMinWidth(259);
        zoomOutShortcutLabelWrapper.setMaxWidth(259);

        HBox zoomOutShortcutRow = new HBox(16, zoomOutShortcutLabelWrapper, zoomOutShortcutFieldWithHint);
        zoomOutShortcutRow.setAlignment(Pos.TOP_LEFT);

        HBox localPortRow = createPrefRow("Local Import Server Port", localImportServerPortField);

        // Preferences container with proper spacing
        VBox preferencesSection = new VBox(12, preferencesLabel, toggleRow, overviewCardRow, themeRow, shortcutRow, sendShortcutRow, zoomInShortcutRow, zoomOutShortcutRow, localPortRow);
        preferencesSection.setPadding(new Insets(8, 0, 8, 0)); // Add top/bottom padding

        // Debug entry (hidden by default) - removed for desktop version

        VBox form = new VBox(16, createFieldGroup("Endpoint URL", endpointField), createFieldGroup("Token", tokenField), createFieldGroup("Encryption Password", encryptionPasswordField), createFieldGroupWithHint("Confirm Password", encryptionPasswordConfirmField, encryptionHint), saveButtonRow, statusRow, preferencesSection, debugSection);
        form.setPadding(new Insets(24));
        form.setAlignment(Pos.TOP_CENTER);

        ScrollPane scrollPane = new ScrollPane(form);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("content-scroll");
        setCenter(scrollPane);

        // Set footer at bottom (fixed position)
        setBottom(footer);

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
        label.setMinWidth(259);
        label.setMaxWidth(259);
        label.setAlignment(Pos.CENTER_RIGHT);

        // Make field grow to fill available space
        HBox.setHgrow(field, Priority.ALWAYS);
        field.setMaxWidth(Double.MAX_VALUE);

        HBox row = new HBox(16, label, field);
        row.setAlignment(Pos.CENTER_LEFT);

        VBox group = new VBox(row);
        return group;
    }

    private VBox createFieldGroupWithHint(String labelText, Control field, Label hint) {
        Label label = new Label(labelText);
        label.getStyleClass().add("field-label");
        label.setMinWidth(259);
        label.setMaxWidth(259);
        label.setAlignment(Pos.CENTER_RIGHT);

        // Make field grow to fill available space
        field.setMaxWidth(Double.MAX_VALUE);

        VBox fieldWithHint = new VBox(6, field, hint);
        HBox.setHgrow(fieldWithHint, Priority.ALWAYS);

        // Align label with the field (first child), not the whole VBox
        HBox row = new HBox(16);
        row.setAlignment(Pos.TOP_LEFT);

        // Wrap label in VBox with top padding to align with field
        VBox labelWrapper = new VBox(label);
        labelWrapper.setAlignment(Pos.TOP_RIGHT);
        labelWrapper.setPadding(new Insets(8, 0, 0, 0)); // Align with field's vertical center
        labelWrapper.setMinWidth(259);
        labelWrapper.setMaxWidth(259);

        row.getChildren().addAll(labelWrapper, fieldWithHint);

        VBox group = new VBox(row);
        return group;
    }

    private void loadSettings() {
        endpointField.setText(settings.getEndpointUrl());
        tokenField.setText(settings.getToken());
        String savedPassword = settings.getEncryptionPassword();
        encryptionPasswordField.setText(savedPassword);
        encryptionPasswordConfirmField.setText(savedPassword);
        copyToClipboardToggle.setSelected(settings.getCopyToClipboardOnPost());
        showOverviewCardToggle.setSelected(settings.getShowOverviewCard());
        themeToggle.setSelected(ThemeService.getInstance().getCurrentTheme() == ThemeService.Theme.LIGHT);
        searchShortcutField.setShortcut(settings.getSearchShortcut());
        sendShortcutField.setShortcut(settings.getSendShortcut());
        zoomInShortcutField.setShortcut(settings.getZoomInShortcut());
        zoomOutShortcutField.setShortcut(settings.getZoomOutShortcut());
        localImportServerPortField.setText(String.valueOf(settings.getLocalImportServerPort()));
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
        // Save search shortcut
        String shortcut = searchShortcutField.getShortcut();
        if (!shortcut.isEmpty()) {
            settings.setSearchShortcut(shortcut);
        }
        // Save send shortcut
        String sendShortcut = sendShortcutField.getShortcut();
        if (!sendShortcut.isEmpty()) {
            settings.setSendShortcut(sendShortcut);
        }
        // Save zoom shortcuts
        String zoomInShortcut = zoomInShortcutField.getShortcut();
        if (!zoomInShortcut.isEmpty()) {
            settings.setZoomInShortcut(zoomInShortcut);
        }
        String zoomOutShortcut = zoomOutShortcutField.getShortcut();
        if (!zoomOutShortcut.isEmpty()) {
            settings.setZoomOutShortcut(zoomOutShortcut);
        }
        String localPort = localImportServerPortField.getText();
        if (localPort == null || localPort.isEmpty()) {
            settings.setLocalImportServerPort(1979);
        } else {
            try {
                settings.setLocalImportServerPort(Integer.parseInt(localPort));
            } catch (Throwable t) {
                settings.setLocalImportServerPort(1979);
            }
        }
        // copyToClipboard is auto-saved on checkbox change
        settings.save();

        String msg = settings.isEncryptionEnabled() ? "Settings saved ✓ (E2E encryption enabled)" : "Settings saved ✓";

        if (configurationChanged && wasConfiguredBefore) {
            // 配置变更：需要清理旧状态并重新初始化
            System.out.println("[SettingsView] Configuration changed, reinitializing services...");
            System.out.println("[SettingsView] - Endpoint changed: " + endpointChanged);
            System.out.println("[SettingsView] - Token changed: " + tokenChanged);
            System.out.println("[SettingsView] - Password changed: " + passwordChanged);

            statusLabel.setText("Configuration changed, reconnecting...");
            statusLabel.getStyleClass().removeAll("error", "success");
            statusLabel.getStyleClass().add("success");

            // Capture endpointChanged for use in thread
            // 根据issue #10要求：endpoint或token变化都需要清空本地数据
            final boolean clearNotes = endpointChanged || tokenChanged;

            new Thread(() -> {
                try {
                    // Pass clearNotes to determine if notes should be cleared
                    // Endpoint或Token变更 → 清空notes（不同服务器或不同账户，数据完全不同）
                    // Password变更 → 只重置sync_state（同一服务器同一账户，数据相同）
                    ServiceManager.getInstance().reinitializeServices(clearNotes);
                    // 更新UI状态
                    javafx.application.Platform.runLater(() -> {
                        statusLabel.setText(msg + " (Reconnected)");
                        // Wait 500ms then navigate back
                        PauseTransition delay = new PauseTransition(Duration.millis(500));
                        delay.setOnFinished(e -> onBack.run());
                        delay.play();
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

            // Wait 500ms then navigate back
            PauseTransition delay = new PauseTransition(Duration.millis(500));
            delay.setOnFinished(e -> onBack.run());
            delay.play();

        } else {
            // 无关键配置变更或配置为空
            statusLabel.setText(msg);
            statusLabel.getStyleClass().removeAll("error", "success");
            statusLabel.getStyleClass().add("success");

            // Wait 500ms then navigate back
            PauseTransition delay = new PauseTransition(Duration.millis(500));
            delay.setOnFinished(e -> onBack.run());
            delay.play();
        }
    }

    private void clearLocalData() {
        statusLabel.setText("Clearing local data...");
        statusLabel.getStyleClass().removeAll("error", "success");
        statusLabel.getStyleClass().add("success");

        new Thread(() -> {
            try {
                ServiceManager serviceManager = ServiceManager.getInstance();
                LocalCacheService cache = serviceManager.getLocalCacheService();

                // Clear all local notes and reset sync state
                cache.clearAllData();

                javafx.application.Platform.runLater(() -> {
                    statusLabel.setText("✓ Local data cleared! Reconnecting...");
                    statusLabel.getStyleClass().removeAll("error", "success");
                    statusLabel.getStyleClass().add("success");
                });

                // Reconnect WebSocket to trigger fresh sync
                Thread.sleep(500);
                WebSocketClientService webSocketService = serviceManager.getWebSocketService();
                webSocketService.disconnect();
                Thread.sleep(500);
                webSocketService.connect();

                javafx.application.Platform.runLater(() -> {
                    statusLabel.setText("✓ Data cleared and reconnected!");
                });

            } catch (Exception e) {
                javafx.application.Platform.runLater(() -> {
                    statusLabel.setText("✗ Failed to clear data: " + e.getMessage());
                    statusLabel.getStyleClass().removeAll("error", "success");
                    statusLabel.getStyleClass().add("error");
                });
            }
        }, "ClearLocalData").start();
    }

    protected HBox createPrefRow(String labelName, Node node) {
        Label label = new Label(labelName);
        label.getStyleClass().add("field-label");
        label.setMinWidth(259);
        label.setMaxWidth(259);
        label.setAlignment(Pos.CENTER_RIGHT);

        HBox row = new HBox(16, label, node);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }
}
