package cn.keevol.keenotes.mobilefx;

import com.gluonhq.attach.lifecycle.LifecycleService;
import com.gluonhq.attach.util.Platform;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.stage.Stage;

public class Main extends Application {

    private StackPane contentPane;
    private BorderPane root;
    private MainView mainView;
    private ReviewView reviewView;
    private Button recordTabBtn;
    private Button reviewTabBtn;

    @Override
    public void start(Stage stage) {
        // Load Chinese font for Android/iOS native builds
        loadCustomFont();



        root = new BorderPane();
        contentPane = new StackPane();

        // Create views
        mainView = new MainView(this::showSettingsView);
        reviewView = new ReviewView();

        // Bottom tab bar
        root.setBottom(createBottomTabBar());
        root.setCenter(contentPane);

        // Show record tab by default
        showRecordTab();

        Scene scene = new Scene(root, 375, 667);
        scene.getStylesheets().add(getClass().getResource("/styles/main.css").toExternalForm());

        stage.setTitle("KeeNotes");
        stage.setScene(scene);

        if (Platform.isDesktop()) {
            var iconStream = getClass().getResourceAsStream("/icons/app-icon.png");
            if (iconStream != null) {
                stage.getIcons().add(new Image(iconStream));
            }
        }

        stage.show();

        LifecycleService.create().ifPresent(service ->
                System.out.println("LifecycleService initialized"));
    }


    private HBox createBottomTabBar() {
        recordTabBtn = new Button("记录");
        recordTabBtn.getStyleClass().addAll("tab-button", "active");
        recordTabBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(recordTabBtn, Priority.ALWAYS);
        recordTabBtn.setOnAction(e -> showRecordTab());

        reviewTabBtn = new Button("回顾");
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
        contentPane.getChildren().setAll(mainView);
        reviewTabBtn.getStyleClass().remove("active");
        if (!recordTabBtn.getStyleClass().contains("active")) {
            recordTabBtn.getStyleClass().add("active");
        }
    }

    private void showReviewTab() {
        contentPane.getChildren().setAll(reviewView);
        recordTabBtn.getStyleClass().remove("active");
        if (!reviewTabBtn.getStyleClass().contains("active")) {
            reviewTabBtn.getStyleClass().add("active");
        }
        reviewView.refresh();
    }

    private void showSettingsView() {
        SettingsView settingsView = new SettingsView(this::backFromSettings);
        contentPane.getChildren().setAll(settingsView);
    }

    private void backFromSettings() {
        if (reviewTabBtn.getStyleClass().contains("active")) {
            showReviewTab();
        } else {
            showRecordTab();
        }
    }

    @Override
    public void stop() {
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
            if (Platform.isAndroid() || Platform.isIOS()) {
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
