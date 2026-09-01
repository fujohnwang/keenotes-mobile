package cn.keevol.keenotes.mobilefx;

import cn.keevol.keenotes.utils.SimpleForwardServer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.scene.Scene;
import javafx.geometry.Rectangle2D;
import javafx.scene.image.Image;
import javafx.scene.text.Font;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.util.logging.Level;
import java.util.logging.Logger;

public class Main extends Application {

    private static final Logger logger = AppLogger.getLogger(Main.class);

    private DesktopMainView mainView;
    private FxRuntimeMonitor runtimeMonitor;
    private ChangeListener<ThemeService.Theme> themeListener;

    @Override
    public void start(Stage stage) {
        AppLogger.installGlobalUncaughtExceptionHandler();
        AppLogger.installCurrentThreadUncaughtExceptionHandler();
        logger.info("Application starting; Java=" + System.getProperty("java.version")
                + ", JavaFX=" + System.getProperty("javafx.version", "unknown")
                + ", OS=" + System.getProperty("os.name") + " " + System.getProperty("os.version"));

        // Load Chinese font
        loadCustomFont();

        // Create desktop main view
        System.out.println("[Main] Using DesktopMainView for desktop platform");
        mainView = new DesktopMainView();

        // Scene size for desktop - adapt to screen size to avoid titlebar being pushed off-screen
        Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
        double sceneWidth = Math.min(1200, screenBounds.getWidth() * 0.9);
        double sceneHeight = Math.min(800, screenBounds.getHeight() * 0.85);
        Scene scene = new Scene(mainView, sceneWidth, sceneHeight);

        // Load theme CSS
        loadThemeCSS(scene);

        // Listen for theme changes
        themeListener = (obs, oldTheme, newTheme) -> Platform.runLater(() -> loadThemeCSS(scene));
        ThemeService.getInstance().currentThemeProperty().addListener(themeListener);

        stage.setTitle("KeeNotes (" + cn.keevol.keenotes.mobilefx.generated.BuildInfo.VERSION + ")");
        stage.setScene(scene);

        // Set minimum window size for desktop
        stage.setMinWidth(800);
        stage.setMinHeight(600);

        // Set application icon
        var iconStream = getClass().getResourceAsStream("/icons/app-icon.png");
        if (iconStream != null) {
            stage.getIcons().add(new Image(iconStream));
        }

        // 居中显示窗口，确保titlebar可见
        stage.setX(screenBounds.getMinX() + (screenBounds.getWidth() - sceneWidth) / 2);
        stage.setY(screenBounds.getMinY() + (screenBounds.getHeight() - sceneHeight) / 2);

        runtimeMonitor = new FxRuntimeMonitor(stage);
        runtimeMonitor.start();

        // 显示UI - 这是最重要的，用户应该立即看到界面
        stage.show();

        // 检查配置状态，如果未配置则导航到设置界面
        checkConfigurationAndNavigate();

        // UI显示后，延迟初始化服务（在后台线程）
        initializeServicesAfterUI();

        // kick off local import server at background @ by fq
        Thread.ofVirtual().start(new Runnable() {
            @Override
            public void run() {
                SimpleForwardServer.start();
            }
        });

        // kick off MCP server at background
        Thread.ofVirtual().start(new Runnable() {
            @Override
            public void run() {
                cn.keevol.keenotes.mcp.SimpleMcpServer.start();
            }
        });
    }

    /**
     * 检查配置状态，如果未配置则导航到设置界面
     */
    private void checkConfigurationAndNavigate() {
        Platform.runLater(() -> {
            SettingsService settings = SettingsService.getInstance();
            if (!settings.isConfigured()) {
                System.out.println("[Main] Configuration not complete, navigating to Settings...");
                mainView.switchToSettingsMode();
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
            
            // 4. 检查更新（在异步线程，延迟3秒启动）
            Thread updateCheckThread = new Thread(() -> {
                try {
                    Thread.sleep(3000); // Wait 3 seconds after startup
                    System.out.println("Checking for updates...");
                    UpdateCheckService updateChecker = new UpdateCheckService();
                    updateChecker.setUpdateListener((version, url) -> {
                        System.out.println("[UpdateCheck] Notifying UI about update: " + version);
                        if (mainView != null && mainView.getSidebar() != null) {
                            mainView.getSidebar().showUpdateNotification(version, url);
                        }
                    });
                    updateChecker.checkForUpdates();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            updateCheckThread.setDaemon(true);
            updateCheckThread.start();
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
        logger.info("Application stopping");
        try {
            if (runtimeMonitor != null) {
                runtimeMonitor.stop();
                runtimeMonitor = null;
            }
            if (themeListener != null) {
                ThemeService.getInstance().currentThemeProperty().removeListener(themeListener);
                themeListener = null;
            }

            // Dispose UI component listeners (prevents listener leaks on singleton services)
            if (mainView != null) {
                mainView.dispose();
            }

            // Stop MCP Server
            cn.keevol.keenotes.mcp.SimpleMcpServer.stop();

            // Stop local import server (shuts down executor + HTTP server)
            SimpleForwardServer.stop();

            // Stop other services
            ServiceManager.getInstance().shutdown();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error during shutdown", e);
        }
        logger.info("Application stopped");
    }

    private void loadCustomFont() {
        String fontResourcePath = "/fonts/MiSans-Regular.ttf";

        try (var fontStream = getClass().getResourceAsStream(fontResourcePath)) {
            if (fontStream == null) {
                System.err.println("Font resource not found: " + fontResourcePath);
                return;
            }

            // Desktop: load directly from stream
            Font font = Font.loadFont(fontStream, 14);
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
        AppLogger.installGlobalUncaughtExceptionHandler();

        // Maven 启动时会读取系统代理并写入 JVM 属性，需要在应用启动前清除
        // 避免翻墙软件的 SOCKS 代理影响应用网络连接
        System.setProperty("java.net.useSystemProxies", "false");
        System.clearProperty("socksProxyHost");
        System.clearProperty("socksProxyPort");
        System.clearProperty("http.proxyHost");
        System.clearProperty("http.proxyPort");
        System.clearProperty("https.proxyHost");
        System.clearProperty("https.proxyPort");
        launch(args);
    }
}
