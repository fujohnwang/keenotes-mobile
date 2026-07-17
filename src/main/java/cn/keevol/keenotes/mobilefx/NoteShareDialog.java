package cn.keevol.keenotes.mobilefx;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Button;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Window;

import java.awt.image.BufferedImage;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class NoteShareDialog extends Dialog<Void> {
    private static final DateTimeFormatter SAVE_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");

    private final LocalCacheService.NoteData noteData;
    private final Window owner;
    private final String hiddenMessage;
    private PosterInkTheme inkTheme;
    private BufferedImage currentPoster;

    private final ImageView posterPreview = new ImageView();
    private final Label statusLabel = new Label();
    private final ProgressIndicator progressIndicator = new ProgressIndicator();
    private Button savePosterButton;
    private Button saveVideoButton;
    private Button cycleThemeButton;

    public NoteShareDialog(Window owner, LocalCacheService.NoteData noteData) {
        this.owner = owner;
        this.noteData = noteData;
        this.hiddenMessage = SettingsService.getInstance().getHiddenMessage();
        this.inkTheme = PosterInkTheme.stableForNoteId(noteData.id);

        if (owner != null) {
            initOwner(owner);
            initModality(Modality.WINDOW_MODAL);
        }
        setTitle("Share Note");
        configureDialogCloseBehavior();
        getDialogPane().setContent(createContent());
        getDialogPane().setPrefWidth(560);
        getDialogPane().setPrefHeight(760);
        getDialogPane().setStyle(resolveDialogPaneStyle());
        refreshPreview();
        configureVideoAvailability();
    }

    private VBox createContent() {
        VBox root = new VBox(14);
        root.setPadding(new Insets(18));
        root.setStyle(resolveRootStyle());

        HBox toolbar = createToolbar();

        posterPreview.setFitWidth(390);
        posterPreview.setPreserveRatio(true);
        posterPreview.setSmooth(true);

        StackPane previewWrapper = new StackPane(posterPreview);
        previewWrapper.setAlignment(Pos.TOP_CENTER);
        previewWrapper.setMinWidth(Region.USE_PREF_SIZE);
        previewWrapper.setStyle("-fx-background-color: transparent;");

        ScrollPane previewScroll = new ScrollPane(previewWrapper);
        previewScroll.setFitToWidth(true);
        previewScroll.setPannable(true);
        previewScroll.setMaxHeight(590);
        previewScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(previewScroll, Priority.ALWAYS);

        statusLabel.setWrapText(true);
        statusLabel.setStyle(resolveStatusStyle(false));

        progressIndicator.setMaxSize(28, 28);
        progressIndicator.setVisible(false);
        progressIndicator.setManaged(false);

        HBox statusRow = new HBox(10, progressIndicator, statusLabel);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        root.getChildren().addAll(toolbar, previewScroll, statusRow);
        return root;
    }

    private HBox createToolbar() {
        cycleThemeButton = createToolbarButton("换一换", createCycleIcon());
        cycleThemeButton.setTooltip(new Tooltip("切换水墨背景"));
        cycleThemeButton.setOnAction(e -> cycleTheme());

        savePosterButton = createToolbarButton("保存海报", createDownloadIcon());
        savePosterButton.setTooltip(new Tooltip("保存 PNG 海报"));
        savePosterButton.setOnAction(e -> savePoster());

        saveVideoButton = createToolbarButton("保存视频", createVideoIcon());
        saveVideoButton.setTooltip(new Tooltip("保存 MP4 视频"));
        saveVideoButton.setOnAction(e -> saveVideo());

        Button closeButton = createToolbarButton("关闭", createCloseIcon());
        closeButton.setTooltip(new Tooltip("关闭"));
        closeButton.setOnAction(e -> requestClose());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox toolbar = new HBox(8, cycleThemeButton, spacer, savePosterButton, saveVideoButton, closeButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        return toolbar;
    }

    private Button createToolbarButton(String text, SVGPath icon) {
        Button button = new Button(text);
        button.setGraphic(icon);
        button.setStyle(resolveToolbarButtonStyle());
        return button;
    }

    private void configureDialogCloseBehavior() {
        ButtonType closeType = new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE);
        getDialogPane().getButtonTypes().add(closeType);
        Node dialogCloseButton = getDialogPane().lookupButton(closeType);
        if (dialogCloseButton != null) {
            dialogCloseButton.setVisible(false);
            dialogCloseButton.setManaged(false);
        }
    }

    private void refreshPreview() {
        try {
            currentPoster = NotePosterRenderer.renderPosterImage(noteData, hiddenMessage, inkTheme);
            posterPreview.setImage(NotePosterRenderer.toFxImage(currentPoster));
            setStatus("当前背景：" + inkTheme.getLabel(), false);
        } catch (Exception e) {
            setStatus("海报预览生成失败：" + e.getMessage(), true);
            savePosterButton.setDisable(true);
            saveVideoButton.setDisable(true);
        }
    }

    private void configureVideoAvailability() {
        if (!NotePosterVideoExporter.isFfmpegAvailable()) {
            saveVideoButton.setDisable(true);
            saveVideoButton.setTooltip(new Tooltip("未找到 ffmpeg，视频生成不可用"));
            setStatus("未找到 ffmpeg，视频生成不可用。仍可保存海报。", false);
        }
    }

    private void cycleTheme() {
        inkTheme = inkTheme.next();
        refreshPreview();
        configureVideoAvailability();
    }

    private void savePoster() {
        if (currentPoster == null) {
            refreshPreview();
        }
        if (currentPoster == null) {
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("保存海报");
        chooser.setInitialFileName(defaultFileName(".png"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG Image", "*.png"));
        File selected = chooser.showSaveDialog(owner);
        if (selected == null) {
            return;
        }

        try {
            NotePosterRenderer.writePng(currentPoster, selected);
            setStatus("海报已保存：" + selected.getName(), false);
        } catch (Exception e) {
            setStatus("保存海报失败：" + e.getMessage(), true);
        }
    }

    private void saveVideo() {
        if (!NotePosterVideoExporter.isFfmpegAvailable()) {
            setStatus("未找到 ffmpeg，视频生成不可用。", false);
            saveVideoButton.setDisable(true);
            return;
        }
        if (currentPoster == null) {
            refreshPreview();
        }
        if (currentPoster == null) {
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("保存视频");
        chooser.setInitialFileName(defaultFileName(".mp4"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("MP4 Video", "*.mp4"));
        File selected = chooser.showSaveDialog(owner);
        if (selected == null) {
            return;
        }

        BufferedImage posterForExport = currentPoster;
        Task<Void> exportTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                NotePosterVideoExporter.exportVideo(posterForExport, selected);
                return null;
            }
        };
        exportTask.setOnRunning(e -> {
            setBusy(true);
            setStatus("正在生成视频...", false);
        });
        exportTask.setOnSucceeded(e -> {
            setBusy(false);
            setStatus("视频已保存：" + selected.getName(), false);
        });
        exportTask.setOnFailed(e -> {
            setBusy(false);
            Throwable error = exportTask.getException();
            String message = error == null ? "未知错误" : error.getMessage();
            setStatus("视频生成失败：" + message, true);
        });
        AppExecutors.media().execute(exportTask);
    }

    private void setBusy(boolean busy) {
        progressIndicator.setVisible(busy);
        progressIndicator.setManaged(busy);
        cycleThemeButton.setDisable(busy);
        savePosterButton.setDisable(busy);
        saveVideoButton.setDisable(busy || !NotePosterVideoExporter.isFfmpegAvailable());
    }

    private void requestClose() {
        setResult(null);
        close();
    }

    private void setStatus(String message, boolean error) {
        statusLabel.setText(message);
        statusLabel.setStyle(resolveStatusStyle(error));
    }

    private String defaultFileName(String extension) {
        String id = noteData.id > 0 ? Long.toString(noteData.id) : "draft";
        String timestamp = LocalDateTime.now().format(SAVE_TIMESTAMP_FORMATTER);
        return sanitizeFileName("keenotes-" + id + "-" + timestamp + extension);
    }

    private String sanitizeFileName(String fileName) {
        return fileName.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    private SVGPath createCycleIcon() {
        return createIcon("M12 5V2L7 7l5 5V8c3.31 0 6 2.69 6 6 0 1.01-.25 1.96-.7 2.8l2.2 2.2C20.44 17.53 21 15.82 21 14c0-4.97-4.03-9-9-9zm-6 5c0-1.01.25-1.96.7-2.8L4.5 5C3.56 6.47 3 8.18 3 10c0 4.97 4.03 9 9 9v3l5-5-5-5v4c-3.31 0-6-2.69-6-6z");
    }

    private SVGPath createDownloadIcon() {
        return createIcon("M5 20h14v-2H5v2zM19 9h-4V3H9v6H5l7 7 7-7z");
    }

    private SVGPath createVideoIcon() {
        return createIcon("M17 10.5V6c0-.55-.45-1-1-1H4c-.55 0-1 .45-1 1v12c0 .55.45 1 1 1h12c.55 0 1-.45 1-1v-4.5l4 4v-11l-4 4z");
    }

    private SVGPath createCloseIcon() {
        return createIcon("M18.3 5.71 12 12l6.3 6.29-1.41 1.41-6.3-6.29-6.29 6.3-1.41-1.41L9.17 12 2.89 5.71 4.3 4.29l6.29 6.3 6.3-6.3z");
    }

    private SVGPath createIcon(String path) {
        SVGPath icon = new SVGPath();
        icon.setContent(path);
        icon.setScaleX(0.72);
        icon.setScaleY(0.72);
        icon.setFill(Color.web(ThemeService.getInstance().isDarkTheme() ? "#E6EDF3" : "#24292F"));
        return icon;
    }

    private String resolveDialogPaneStyle() {
        return "-fx-background-color: transparent; -fx-padding: 0;";
    }

    private String resolveRootStyle() {
        boolean dark = ThemeService.getInstance().isDarkTheme();
        String bg = dark ? "#161B22" : "#FFFFFF";
        String border = dark ? "#30363D" : "#D0D7DE";
        return "-fx-background-color: " + bg + ";"
                + "-fx-border-color: " + border + ";"
                + "-fx-border-radius: 12;"
                + "-fx-background-radius: 12;";
    }

    private String resolveToolbarButtonStyle() {
        boolean dark = ThemeService.getInstance().isDarkTheme();
        String text = dark ? "#E6EDF3" : "#24292F";
        String bg = dark ? "rgba(255,255,255,0.08)" : "rgba(9,105,218,0.08)";
        String border = dark ? "#30363D" : "#D0D7DE";
        return "-fx-background-color: " + bg + ";"
                + "-fx-text-fill: " + text + ";"
                + "-fx-border-color: " + border + ";"
                + "-fx-border-radius: 18;"
                + "-fx-background-radius: 18;"
                + "-fx-padding: 8 12;"
                + "-fx-cursor: hand;";
    }

    private String resolveStatusStyle(boolean error) {
        if (error) {
            return "-fx-text-fill: #F85149; -fx-font-size: 12px;";
        }
        boolean dark = ThemeService.getInstance().isDarkTheme();
        return "-fx-text-fill: " + (dark ? "#8B949E" : "#57606A") + "; -fx-font-size: 12px;";
    }
}
