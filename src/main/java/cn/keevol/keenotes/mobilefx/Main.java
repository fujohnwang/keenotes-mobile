package cn.keevol.keenotes.mobilefx;

import cn.keevol.keenotes.utils.SimpleForwardServer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.text.Font;
import javafx.stage.Stage;

public class Main extends Application {

    private DesktopMainView mainView;

    @Override
    public void start(Stage stage) {
        // 添加启动日志
        System.out.println("[Main] Application starting...");
        System.out.println("[Main] Java version: " + System.getProperty("java.version"));
        System.out.println("[Main] OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
        System.out.println("[Main] User home: " + System.getProperty("user.home"));
        System.out.println("[Main] JavaFX version: " + System.getProperty("javafx.version", "unknown"));

        // Load Chinese font
        loadCustomFont();

        // Create desktop main view
        System.out.println("[Main] Using DesktopMainView for desktop platform");
        mainView = new DesktopMainView();

        // Scene size for desktop
        Scene scene = new Scene(mainView, 1200, 800);

        // Load theme CSS
        loadThemeCSS(scene);

        // Listen for theme changes
        ThemeService.getInstance().currentThemeProperty().addListener((obs, oldTheme, newTheme) -> {
            Platform.runLater(() -> loadThemeCSS(scene));
        });

        stage.setTitle("KeeNotes");
        stage.setScene(scene);

        // Set minimum window size for desktop
        stage.setMinWidth(800);
        stage.setMinHeight(600);

        // Set application icon
        var iconStream = getClass().getResourceAsStream("/icons/app-icon.png");
        if (iconStream != null) {
            stage.getIcons().add(new Image(iconStream));
        }

        // 显示UI - 这是最重要的，用户应该立即看到界面
        stage.show();

        // UI显示后，延迟初始化服务（在后台线程）
        initializeServicesAfterUI();

        // kick off local import server at background @ by fq
        Thread.ofVirtual().start(new Runnable() {
            @Override
            public void run() {
                new SimpleForwardServer().start();
            }
        });
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
            ServiceManager.getInstance().getLocalCacheService();

            // 2. 添加服务状态监听器
            ServiceManager.getInstance().addListener((status, message) -> {
                System.out.println("[Service Status] " + status + ": " + message);
                updateServiceStatusUI(status, message);
            });

            // 3. 延迟连接WebSocket（在异步线程）
            Thread connectThread = new Thread(() -> {
                try {
                    Thread.sleep(500);
                    System.out.println("Attempting to connect WebSocket...");
                    ServiceManager.getInstance().connectWebSocketIfNeeded();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            connectThread.setDaemon(true);
            connectThread.start();
        });
    }

    /**
     * 更新服务状态UI - 状态现在由各组件自己管理
     * NoteInputPanel 管理 Send Channel
     * NotesDisplayPanel 管理 Sync Channel 和 Sync Indicator
     */
    private void updateServiceStatusUI(String status, String message) {
        // Status is now managed by individual components:
        // - NoteInputPanel handles Send Channel status
        // - NotesDisplayPanel handles Sync Channel and Sync Indicator
        // This method is kept for compatibility but does nothing
    }

    @Override
    public void stop() {
        System.out.println("Application stopping...");
        try {
            ServiceManager.getInstance().shutdown();
        } catch (Exception e) {
            System.err.println("Error during shutdown: " + e.getMessage());
            e.printStackTrace();
        }
        System.out.println("Application stopped.");
    }

    private void loadCustomFont() {
        String fontResourcePath = "/fonts/MiSans-Regular.ttf";

        try {
            var fontStream = getClass().getResourceAsStream(fontResourcePath);
            if (fontStream == null) {
                System.err.println("Font resource not found: " + fontResourcePath);
                return;
            }

            // Desktop: load directly from stream
            Font font = Font.loadFont(fontStream, 14);
            fontStream.close();
            if (font != null) {
                System.out.println("Loaded font from stream: " + font.getName());
                return;
            }
        } catch (Exception e) {
            System.err.println("Error loading font: " + e.getMessage());
            e.printStackTrace();
        }
        System.err.println("Failed to load custom font");
    }

    /**
     * Load theme CSS files based on current theme setting
     */
    private void loadThemeCSS(Scene scene) {
        scene.getStylesheets().clear();

        // Always load common.css first (layout styles)
        scene.getStylesheets().add(getClass().getResource("/styles/common.css").toExternalForm());

        // Load theme-specific CSS
        ThemeService.Theme theme = ThemeService.getInstance().getCurrentTheme();
        String themeFile = theme == ThemeService.Theme.LIGHT ? "light.css" : "dark.css";
        scene.getStylesheets().add(getClass().getResource("/styles/" + themeFile).toExternalForm());

        System.out.println("[Main] Loaded theme: " + theme + " (" + themeFile + ")");
    }

    public static void main(String[] args) {
        launch(args);
    }
}
