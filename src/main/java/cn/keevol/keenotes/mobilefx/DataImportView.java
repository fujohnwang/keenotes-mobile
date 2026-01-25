package cn.keevol.keenotes.mobilefx;

import cn.keevol.keenotes.utils.SimpleForwardServer;
import cn.keevol.keenotes.utils.javafx.JFX;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;

/**
 * Data Import view for importing notes from NDJSON files
 */
public class DataImportView extends VBox {
    
    private final DataImportService importService;
    private final SettingsService settings;
    private final Label fileLabel;
    private final Label statusLabel;
    private final Button chooseFileButton;
    private final ProgressBar progressBar;
    private final Label progressLabel;
    private final TextField localImportServerPortField;
    private final Label portStatusLabel;
    private File selectedFile;
    
    public DataImportView() {
        this.importService = new DataImportService(ServiceManager.getInstance().getApiService());
        this.settings = SettingsService.getInstance();
        
        // Initialize fields first
        this.localImportServerPortField = new TextField();
        this.portStatusLabel = new Label("");
        this.chooseFileButton = new Button("Choose File...");
        this.fileLabel = new Label("No file selected");
        this.statusLabel = new Label("");
        this.progressBar = new ProgressBar(0);
        this.progressLabel = new Label("");
        
        setPadding(new Insets(0)); // Remove padding, let sections handle their own
        setSpacing(0);
        setAlignment(Pos.TOP_CENTER);
        
        // Content area with padding
        VBox contentArea = new VBox(20);
        contentArea.setPadding(new Insets(24));
        
        // Local Import Server Port section with shadow
        VBox portSection = createPortSection();
        
        // File import section with shadow
        VBox fileSection = createFileSection();
        
        contentArea.getChildren().addAll(portSection, fileSection);
        
        getChildren().add(contentArea);
    }
    
