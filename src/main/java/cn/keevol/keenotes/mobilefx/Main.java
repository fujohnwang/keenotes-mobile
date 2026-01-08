package cn.keevol.keenotes.mobilefx;

import com.gluonhq.attach.lifecycle.LifecycleService;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.SwipeEvent;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.util.Duration;

public class Main extends Application {

    private StackPane contentPane;
    private BorderPane root;
    private MainViewV2 mainView;
    private DebugView debugView;
    private StatusFooter statusFooter;
    private boolean inSettingsView = false;
    private boolean inDebugView = false;

    @Override
    public void start(Stage stage) {
        // 添加启动日志
        System.out.println("[Main] Application starting...");
        System.out.println("[Main] Java version: " + System.getProperty("java.version"));
        System.out.println("[Main] OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
        System.out.println("[Main] User home: " + System.getProperty("user.home"));
        System.out.println("[Main] JavaFX version: " + System.getProperty("javafx.version", "unknown"));
        
        // Load Chinese font for Android/iOS native builds
        // This is fast and doesn't block UI
        loadCustomFont();

        root = new BorderPane();
        contentPane = new StackPane();

        // Create views - pass ServiceManager instead of directly accessing services
        // This allows lazy initialization of services
        mainView = new MainViewV2(this::showSettingsView, this::onClearSearchToNoteView);
        debugView = new DebugView(this::backFromDebug);

        // Create status footer for connection status
        statusFooter = new StatusFooter();

        // Bottom status footer (replaces tab bar)
        root.setBottom(statusFooter);
        root.setCenter(contentPane);

        // Show note pane by default
        contentPane.getChildren().setAll(mainView);
        mainView.showNotePane();

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
            Thread connectThread = new Thread(() -> {
                try {
                    // 稍微延迟，让用户先看到UI
                    Thread.sleep(500);
                    System.out.println("Attempting to connect WebSocket...");
                    ServiceManager.getInstance().connectWebSocketIfNeeded();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            connectThread.setDaemon(true);  // 设置为daemon线程
            connectThread.start();
        });
    }

    /**
     * 更新服务状态UI - 使用StatusFooter显示双通道状态
     */
    private void updateServiceStatusUI(String status, String message) {
        Platform.runLater(() -> {
            if (statusFooter != null) {
                // Update Send Channel status based on configuration
                SettingsService settings = SettingsService.getInstance();
                if (settings.getEndpointUrl().isEmpty() || settings.getToken().isEmpty()) {
                    statusFooter.setSendChannelState(StatusFooter.SendChannelState.NOT_CONFIGURED);
                } else {
                    // Assume network is available (JavaFX doesn't have easy network detection)
                    statusFooter.setSendChannelState(StatusFooter.SendChannelState.READY);
                }
                
                // Update Sync Channel status
                switch (status.toLowerCase()) {
                    case "websocket_connected":
                        statusFooter.setSyncChannelState(StatusFooter.ConnectionState.CONNECTED);
                        break;
                    case "websocket_disconnected":
                    case "not_configured":
                    case "connect_error":
                    case "websocket_error":
                        statusFooter.setSyncChannelState(StatusFooter.ConnectionState.DISCONNECTED);
                        break;
                    case "reinitializing":
                        statusFooter.setSyncChannelState(StatusFooter.ConnectionState.CONNECTING);
                        break;
                    case "syncing":
                        statusFooter.setSyncStatusMessage("Syncing...", "#FFC107");
                        break;
                    case "sync_complete":
                        // Restore connected state after sync
                        statusFooter.setSyncChannelState(StatusFooter.ConnectionState.CONNECTED);
                        break;
                    default:
                        // For other status messages, keep current state
                        break;
                }
            }
        });
    }


    private void showSettingsView() {
        inSettingsView = true;
        SettingsView settingsView = new SettingsView(this::backFromSettings, this::showDebugView);
        animateViewTransition(settingsView, true);
    }

    private void showDebugView() {
        inDebugView = true;
        inSettingsView = false;
        animateViewTransition(debugView, true);
    }

    private void backFromDebug() {
        inDebugView = false;
        animateViewTransition(mainView, false);
        mainView.showNotePane();
    }

    private void backFromSettings() {
        inSettingsView = false;
        animateViewTransition(mainView, false);
        mainView.showNotePane();
    }

    /**
     * Animate view transition with fade and slide effect
     * @param newView the view to show
     * @param slideFromRight true for forward navigation (slide from right), false for back (slide from left)
     */
    private void animateViewTransition(Node newView, boolean slideFromRight) {
        Node oldView = contentPane.getChildren().isEmpty() ? null : contentPane.getChildren().get(0);
        
        // Add new view
        if (!contentPane.getChildren().contains(newView)) {
            contentPane.getChildren().add(newView);
        }
        newView.toFront();
        
        // Setup initial state for new view
        double startX = slideFromRight ? 100 : -100;
        newView.setTranslateX(startX);
        newView.setOpacity(0);
        
        // Animate new view in
        FadeTransition fadeIn = new FadeTransition(Duration.millis(250), newView);
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);
        
        TranslateTransition slideIn = new TranslateTransition(Duration.millis(250), newView);
        slideIn.setFromX(startX);
        slideIn.setToX(0);
        
        ParallelTransition inTransition = new ParallelTransition(fadeIn, slideIn);
        
        // Animate old view out (if exists)
        if (oldView != null && oldView != newView) {
            double endX = slideFromRight ? -50 : 50;
            
            FadeTransition fadeOut = new FadeTransition(Duration.millis(200), oldView);
            fadeOut.setFromValue(1);
            fadeOut.setToValue(0);
            
            TranslateTransition slideOut = new TranslateTransition(Duration.millis(200), oldView);
            slideOut.setFromX(0);
            slideOut.setToX(endX);
            
            ParallelTransition outTransition = new ParallelTransition(fadeOut, slideOut);
            
            // Remove old view after animation
            Node finalOldView = oldView;
            outTransition.setOnFinished(e -> {
                contentPane.getChildren().remove(finalOldView);
                // Reset old view state
                finalOldView.setTranslateX(0);
                finalOldView.setOpacity(1);
            });
            
            outTransition.play();
        }
        
        inTransition.play();
    }

    /**
     * Called when MainViewV2 clears search and returns to note view
     * No longer needs to update tab buttons since they're removed
     */
    private void onClearSearchToNoteView() {
        // No-op now that tab bar is removed
        // MainViewV2 handles its own state
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
            // 不需要等待，shutdown方法会立即释放所有资源
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
