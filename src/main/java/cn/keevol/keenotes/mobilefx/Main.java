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

    public static void main(String[] args) {
        launch(args);
    }
}