    private VBox createPortSection() {
        VBox section = new VBox(12);
        section.setPadding(new Insets(16));
        section.setMaxWidth(700);
        section.getStyleClass().add("import-section");
        
        Label sectionLabel = new Label("Local Import Server");
        sectionLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: -fx-text-primary;");
        
        Label descLabel = new Label("Port for local HTTP server that forwards notes to the main endpoint. Default: 1979");
        descLabel.getStyleClass().add("field-hint");
        descLabel.setWrapText(true);
        
        Label portLabel = new Label("Server Port");
        portLabel.getStyleClass().add("field-label");
        portLabel.setMinWidth(259);
        portLabel.setMaxWidth(259);
        portLabel.setAlignment(Pos.CENTER_RIGHT);
        
        localImportServerPortField.getStyleClass().add("input-field");
        localImportServerPortField.setText(String.valueOf(settings.getLocalImportServerPort()));
        HBox.setHgrow(localImportServerPortField, Priority.ALWAYS);
        localImportServerPortField.setMaxWidth(Double.MAX_VALUE);
        
        portStatusLabel.getStyleClass().add("field-hint");
        
        localImportServerPortField.setOnAction((e) -> {
            localImportServerPortField.getParent().requestFocus();
        });
        
        localImportServerPortField.focusedProperty().addListener((o, oldValue, newValue) -> {
            if (!newValue) {
                // Lost focus, check if value changed
                int currentPort = settings.getLocalImportServerPort();
                String inputText = localImportServerPortField.getText();
                int inputPort;
                try {
                    inputPort = Integer.parseInt(inputText);
                } catch (Exception e) {
                    inputPort = 1979; // Default
                }
                
                if (inputPort != currentPort) {
                    // Port changed, update and restart
                    settings.setLocalImportServerPort(inputPort);
                    settings.save();
                    localImportServerPortField.setText(String.valueOf(inputPort));
                    portStatusLabel.setText("✓ Saved. Restarting local import server...");
                    portStatusLabel.setStyle("-fx-text-fill: #4CAF50;");
                    Thread.ofVirtual().start(() -> {
                        SimpleForwardServer.restart();
                        javafx.application.Platform.runLater(() -> {
                            portStatusLabel.setText("✓ Server restarted");
                            new Thread(() -> {
                                try {
                                    Thread.sleep(3000);
                                    javafx.application.Platform.runLater(() -> portStatusLabel.setText(""));
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }).start();
                        });
                    });
                }
            }
        });
        
        HBox portRow = new HBox(16, portLabel, localImportServerPortField);
        portRow.setAlignment(Pos.CENTER_LEFT);
        
        // Status label row
        Label statusSpacer = new Label();
        statusSpacer.setMinWidth(259);
        statusSpacer.setMaxWidth(259);
        
        HBox statusRow = new HBox(16, statusSpacer, portStatusLabel);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        
        section.getChildren().addAll(sectionLabel, descLabel, portRow, statusRow);
        return section;
    }
    
    private VBox createFileSection() {
        VBox section = new VBox(12);
        section.setPadding(new Insets(16));
        section.setMaxWidth(700);
        section.getStyleClass().add("import-section");
        
        Label sectionLabel = new Label("Import from NDJSON File");
        sectionLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: -fx-text-primary;");
        
        Label descLabel = new Label("Import notes from NDJSON file. Each line should contain a JSON object with fields: content, channel, created_at (or ts), and optionally encrypted.");
        descLabel.setWrapText(true);
        descLabel.getStyleClass().add("field-hint");
        
        // Label: 259px width, right aligned (same as SettingsGeneralView)
        Label fileChooseLabel = new Label("Select File");
        fileChooseLabel.getStyleClass().add("field-label");
        fileChooseLabel.setMinWidth(259);
        fileChooseLabel.setMaxWidth(259);
        fileChooseLabel.setAlignment(Pos.CENTER_RIGHT);
        
        // Button style same as Save button in SettingsGeneralView
        chooseFileButton.getStyleClass().clear();
        chooseFileButton.getStyleClass().addAll("action-button", "primary");
        chooseFileButton.setOnAction(e -> chooseFile());
        HBox.setHgrow(chooseFileButton, Priority.ALWAYS);
        chooseFileButton.setMaxWidth(Double.MAX_VALUE);
        
        HBox fileRow = new HBox(16, fileChooseLabel, chooseFileButton);
        fileRow.setAlignment(Pos.CENTER_LEFT);
        
        // Status label row (aligned with label)
        Label statusSpacer = new Label();
        statusSpacer.setMinWidth(259);
        statusSpacer.setMaxWidth(259);
        
        fileLabel.getStyleClass().add("field-hint");
        statusLabel.getStyleClass().add("field-hint");
        statusLabel.setWrapText(true);
        
        VBox statusBox = new VBox(4, fileLabel, statusLabel);
        
        HBox statusRow = new HBox(16, statusSpacer, statusBox);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        
        // Progress section
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        progressBar.setManaged(false);
        
        progressLabel.getStyleClass().add("field-hint");
        progressLabel.setVisible(false);
        progressLabel.setManaged(false);
        
        Label progressSpacer = new Label();
        progressSpacer.setMinWidth(259);
        progressSpacer.setMaxWidth(259);
        
        VBox progressContent = new VBox(8, progressBar, progressLabel);
        HBox.setHgrow(progressContent, Priority.ALWAYS);
        
        HBox progressRow = new HBox(16, progressSpacer, progressContent);
        progressRow.setAlignment(Pos.CENTER_LEFT);
        
        section.getChildren().addAll(sectionLabel, descLabel, fileRow, statusRow, progressRow);
        return section;
    }
    
    private void chooseFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select NDJSON File");
        fileChooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("NDJSON Files", "*.ndjson", "*.jsonl", "*.json")
        );
        
        File file = fileChooser.showOpenDialog(getScene().getWindow());
        
