package cn.keevol.keenotes.mobilefx;

import com.gluonhq.attach.lifecycle.LifecycleService;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.SwipeEvent;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.stage.Stage;

public class Main extends Application {

    private StackPane contentPane;
    private BorderPane root;
    private MainViewV2 mainView;
    private DebugView debugView;
    private Button recordTabBtn;
    private Button reviewTabBtn;
    private boolean inSettingsView = false;
    private boolean inDebugView = false;

    @Override
    public void start(Stage stage) {
        // Load Chinese font for Android/iOS native builds
        // This is fast and doesn't block UI
        loadCustomFont();

        root = new BorderPane();
        contentPane = new StackPane();

        // Create views - pass ServiceManager instead of directly accessing services
        // This allows lazy initialization of services
        mainView = new MainViewV2(this::showSettingsView, this::onClearSearchToNoteView);
        debugView = new DebugView(this::backFromDebug);

        // Bottom tab bar
        root.setBottom(createBottomTabBar());
        root.setCenter(contentPane);

        // Show record tab by default
        showRecordTab();

        Scene scene = new Scene(root, 375, 667);
        scene.getStylesheets().add(getClass().getResource("/styles/main.css").toExternalForm());

        // Handle Android back button (mapped to ESCAPE in JavaFX)
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                if (handleBackNavigation()) {
                    event.consume();
                }
            }
        });

        // Handle iOS swipe right gesture for back navigation
        scene.addEventFilter(SwipeEvent.SWIPE_RIGHT, event -> {
            if (handleBackNavigation()) {
                event.consume();
            }
        });

        stage.setTitle("KeeNotes");
        stage.setScene(scene);

        if (com.gluonhq.attach.util.Platform.isDesktop()) {
            var iconStream = getClass().getResourceAsStream("/icons/app-icon.png");
            if (iconStream != null) {
                stage.getIcons().add(new Image(iconStream));
            }
        }

        // 显示UI - 这是最重要的，用户应该立即看到界面
        stage.show();

        // UI显示后，延迟初始化服务（在后台线程）
        // 这样即使网络不通或配置未完成，UI也能正常显示
        initializeServicesAfterUI();

        LifecycleService.create().ifPresent(service ->
                System.out.println("LifecycleService initialized"));
    }

    /**
     * 在UI显示后初始化服务
     * 所有耗时操作（数据库初始化、网络连接）都在后台执行
     */
    private void initializeServicesAfterUI() {
        // 使用Platform.runLater确保UI已经完全渲染
        Platform.runLater(() -> {
            System.out.println("Initializing services after UI is ready...");

            // 1. 初始化LocalCacheService（数据库初始化）
            // 这个操作可能稍慢，但在后台执行，不影响UI
            ServiceManager.getInstance().getLocalCacheService();

            // 2. 添加服务状态监听器
            ServiceManager.getInstance().addListener((status, message) -> {
                System.out.println("[Service Status] " + status + ": " + message);
                // 可以在这里更新UI显示服务状态
                updateServiceStatusUI(status, message);
            });

            // 3. 延迟连接WebSocket（在异步线程）
            // 给用户几秒钟时间看到UI，然后再尝试连接
            // 如果网络不通，用户仍然可以使用设置界面配置endpoint
            new Thread(() -> {
                try {
                    // 稍微延迟，让用户先看到UI
                    Thread.sleep(500);
                    System.out.println("Attempting to connect WebSocket...");
                    ServiceManager.getInstance().connectWebSocketIfNeeded();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        });
    }

    /**
     * 更新服务状态UI（可选的，可以在状态栏显示）
     */
    private void updateServiceStatusUI(String status, String message) {
        // 可以在这里添加状态栏显示
        // 例如：在底部显示连接状态图标或文字
        // 目前只打印日志，用户可以通过设置界面查看状态
    }


    private HBox createBottomTabBar() {
        recordTabBtn = new Button("记录/Take");
        recordTabBtn.getStyleClass().addAll("tab-button", "active");
        recordTabBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(recordTabBtn, Priority.ALWAYS);
        recordTabBtn.setOnAction(e -> showRecordTab());

        reviewTabBtn = new Button("回顾/Review");
        reviewTabBtn.getStyleClass().add("tab-button");
        reviewTabBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(reviewTabBtn, Priority.ALWAYS);
        reviewTabBtn.setOnAction(e -> showReviewTab());

        HBox tabBar = new HBox(recordTabBtn, reviewTabBtn);
        tabBar.getStyleClass().add("bottom-tab-bar");
        tabBar.setAlignment(Pos.CENTER);
        tabBar.setPadding(new Insets(0));
        return tabBar;
    }

    private void showRecordTab() {
        // Ensure MainViewV2 is showing
        if (!contentPane.getChildren().contains(mainView)) {
            contentPane.getChildren().setAll(mainView);
        }
        // Tell MainViewV2 to show note pane
        mainView.showRecordTab();
        reviewTabBtn.getStyleClass().remove("active");
        if (!recordTabBtn.getStyleClass().contains("active")) {
            recordTabBtn.getStyleClass().add("active");
        }
    }

    private void showReviewTab() {
        // Ensure MainViewV2 is showing
        if (!contentPane.getChildren().contains(mainView)) {
            contentPane.getChildren().setAll(mainView);
        }
        // Tell MainViewV2 to show review pane
        mainView.showReviewPane();
        recordTabBtn.getStyleClass().remove("active");
        if (!reviewTabBtn.getStyleClass().contains("active")) {
            reviewTabBtn.getStyleClass().add("active");
        }
    }

    private void showSettingsView() {
        inSettingsView = true;
        SettingsView settingsView = new SettingsView(this::backFromSettings, this::showDebugView);
        contentPane.getChildren().setAll(settingsView);
    }

    private void showDebugView() {
        inDebugView = true;
        inSettingsView = false;
        contentPane.getChildren().setAll(debugView);
    }

    private void backFromDebug() {
        inDebugView = false;
        contentPane.getChildren().setAll(mainView);
        mainView.showNotePane();
    }

    private void backFromSettings() {
        inSettingsView = false;
        // Ensure MainViewV2 is showing
        contentPane.getChildren().setAll(mainView);
        if (reviewTabBtn.getStyleClass().contains("active")) {
            mainView.showReviewPane();
        } else {
            mainView.showRecordTab();
        }
    }

    /**
     * Called when MainViewV2 clears search and returns to note view
     * Updates tab button state to match
     */
    private void onClearSearchToNoteView() {
        reviewTabBtn.getStyleClass().remove("active");
        if (!recordTabBtn.getStyleClass().contains("active")) {
            recordTabBtn.getStyleClass().add("active");
        }
    }

    /**
     * Handle back navigation for both Android back button and iOS swipe gesture.
     * @return true if navigation was handled, false if should exit app
     */
    private boolean handleBackNavigation() {
        // Priority 1: Debug view
        if (inDebugView) {
            backFromDebug();
            return true;
        }
        // Priority 2: Settings view
        if (inSettingsView) {
            backFromSettings();
            return true;
        }
        // Priority 3: If search field has text, clear it
        // Note: search field is always visible now, so we check if it has text
        // This requires MainView to expose a method to check/clear search
        // For now, skip this priority since search is always visible

        // Not handled - let system handle (exit app)
        return false;
    }

    @Override
    public void stop() {
        System.out.println("Application stopping...");
        try {
            // 使用ServiceManager统一管理服务的关闭
            ServiceManager.getInstance().shutdown();
            // 给一些时间让线程优雅关闭
            Thread.sleep(1000);
        } catch (Exception e) {
            System.err.println("Error during shutdown: " + e.getMessage());
            e.printStackTrace();
        }
        System.out.println("Application stopped.");
    }

    private void loadCustomFont() {
        // On Android, Font.loadFont() from InputStream doesn't work properly
        // Need to copy font to local file system first, then load from file URI
        String fontResourcePath = "/fonts/MiSans-Regular.ttf";
        
        try {
            var fontStream = getClass().getResourceAsStream(fontResourcePath);
            if (fontStream == null) {
                System.err.println("Font resource not found: " + fontResourcePath);
                return;
            }

            // For Android/iOS: copy to temp file and load from file URI
            if (com.gluonhq.attach.util.Platform.isAndroid() || com.gluonhq.attach.util.Platform.isIOS()) {
                java.io.File tempDir = new java.io.File(System.getProperty("java.io.tmpdir", "/tmp"));
                java.io.File fontFile = new java.io.File(tempDir, "MiSans-Regular.ttf");
                
                // Copy font to temp file
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(fontFile)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = fontStream.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                    }
                }
                fontStream.close();
                
                // Load from file URI (this works on Android)
                Font font = Font.loadFont(fontFile.toURI().toString(), 14);
                if (font != null) {
                    System.out.println("Loaded font from file: " + font.getName());
                    return;
                } else {
                    System.err.println("Font.loadFont returned null for file: " + fontFile.getAbsolutePath());
                }
            } else {
                // Desktop: load directly from stream
                Font font = Font.loadFont(fontStream, 14);
                fontStream.close();
                if (font != null) {
                    System.out.println("Loaded font from stream: " + font.getName());
                    return;
                }
            }
        } catch (Exception e) {
            System.err.println("Error loading font: " + e.getMessage());
            e.printStackTrace();
        }
        System.err.println("Failed to load custom font");
    }

    public static void main(String[] args) {
        launch(args);
    }
}
