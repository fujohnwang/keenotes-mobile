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
    private final VBox searchPane;
    private final TextArea noteInput;
    private final Button submitBtn;
    private final Label statusLabel;
    private final VBox echoContainer;
    private final VBox reviewResultsContainer;
    private final VBox searchResultsContainer;

    private final ApiServiceV2 apiService;
    private final Runnable onOpenSettings;
    private final Runnable onClearSearchToNoteView;

    // Header components
    private HBox header;
    private TextField searchField;
    private Button clearSearchBtn;
    private Button settingsBtn;
    private PauseTransition searchDebounce;

    // Lazy-loaded services
    private LocalCacheService localCache;

    public MainViewV2(Runnable onOpenSettings, Runnable onClearSearchToNoteView) {
        this.onOpenSettings = onOpenSettings;
        this.onClearSearchToNoteView = onClearSearchToNoteView;
        // 使用ServiceManager获取ApiService，而不是直接创建
        this.apiService = ServiceManager.getInstance().getApiService();
        getStyleClass().add("main-view");

        // Initialize components
        reviewResultsContainer = new VBox(12);
        searchResultsContainer = new VBox(12);
        contentPane = new StackPane();
        noteInput = new TextArea();
        submitBtn = new Button("Save Note");
        statusLabel = new Label();
        echoContainer = new VBox(8);

        // Initialize search debounce
        searchDebounce = new PauseTransition(Duration.millis(500));
        searchDebounce.setOnFinished(e -> performSearch());

        // Header with search/settings
        createHeader();
        setTop(header);

        // Create panes
        notePane = createNotePane();
        reviewPane = createReviewPane();
        searchPane = createSearchPane();

        // Stack all panes
        contentPane.getChildren().addAll(searchPane, reviewPane, notePane);
        setCenter(contentPane);

        // Show note pane by default
        showNotePane();

        // Set initial focus
        Platform.runLater(() -> noteInput.requestFocus());

        // 延迟检查是否需要初始化缓存（在UI显示后）
        // 只有当配置存在时才初始化，避免不必要的数据库操作
        checkAndInitializeCacheAsync();
    }

    private void createHeader() {
        // Settings button (right)
        javafx.scene.shape.SVGPath gearIcon = new javafx.scene.shape.SVGPath();
        gearIcon.setContent("M12 15.5A3.5 3.5 0 0 1 8.5 12 3.5 3.5 0 0 1 12 8.5a3.5 3.5 0 0 1 3.5 3.5 3.5 3.5 0 0 1-3.5 3.5m7.43-2.53c.04-.32.07-.64.07-.97 0-.33-.03-.66-.07-1l2.11-1.63c.19-.15.24-.42.12-.64l-2-3.46c-.12-.22-.39-.31-.61-.22l-2.49 1c-.52-.39-1.06-.73-1.69-.98l-.37-2.65A.506.506 0 0 0 14 2h-4c-.25 0-.46.18-.5.42l-.37 2.65c-.63.25-1.17.59-1.69.98l-2.49-1c-.22-.09-.49 0-.61.22l-2 3.46c-.13.22-.07.49.12.64L4.57 11c-.04.34-.07.67-.07 1 0 .33.03.65.07.97l-2.11 1.66c-.19.15-.25.42-.12.64l2 3.46c.12.22.39.3.61.22l2.49-1.01c.52.4 1.06.74 1.69.99l.37 2.65c.04.24.25.42.5.42h4c.25 0 .46-.18.5-.42l.37-2.65c.63-.26 1.17-.59 1.69-.99l2.49 1.01c.22.08.49 0 .61-.22l2-3.46c.12-.22.07-.49-.12-.64l-2.11-1.66z");
        gearIcon.setFill(javafx.scene.paint.Color.web("#8B949E"));
        gearIcon.setScaleX(1.2);
        gearIcon.setScaleY(1.2);

        settingsBtn = new Button();
        settingsBtn.setGraphic(gearIcon);
        settingsBtn.getStyleClass().add("icon-button");
        settingsBtn.setOnAction(e -> onOpenSettings.run());

        settingsBtn.setOnMouseEntered(e -> gearIcon.setFill(javafx.scene.paint.Color.web("#00D4FF")));
        settingsBtn.setOnMouseExited(e -> gearIcon.setFill(javafx.scene.paint.Color.web("#8B949E")));

        // Search field - always visible in header
        searchField = new TextField();
        searchField.setPromptText("Search notes...");
        searchField.getStyleClass().add("search-field");
        searchField.setVisible(true);
        searchField.setManaged(true);

        clearSearchBtn = new Button("✕");
        clearSearchBtn.getStyleClass().add("clear-search-btn");
        clearSearchBtn.setVisible(false);
        clearSearchBtn.setManaged(false);
        clearSearchBtn.setOnAction(e -> {
            searchField.clear();
            searchResultsContainer.getChildren().clear();
        });

        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            boolean hasText = newVal != null && !newVal.trim().isEmpty();
            clearSearchBtn.setVisible(hasText);
            clearSearchBtn.setManaged(hasText);

            if (!hasText) {
                if (searchDebounce != null) {
                    searchDebounce.stop();
                }
                searchResultsContainer.getChildren().clear();
                showNotePane();
                // Notify Main to update tab button state
                if (onClearSearchToNoteView != null) {
                    onClearSearchToNoteView.run();
                }
            } else {
                showSearchPane();
                if (searchDebounce != null) {
                    searchDebounce.stop();  // Stop any existing
                    searchDebounce.playFromStart();  // Restart
                }
            }
        });

        searchField.setOnAction(e -> {
            searchDebounce.stop();
            performSearch();
        });

        // Build header - always with search field
        rebuildHeader();
    }

    private void rebuildHeader() {
        // Clear existing children
        if (header == null) {
            header = new HBox(8);
            header.getStyleClass().add("header");
            header.setAlignment(Pos.CENTER_LEFT);
            header.setPadding(new Insets(8, 12, 8, 12));
            setTop(header);
        }

        header.getChildren().clear();

        // Always show: Search field + Clear button + Settings
        HBox searchBox = new HBox(4, searchField, clearSearchBtn);
        searchBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(searchBox, Priority.ALWAYS);
        header.getChildren().addAll(searchBox, settingsBtn);
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
        // Period selection dropdown - vertically centered
        HBox periodControls = new HBox(8);
        periodControls.setPadding(new Insets(8, 16, 8, 16));
        periodControls.setAlignment(Pos.CENTER_LEFT);

        ComboBox<String> periodSelect = new ComboBox<>();
        periodSelect.getItems().addAll("7 days", "30 days", "90 days", "All");
        periodSelect.setValue("7 days");
        periodSelect.getStyleClass().add("review-period-select");
        periodSelect.setOnAction(e -> loadReviewNotes(periodSelect.getValue()));

        // Add label for context - vertically centered
        Label periodLabel = new Label("Review Period:");
        periodLabel.getStyleClass().add("period-label");

        // Wrap in VBox for vertical centering
        VBox labelBox = new VBox(periodLabel);
        labelBox.setAlignment(Pos.CENTER);

        periodControls.getChildren().addAll(labelBox, periodSelect);

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

    private VBox createSearchPane() {
        searchResultsContainer.setPadding(new Insets(8, 16, 16, 16));
        searchResultsContainer.getStyleClass().add("search-results");

        ScrollPane scrollPane = new ScrollPane(searchResultsContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("content-scroll");

        VBox pane = new VBox(scrollPane);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        pane.getStyleClass().add("search-pane");
        return pane;
    }

    private void performSearch() {
        String query = searchField.getText().trim();
        if (query.isEmpty()) {
            searchResultsContainer.getChildren().clear();
            return;
        }

        searchResultsContainer.getChildren().clear();
        Label loadingLabel = new Label("Searching locally...");
        loadingLabel.getStyleClass().add("search-loading");
        searchResultsContainer.getChildren().add(loadingLabel);

        // 在后台线程执行搜索
        new Thread(() -> {
            try {
                // 增加调试信息
                ServiceManager serviceManager = ServiceManager.getInstance();
                SettingsService settings = SettingsService.getInstance();
                
                System.out.println("[DEBUG search] Query: " + query);
                System.out.println("[DEBUG search] ServiceManager: " + (serviceManager != null ? "OK" : "NULL"));
                System.out.println("[DEBUG search] Settings configured: " + settings.isConfigured());
                System.out.println("[DEBUG search] LocalCache state: " + serviceManager.getLocalCacheState());
                
                // 检查配置
                if (!settings.isConfigured()) {
                    Platform.runLater(() -> {
                        searchResultsContainer.getChildren().clear();
                        Label notConfigured = new Label("Please configure server settings first in Settings.");
                        notConfigured.getStyleClass().add("no-results");
                        searchResultsContainer.getChildren().add(notConfigured);
                    });
                    return;
                }
                
                // 检查初始化状态
                ServiceManager.InitializationState state = serviceManager.getLocalCacheState();
                
                if (state == ServiceManager.InitializationState.READY) {
                    // 缓存就绪，执行搜索
                    LocalCacheService cache = serviceManager.getLocalCacheService();
                    List<LocalCacheService.NoteData> results = cache.searchNotes(query);
                    
                    Platform.runLater(() -> {
                        searchResultsContainer.getChildren().clear();
                        
                        System.out.println("[DEBUG search] results.size=" + results.size());
                        
                        if (results.isEmpty()) {
                            Label noResults = new Label("No results found for \"" + query + "\"");
                            noResults.getStyleClass().add("no-results");
                            searchResultsContainer.getChildren().add(noResults);
                        } else {
                            Label countLabel = new Label(results.size() + " result(s) found");
                            countLabel.getStyleClass().add("search-count");
                            searchResultsContainer.getChildren().add(countLabel);
                            
                            for (LocalCacheService.NoteData result : results) {
                                VBox card = createResultCard(result);
                                searchResultsContainer.getChildren().add(card);
                            }
                        }
                    });
                    
                } else if (state == ServiceManager.InitializationState.ERROR) {
                    // 初始化失败，显示错误信息和重试按钮
                    String errorMsg = serviceManager.getLocalCacheErrorMessage();
                    Platform.runLater(() -> {
                        searchResultsContainer.getChildren().clear();
                        
                        VBox errorBox = new VBox(12);
                        errorBox.setPadding(new Insets(16));
                        errorBox.setAlignment(Pos.CENTER);
                        
                        Label errorTitle = new Label("Cache Initialization Failed");
                        errorTitle.getStyleClass().add("error-title");
                        
                        Label errorDetail = new Label(errorMsg != null ? errorMsg : "Unknown error");
                        errorDetail.getStyleClass().add("error-detail");
                        errorDetail.setWrapText(true);
                        
                        Button retryBtn = new Button("Retry");
                        retryBtn.getStyleClass().addAll("action-button", "primary");
                        retryBtn.setOnAction(e -> {
                            serviceManager.retryLocalCacheInitialization();
                            // 延迟后重新搜索
                            new Thread(() -> {
                                try {
                                    Thread.sleep(1000);
                                    Platform.runLater(() -> performSearch());
                                } catch (InterruptedException ex) {
                                    Thread.currentThread().interrupt();
                                }
                            }).start();
                        });
                        
                        errorBox.getChildren().addAll(errorTitle, errorDetail, retryBtn);
                        searchResultsContainer.getChildren().add(errorBox);
                    });
                    
                } else if (state == ServiceManager.InitializationState.INITIALIZING) {
                    // 正在初始化
                    Platform.runLater(() -> {
                        searchResultsContainer.getChildren().clear();
                        Label initializingLabel = new Label("Cache is initializing. Please wait...");
                        initializingLabel.getStyleClass().add("search-loading");
                        searchResultsContainer.getChildren().add(initializingLabel);
                        
                        // 延迟重试，但限制重试次数
                        new Thread(() -> {
                            try {
                                Thread.sleep(3000); // 增加等待时间到3秒
                                // 检查状态是否仍然是初始化中，避免无限循环
                                ServiceManager.InitializationState currentState = serviceManager.getLocalCacheState();
                                if (currentState == ServiceManager.InitializationState.INITIALIZING) {
                                    // 如果仍在初始化，再等待一次
                                    Thread.sleep(2000);
                                    currentState = serviceManager.getLocalCacheState();
                                }
                                
                                // 只有在状态改变时才重试
                                if (currentState != ServiceManager.InitializationState.INITIALIZING) {
                                    Platform.runLater(() -> performSearch());
                                } else {
                                    // 初始化超时，显示错误
                                    Platform.runLater(() -> {
                                        searchResultsContainer.getChildren().clear();
                                        Label errorLabel = new Label("Cache initialization timeout. Please try again.");
                                        errorLabel.getStyleClass().add("error-message");
                                        searchResultsContainer.getChildren().add(errorLabel);
                                    });
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }).start();
                    });
                    
                } else {
                    // NOT_STARTED - 触发初始化
                    Platform.runLater(() -> {
                        searchResultsContainer.getChildren().clear();
                        Label initLabel = new Label("Initializing cache...");
                        initLabel.getStyleClass().add("search-loading");
                        searchResultsContainer.getChildren().add(initLabel);
                        
                        // 触发初始化
                        serviceManager.getLocalCacheService();
                        
                        // 延迟重试
                        new Thread(() -> {
                            try {
                                Thread.sleep(2000);
                                Platform.runLater(() -> performSearch());
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }).start();
                    });
                }
                
            } catch (Exception e) {
                System.err.println("[ERROR search] Exception: " + e.getMessage());
                e.printStackTrace();
                Platform.runLater(() -> {
                    searchResultsContainer.getChildren().clear();
                    Label errorLabel = new Label("Error searching notes: " + e.getMessage());
                    errorLabel.getStyleClass().add("no-results");
                    searchResultsContainer.getChildren().add(errorLabel);
                });
            }
        }).start();
    }

    private void showSearchPane() {
        notePane.setVisible(false);
        reviewPane.setVisible(false);
        searchPane.setVisible(true);
        searchPane.toFront();
    }

    public void showNotePane() {
        searchPane.setVisible(false);
        reviewPane.setVisible(false);
        notePane.setVisible(true);
        notePane.toFront();
        Platform.runLater(() -> noteInput.requestFocus());
    }

    public void showReviewPane() {
        searchPane.setVisible(false);
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

        // 在后台线程执行回顾加载
        new Thread(() -> {
            try {
                // 增加调试信息
                ServiceManager serviceManager = ServiceManager.getInstance();
                SettingsService settings = SettingsService.getInstance();
                
                System.out.println("[DEBUG] ServiceManager: " + (serviceManager != null ? "OK" : "NULL"));
                System.out.println("[DEBUG] Settings configured: " + settings.isConfigured());
                System.out.println("[DEBUG] LocalCache state: " + serviceManager.getLocalCacheState());
                
                // 检查配置
                if (!settings.isConfigured()) {
                    Platform.runLater(() -> {
                        reviewResultsContainer.getChildren().clear();
                        Label notConfigured = new Label("Please configure server settings first in Settings.");
                        notConfigured.getStyleClass().add("no-results");
                        reviewResultsContainer.getChildren().add(notConfigured);
                    });
                    return;
                }
                
                // 检查初始化状态
                ServiceManager.InitializationState state = serviceManager.getLocalCacheState();
                
                if (state == ServiceManager.InitializationState.READY) {
                    // 缓存就绪，执行查询
                    LocalCacheService cache = serviceManager.getLocalCacheService();
                    List<LocalCacheService.NoteData> results = cache.getNotesForReview(days);
                    
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
                    
                } else if (state == ServiceManager.InitializationState.ERROR) {
                    // 初始化失败，显示错误信息和重试按钮
                    String errorMsg = serviceManager.getLocalCacheErrorMessage();
                    Platform.runLater(() -> {
                        reviewResultsContainer.getChildren().clear();
                        
                        VBox errorBox = new VBox(12);
                        errorBox.setPadding(new Insets(16));
                        errorBox.setAlignment(Pos.CENTER);
                        
                        Label errorTitle = new Label("Cache Initialization Failed");
                        errorTitle.getStyleClass().add("error-title");
                        
                        Label errorDetail = new Label(errorMsg != null ? errorMsg : "Unknown error");
                        errorDetail.getStyleClass().add("error-detail");
                        errorDetail.setWrapText(true);
                        
                        Button retryBtn = new Button("Retry");
                        retryBtn.getStyleClass().addAll("action-button", "primary");
                        retryBtn.setOnAction(e -> {
                            serviceManager.retryLocalCacheInitialization();
                            // 延迟后重新加载
                            new Thread(() -> {
                                try {
                                    Thread.sleep(1000);
                                    Platform.runLater(() -> loadReviewNotes(period));
                                } catch (InterruptedException ex) {
                                    Thread.currentThread().interrupt();
                                }
                            }).start();
                        });
                        
                        errorBox.getChildren().addAll(errorTitle, errorDetail, retryBtn);
                        reviewResultsContainer.getChildren().add(errorBox);
                    });
                    
                } else if (state == ServiceManager.InitializationState.INITIALIZING) {
                    // 正在初始化，显示加载状态
                    Platform.runLater(() -> {
                        reviewResultsContainer.getChildren().clear();
                        Label initializingLabel = new Label("Cache is initializing. Please wait...");
                        initializingLabel.getStyleClass().add("search-loading");
                        reviewResultsContainer.getChildren().add(initializingLabel);
                        
                        // 延迟重试，但限制重试次数
                        new Thread(() -> {
                            try {
                                Thread.sleep(3000); // 增加等待时间到3秒
                                // 检查状态是否仍然是初始化中，避免无限循环
                                ServiceManager.InitializationState currentState = serviceManager.getLocalCacheState();
                                if (currentState == ServiceManager.InitializationState.INITIALIZING) {
                                    // 如果仍在初始化，再等待一次
                                    Thread.sleep(2000);
                                    currentState = serviceManager.getLocalCacheState();
                                }
                                
                                // 只有在状态改变时才重试
                                if (currentState != ServiceManager.InitializationState.INITIALIZING) {
                                    Platform.runLater(() -> loadReviewNotes(period));
                                } else {
                                    // 初始化超时，显示诊断信息
                                    String gluonPlatform = "unknown";
                                    try {
                                        gluonPlatform = com.gluonhq.attach.util.Platform.getCurrent().name();
                                    } catch (Exception ex) {
                                        gluonPlatform = "error: " + ex.getMessage();
                                    }
                                    String diagInfo = "State: INITIALIZING (stuck)\n" +
                                                     "Gluon Platform: " + gluonPlatform + "\n" +
                                                     "os.name: " + System.getProperty("os.name", "unknown") + "\n" +
                                                     "os.arch: " + System.getProperty("os.arch", "unknown");
                                    String errorMsg = serviceManager.getLocalCacheErrorMessage();
                                    if (errorMsg != null) {
                                        diagInfo += "\nError: " + errorMsg;
                                    }
                                    final String finalDiag = diagInfo;
                                    
                                    Platform.runLater(() -> {
                                        reviewResultsContainer.getChildren().clear();
                                        
                                        VBox errorBox = new VBox(12);
                                        errorBox.setPadding(new Insets(16));
                                        errorBox.setAlignment(Pos.CENTER);
                                        
                                        Label errorTitle = new Label("Cache Initialization Timeout");
                                        errorTitle.getStyleClass().add("error-title");
                                        
                                        Label errorDetail = new Label(finalDiag);
                                        errorDetail.getStyleClass().add("error-detail");
                                        errorDetail.setWrapText(true);
                                        
                                        Button retryBtn = new Button("Retry");
                                        retryBtn.getStyleClass().addAll("action-button", "primary");
                                        retryBtn.setOnAction(e -> {
                                            serviceManager.retryLocalCacheInitialization();
                                            new Thread(() -> {
                                                try {
                                                    Thread.sleep(1000);
                                                    Platform.runLater(() -> loadReviewNotes(period));
                                                } catch (InterruptedException ex) {
                                                    Thread.currentThread().interrupt();
                                                }
                                            }).start();
                                        });
                                        
                                        errorBox.getChildren().addAll(errorTitle, errorDetail, retryBtn);
                                        reviewResultsContainer.getChildren().add(errorBox);
                                    });
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }).start();
                    });
                    
                } else {
                    // NOT_STARTED - 触发初始化
                    Platform.runLater(() -> {
                        reviewResultsContainer.getChildren().clear();
                        Label initLabel = new Label("Initializing cache...");
                        initLabel.getStyleClass().add("search-loading");
                        reviewResultsContainer.getChildren().add(initLabel);
                        
                        // 触发初始化
                        serviceManager.getLocalCacheService();
                        
                        // 延迟重试
                        new Thread(() -> {
                            try {
                                Thread.sleep(2000);
                                Platform.runLater(() -> loadReviewNotes(period));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }).start();
                    });
                }
                
            } catch (Exception e) {
                System.err.println("[ERROR loadReviewNotes] Exception: " + e.getMessage());
                e.printStackTrace();
                Platform.runLater(() -> {
                    reviewResultsContainer.getChildren().clear();
                    Label errorLabel = new Label("Error loading notes: " + e.getMessage());
                    errorLabel.getStyleClass().add("no-results");
                    reviewResultsContainer.getChildren().add(errorLabel);
                });
            }
        }).start();
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
     * 切换到记录/笔记面板
     */
    public void showRecordTab() {
        showNotePane();
    }

    /**
     * 获取本地笔记统计信息
     */
    public String getLocalStats() {
        LocalCacheService cache = getLocalCacheService();
        if (cache == null || !cache.isInitialized()) {
            return "Local cache: initializing...";
        }
        int count = cache.getLocalNoteCount();
        String lastSync = cache.getLastSyncTime();
        return String.format("Local notes: %d\nLast sync: %s", count, lastSync != null ? lastSync : "Never");
    }

    /**
     * 检查是否需要初始化缓存
     * 只有当配置存在时才初始化，避免不必要的数据库操作
     */
    private void checkAndInitializeCacheAsync() {
        new Thread(() -> {
            try {
                // 稍微延迟，确保UI已经渲染完成
                Thread.sleep(100);

                // 检查配置是否存在
                SettingsService settings = SettingsService.getInstance();
                if (!settings.isConfigured()) {
                    System.out.println("[MainViewV2] No configuration found, skipping cache initialization");
                    return;
                }

                // 配置存在，初始化缓存
                localCache = ServiceManager.getInstance().getLocalCacheService();
                System.out.println("[MainViewV2] Configuration found, cache initialization triggered");
            } catch (Exception e) {
                System.err.println("[MainViewV2] Failed to check/initialize cache: " + e.getMessage());
            }
        }).start();
    }

    /**
     * 获取LocalCacheService实例
     * 如果尚未初始化，会触发初始化但不等待
     */
    private LocalCacheService getLocalCacheService() {
        if (localCache == null) {
            localCache = ServiceManager.getInstance().getLocalCacheService();
        }
        return localCache;
    }

    /**
     * 检查本地缓存是否已就绪
     */
    private boolean isLocalCacheReady() {
        LocalCacheService cache = getLocalCacheService();
        return cache != null && cache.isInitialized();
    }

    /**
     * 当设置保存后调用此方法
     * 如果配置已完成但缓存未初始化，则触发初始化
     */
    public void onSettingsSaved() {
        SettingsService settings = SettingsService.getInstance();
        if (settings.isConfigured() && !isLocalCacheReady()) {
            System.out.println("[MainViewV2] Settings saved, triggering cache initialization");
            // 触发缓存初始化
            new Thread(() -> {
                try {
                    Thread.sleep(100); // 短暂延迟
                    localCache = ServiceManager.getInstance().getLocalCacheService();
                } catch (Exception e) {
                    System.err.println("[MainViewV2] Failed to initialize cache after settings saved: " + e.getMessage());
                }
            }).start();
        }
    }
}