        if (file != null && file.exists()) {
            selectedFile = file;
            fileLabel.setText(file.getName());
            statusLabel.setText("Validating file format...");
            statusLabel.setStyle("-fx-text-fill: -fx-text-primary;");
            
            // Validate file
            importService.validateFile(file).thenAccept(result -> {
                javafx.application.Platform.runLater(() -> {
                    if (result.valid()) {
                        statusLabel.setText("✓ Valid format (" + result.lineCount() + " lines). Starting import in background...");
                        statusLabel.setStyle("-fx-text-fill: #4CAF50;");
                        
                        // Start import
                        startImport(file, result.lineCount());
                        
                    } else {
                        statusLabel.setText("✗ " + result.errorMessage());
                        statusLabel.setStyle("-fx-text-fill: #F44336;");
                        selectedFile = null;
                    }
                });
            });
        }
    }
    
    private void startImport(File file, int totalLines) {
        // Show progress UI
        progressBar.setVisible(true);
        progressBar.setManaged(true);
        progressLabel.setVisible(true);
        progressLabel.setManaged(true);
        progressBar.setProgress(0);
        progressLabel.setText("Importing 0 / " + totalLines);
        
        // Disable file chooser during import
        chooseFileButton.setDisable(true);
        
        // Notify status bar
        notifyImportStarted();
        
        // Start import
        importService.importFile(file, new DataImportService.ImportProgressListener() {
            @Override
            public void onProgress(int current, int total) {
                double progress = (double) current / total;
                progressBar.setProgress(progress);
                progressLabel.setText("Importing " + current + " / " + total);
            }
            
            @Override
            public void onComplete(int success, int failed, String failedFilePath) {
                // Hide progress UI
                progressBar.setVisible(false);
                progressBar.setManaged(false);
                progressLabel.setVisible(false);
                progressLabel.setManaged(false);
                
                // Clear file selection
                selectedFile = null;
                fileLabel.setText("No file selected");
                
                // Enable file chooser
                chooseFileButton.setDisable(false);
                
                // Show result
                String message = "✓ Import complete: " + success + " succeeded";
                if (failed > 0) {
                    message += ", " + failed + " failed";
                    if (failedFilePath != null) {
                        message += ". Failed items saved to: " + failedFilePath;
                    }
                }
                statusLabel.setText(message);
                statusLabel.setStyle("-fx-text-fill: " + (failed > 0 ? "#FF9800" : "#4CAF50") + ";");
                
                // Notify status bar
                notifyImportCompleted(success, failed);
                
                // Show system notification
                showNotification("Import Complete", message);
            }
            
            @Override
            public void onError(String error) {
                // Hide progress UI
                progressBar.setVisible(false);
                progressBar.setManaged(false);
                progressLabel.setVisible(false);
                progressLabel.setManaged(false);
                
                // Enable file chooser
                chooseFileButton.setDisable(false);
                
                // Show error
                statusLabel.setText("✗ Import error: " + error);
                statusLabel.setStyle("-fx-text-fill: #F44336;");
                
                // Notify status bar
                notifyImportError(error);
                
                // Show system notification
                showNotification("Import Error", error);
            }
        });
    }
    
    private void notifyImportStarted() {
        // Find StatusFooterBar and update
        javafx.scene.Node node = this;
        while (node != null) {
            if (node instanceof javafx.scene.layout.BorderPane) {
                javafx.scene.Node bottom = ((javafx.scene.layout.BorderPane) node).getBottom();
                if (bottom instanceof StatusFooterBar) {
                    ((StatusFooterBar) bottom).setImportStatus("Importing...", true);
                    break;
                }
            }
            node = node.getParent();
        }
    }
    
    private void notifyImportCompleted(int success, int failed) {
        // Find StatusFooterBar and update
        javafx.scene.Node node = this;
        while (node != null) {
            if (node instanceof javafx.scene.layout.BorderPane) {
                javafx.scene.Node bottom = ((javafx.scene.layout.BorderPane) node).getBottom();
                if (bottom instanceof StatusFooterBar) {
                    String message = success + " imported";
                    if (failed > 0) {
                        message += ", " + failed + " failed";
                    }
                    ((StatusFooterBar) bottom).setImportStatus(message, false);
                    break;
                }
            }
            node = node.getParent();
        }
    }
    
    private void notifyImportError(String error) {
        // Find StatusFooterBar and update
        javafx.scene.Node node = this;
        while (node != null) {
            if (node instanceof javafx.scene.layout.BorderPane) {
                javafx.scene.Node bottom = ((javafx.scene.layout.BorderPane) node).getBottom();
                if (bottom instanceof StatusFooterBar) {
                    ((StatusFooterBar) bottom).setImportStatus("Import failed", false);
                    break;
                }
            }
            node = node.getParent();
        }
    }
    
    private void showNotification(String title, String message) {
        // Find DesktopMainView and show popup notification
        javafx.scene.Node node = this;
        while (node != null) {
            if (node instanceof DesktopMainView) {
                ((DesktopMainView) node).showPopupNotification(title + ": " + message);
                return;
            }
            node = node.getParent();
        }
    }
    
    private void updateLocalPortSetting() {
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
    }
}
