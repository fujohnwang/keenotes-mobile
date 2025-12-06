package com.keenotes.mobile;

import com.gluonhq.attach.lifecycle.LifecycleService;
import com.gluonhq.attach.util.Platform;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

public class Main extends Application {

    private StackPane root;

    @Override
    public void start(Stage stage) {
        root = new StackPane();

        showMainView();

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

    private void showMainView() {
        MainView mainView = new MainView(this::showSettingsView);
        root.getChildren().setAll(mainView);
    }

    private void showSettingsView() {
        SettingsView settingsView = new SettingsView(this::showMainView);
        root.getChildren().setAll(settingsView);
    }

    @Override
    public void stop() {
        System.out.println("Application stopped.");
    }

    public static void main(String[] args) {
        launch(args);
    }
}
