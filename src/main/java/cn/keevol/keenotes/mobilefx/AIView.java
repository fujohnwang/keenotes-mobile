package cn.keevol.keenotes.mobilefx;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;
import javafx.animation.PauseTransition;
import javafx.util.Duration;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Subscription;

/**
 * AI Integration view for MCP server and AI assistant configurations
 */
public class AIView extends VBox {
    private final SearchSettingsPane searchSettings = new SearchSettingsPane();
    private final Subscription tabContentLayoutSubscription;
    private final PauseTransition copyFeedback = new PauseTransition(Duration.seconds(2));

    public void dispose() {
        tabContentLayoutSubscription.unsubscribe();
        copyFeedback.stop();
        searchSettings.dispose();
    }
    
    private final SettingsService settings;
    private final TextField mcpServerPortField;
    private final Label mcpStatusLabel;
    private final Label mcpEndpointLabel;
    
    public AIView() {
        this.settings = SettingsService.getInstance();
        
        // Initialize fields
        this.mcpServerPortField = new TextField();
        this.mcpStatusLabel = new Label("");
        this.mcpEndpointLabel = new Label();
        
        setPadding(new Insets(0));
        setSpacing(0);
        setAlignment(Pos.TOP_CENTER);
        
        // Content area with padding
        VBox contentArea = new VBox(20);
        contentArea.setPadding(new Insets(24));
        contentArea.setMaxWidth(1168);
        
        // MCP Server section
        VBox mcpSection = createMcpSection();
        
        TabPane tabs = new TabPane(new Tab("MCP", mcpSection), new Tab("Local Search", searchSettings)) {
            @Override public javafx.geometry.Orientation getContentBias() { return javafx.geometry.Orientation.HORIZONTAL; }
            @Override protected double computePrefHeight(double width) {
                if (width < 0) width = Math.max(getWidth(), 600);
                Tab selected = getSelectionModel().getSelectedItem();
                javafx.scene.Node header = lookup(".tab-header-area");
                double headerHeight = header == null ? 54 : header.prefHeight(width);
                double contentWidth = Math.max(0, width - snappedLeftInset() - snappedRightInset());
                return snappedTopInset() + headerHeight + snappedBottomInset()
                        + (selected == null || selected.getContent() == null ? 0 : selected.getContent().prefHeight(contentWidth));
            }
        };
        tabs.setId("ai-settings-tabs");
        tabs.getStyleClass().add("ai-settings-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setTabMinHeight(36);
        tabs.setMinHeight(USE_PREF_SIZE);
        tabs.setMaxHeight(USE_PREF_SIZE);
        tabs.getSelectionModel().selectedItemProperty().addListener((o, old, selected) -> tabs.requestLayout());
        // The skin's unmanaged content containers stop layout invalidation from reaching
        // the TabPane. Forward it so expanding content also updates our preferred height.
        java.util.function.BiConsumer<Boolean, Boolean> contentLayoutChanged = (old, needsLayout) -> {
            if (needsLayout) tabs.requestLayout();
        };
        tabContentLayoutSubscription = Subscription.combine(
                searchSettings.needsLayoutProperty().subscribe(contentLayoutChanged),
                mcpSection.needsLayoutProperty().subscribe(contentLayoutChanged));
        contentArea.getChildren().add(tabs);
        
        getChildren().add(contentArea);
    }
    
    private VBox createMcpSection() {
        VBox section = new VBox(12);
        section.setPadding(new Insets(24, 0, 8, 0));
        section.setMaxWidth(Double.MAX_VALUE);
        section.getStyleClass().add("import-section");
        
        Label sectionLabel = new Label("MCP Server");
        sectionLabel.getStyleClass().add("search-page-title");
        
        Label descLabel = new Label("Expose KeeNotes as an MCP server for AI assistants (Claude, Kiro, etc.). Default port: 1999");
        descLabel.getStyleClass().add("field-hint");
        descLabel.setWrapText(true);
        
        Label portLabel = new Label("MCP Server Port");
        portLabel.getStyleClass().add("field-label");
        portLabel.setMinWidth(145);
        portLabel.setMaxWidth(145);
        portLabel.setAlignment(Pos.CENTER_RIGHT);
        
        mcpServerPortField.getStyleClass().add("input-field");
        mcpServerPortField.setText(String.valueOf(settings.getMcpServerPort()));
        HBox.setHgrow(mcpServerPortField, Priority.ALWAYS);
        mcpServerPortField.setMaxWidth(180);
        
        mcpStatusLabel.getStyleClass().add("field-hint");
        
        mcpServerPortField.setOnAction((e) -> {
            mcpServerPortField.getParent().requestFocus();
        });
        
        mcpServerPortField.focusedProperty().addListener((o, oldValue, newValue) -> {
            if (!newValue) {
                // Lost focus, check if value changed
                int currentPort = settings.getMcpServerPort();
                String inputText = mcpServerPortField.getText();
                int inputPort;
                try {
                    inputPort = Integer.parseInt(inputText);
                } catch (Exception e) {
                    inputPort = 1999; // Default
                }
                
                if (inputPort != currentPort) {
                    // Port changed, update and restart
                    final int finalInputPort = inputPort;
                    settings.setMcpServerPort(finalInputPort);
                    settings.save();
                    mcpServerPortField.setText(String.valueOf(finalInputPort));
                    updateMcpEndpointLabel(); // Update endpoint label
                    mcpStatusLabel.setText("✓ Saved. Restarting MCP server...");
                    mcpStatusLabel.setStyle("-fx-text-fill: #4CAF50;");
                    Thread.ofVirtual().start(() -> {
                        cn.keevol.keenotes.mcp.SimpleMcpServer.restart();
                        javafx.application.Platform.runLater(() -> {
                            mcpStatusLabel.setText("✓ MCP server restarted on port " + finalInputPort);
                            new Thread(() -> {
                                try {
                                    Thread.sleep(3000);
                                    javafx.application.Platform.runLater(() -> mcpStatusLabel.setText(""));
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }).start();
                        });
                    });
                }
            }
        });
        
        HBox portRow = new HBox(16, portLabel, mcpServerPortField);
        portRow.setAlignment(Pos.CENTER_LEFT);
        
        // Endpoint label row (clickable to copy)
        updateMcpEndpointLabel();
        mcpEndpointLabel.getStyleClass().add("field-hint");
        mcpEndpointLabel.setStyle("-fx-font-style: italic; -fx-cursor: hand;");
        
        // Click to copy endpoint to clipboard
        mcpEndpointLabel.setOnMouseClicked(event -> {
            int port = settings.getMcpServerPort();
            String endpoint = "http://localhost:" + port + "/mcp";
            
            // Copy to clipboard
            javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(endpoint);
            clipboard.setContent(content);
            
            // Show temporary feedback in status label
            mcpStatusLabel.setText("✓ Copied to clipboard!");
            mcpStatusLabel.setStyle("-fx-text-fill: #4CAF50;");
            
            // Clear after 2 seconds
            new Thread(() -> {
                try {
                    Thread.sleep(2000);
                    javafx.application.Platform.runLater(() -> {
                        mcpStatusLabel.setText("");
                        mcpStatusLabel.setStyle("");
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        });
        
        Label endpointSpacer = new Label();
        endpointSpacer.setMinWidth(145);
        endpointSpacer.setMaxWidth(145);
        
        HBox endpointRow = new HBox(16, endpointSpacer, mcpEndpointLabel);
        endpointRow.setAlignment(Pos.CENTER_LEFT);
        
        // Status label row (reused for both port change status and copy feedback)
        mcpStatusLabel.getStyleClass().add("field-hint");
        
        Label statusSpacer = new Label();
        statusSpacer.setMinWidth(145);
        statusSpacer.setMaxWidth(145);
        
        HBox statusRow = new HBox(16, statusSpacer, mcpStatusLabel);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        
        section.getChildren().addAll(sectionLabel, descLabel, portRow, endpointRow, statusRow);
        
        // Example configuration section (collapsible)
        VBox exampleSection = createExampleConfigSection();
        section.getChildren().add(exampleSection);
        
        return section;
    }
    
    private void updateMcpEndpointLabel() {
        int port = settings.getMcpServerPort();
        mcpEndpointLabel.setText("Endpoint: http://localhost:" + port + "/mcp");
    }
    
    private VBox createExampleConfigSection() {
        VBox section = new VBox(8);
        section.setPadding(new Insets(12, 0, 0, 0));
        
        // Spacer for alignment (145px like other rows)
        Label headerSpacer = new Label();
        headerSpacer.setMinWidth(145);
        headerSpacer.setMaxWidth(145);
        
        // Example header (clickable to show/hide)
        Label exampleHeader = new Label("▶ Example Configuration");
        exampleHeader.getStyleClass().add("field-hint");
        exampleHeader.setStyle("-fx-cursor: hand;");
        
        HBox headerRow = new HBox(16, headerSpacer, exampleHeader);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        
        // Example content (initially hidden)
        VBox exampleContent = new VBox(8);
        exampleContent.setVisible(false);
        exampleContent.setManaged(false);
        
        Label exampleDesc = new Label("Add this to your AI assistant's MCP configuration file:");
        exampleDesc.getStyleClass().add("field-hint");
        
        // Configuration example text
        int port = settings.getMcpServerPort();
        String configExample = String.format("""
            {
              "mcpServers": {
                "keenotes": {
                  "url": "http://localhost:%d/mcp",
                  "transport": "http"
                }
              }
            }""", port);
        
        javafx.scene.control.TextArea configText = new javafx.scene.control.TextArea(configExample);
        configText.setEditable(false);
        configText.setWrapText(false);
        configText.setPrefRowCount(11);
        configText.getStyleClass().add("input-field");
        configText.setStyle("-fx-font-family: 'Monaco', 'Menlo', 'Consolas', monospace; -fx-font-size: 11px;");
        
        SVGPath copyIcon = new SVGPath();
        String copyPath = "M5 5 H15 V15 H5 Z M2 11 V2 H11";
        copyIcon.setContent(copyPath);
        copyIcon.getStyleClass().add("mcp-copy-icon");
        Button copy = new Button();
        copy.setId("copy-mcp-example");
        copy.setGraphic(copyIcon);
        copy.getStyleClass().add("mcp-copy-button");
        copy.setAccessibleText("Copy example configuration");
        Tooltip copyTooltip = new Tooltip("Copy example configuration");
        copy.setTooltip(copyTooltip);
        copyFeedback.setOnFinished(e -> { copyIcon.setContent(copyPath); copyTooltip.setText("Copy example configuration"); });
        copy.setOnAction(e -> {
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(configText.getText());
            if (javafx.scene.input.Clipboard.getSystemClipboard().setContent(content)) {
                copyIcon.setContent("M2 8 L6 12 L14 3");
                copyTooltip.setText("Copied");
                copyFeedback.playFromStart();
            }
        });
        StackPane exampleEditor = new StackPane(configText, copy);
        StackPane.setAlignment(copy, Pos.TOP_RIGHT);
        StackPane.setMargin(copy, new Insets(8));
        copy.setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
        VBox contentBox = new VBox(8, exampleDesc, exampleEditor);
        
        // Spacer for content alignment
        Label contentSpacer = new Label();
        contentSpacer.setMinWidth(145);
        contentSpacer.setMaxWidth(145);
        
        HBox contentRow = new HBox(16, contentSpacer, contentBox);
        contentRow.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(contentBox, Priority.ALWAYS);
        
        exampleContent.getChildren().add(contentRow);
        
        // Toggle visibility on click
        exampleHeader.setOnMouseClicked(e -> {
            boolean isVisible = exampleContent.isVisible();
            exampleContent.setVisible(!isVisible);
            exampleContent.setManaged(!isVisible);
            exampleHeader.setText(isVisible ? "▶ Example Configuration" : "▼ Example Configuration");
        });
        
        section.getChildren().addAll(headerRow, exampleContent);
        return section;
    }
}
