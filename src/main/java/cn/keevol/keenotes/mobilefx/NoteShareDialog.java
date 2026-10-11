package cn.keevol.keenotes.mobilefx;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
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
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.awt.image.BufferedImage;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class NoteShareDialog extends Dialog<Void> {
    private static final DateTimeFormatter SAVE_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");
    private static final double STATUS_ROW_HEIGHT = 40;

    private final LocalCacheService.NoteData noteData;
    private final Window owner;
    private final String hiddenMessage;
    private PosterInkTheme inkTheme;
    private BufferedImage currentPoster;
    private final ObjectProperty<PosterColorTheme> colorTheme = new SimpleObjectProperty<>(PosterColorTheme.PAPER);
    private Task<BufferedImage> renderTask;
    private VBox carouselControls;

    private final ImageView posterPreview = new ImageView();
    private final Label statusLabel = new Label();
    private final ProgressIndicator progressIndicator = new ProgressIndicator();
    private Button copyPosterButton;
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
        getDialogPane().setMinHeight(0);
        getDialogPane().setStyle(resolveDialogPaneStyle());
        setOnShowing(event -> getDialogPane().setPrefHeight(resolveScreenBounds().getHeight() * 0.8));
        setOnShown(event -> {
            Rectangle2D bounds = resolveScreenBounds();
            // Set the outer window height after decorations have been measured.
            setHeight(bounds.getHeight() * 0.8);
            setY(bounds.getMinY() + (bounds.getHeight() - getHeight()) / 2);
        });
        setOnHidden(event -> {
            if (renderTask != null) {
                renderTask.cancel();
                renderTask = null;
            }
        });
        refreshPreview();
    }

    private Rectangle2D resolveScreenBounds() {
        if (owner == null) return Screen.getPrimary().getVisualBounds();
        return Screen.getScreensForRectangle(
                        owner.getX() + owner.getWidth() / 2,
                        owner.getY() + owner.getHeight() / 2, 1, 1)
                .stream().findFirst().orElse(Screen.getPrimary()).getVisualBounds();
    }

    private VBox createContent() {
        VBox root = new VBox(14);
        root.setMinHeight(0);
        root.setPadding(new Insets(18));
        root.setStyle(resolveRootStyle());

        HBox toolbar = createToolbar();
        toolbar.setMinHeight(Region.USE_PREF_SIZE);

        posterPreview.setFitWidth(390);
        posterPreview.setPreserveRatio(true);
        posterPreview.setSmooth(true);

        StackPane previewWrapper = new StackPane(posterPreview);
        previewWrapper.setAlignment(Pos.TOP_CENTER);
        previewWrapper.setMinWidth(0);
        previewWrapper.setStyle("-fx-background-color: transparent;");

        ScrollPane previewScroll = new ScrollPane(previewWrapper);
        previewScroll.setFitToWidth(true);
        previewScroll.setPannable(true);
        previewScroll.setMinHeight(0);
        previewScroll.setMaxHeight(Double.MAX_VALUE);
        previewScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        // Mirror the space taken by the right scrollbar so the poster stays on the dialog's center line.
        var scrollbarGutter = Bindings.createDoubleBinding(() -> {
            double viewportWidth = previewScroll.getViewportBounds().getWidth();
            if (viewportWidth <= 0) return 0.0;
            Insets insets = previewScroll.getInsets();
            return Math.max(0, previewScroll.getWidth() - viewportWidth - insets.getLeft() - insets.getRight());
        }, previewScroll.widthProperty(), previewScroll.viewportBoundsProperty(), previewScroll.insetsProperty());
        previewWrapper.paddingProperty().bind(Bindings.createObjectBinding(
                () -> new Insets(0, 0, 0, scrollbarGutter.get()), scrollbarGutter));
        posterPreview.fitWidthProperty().bind(Bindings.createDoubleBinding(
                () -> Math.max(1, Math.min(previewScroll.getViewportBounds().getWidth() - scrollbarGutter.get(),
                        previewScroll.getViewportBounds().getHeight() * NotePosterRenderer.ASPECT_RATIO)),
                previewScroll.viewportBoundsProperty(), scrollbarGutter));
        VBox.setVgrow(previewScroll, Priority.ALWAYS);

        statusLabel.setWrapText(true);
        statusLabel.setId("poster-status-label");
        statusLabel.setMaxWidth(Region.USE_PREF_SIZE);
        statusLabel.setMaxHeight(STATUS_ROW_HEIGHT);
        statusLabel.setAlignment(Pos.CENTER);
        statusLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        statusLabel.setStyle(resolveStatusStyle(false));
        Tooltip statusTooltip = new Tooltip();
        statusTooltip.textProperty().bind(statusLabel.textProperty());
        statusLabel.setTooltip(statusTooltip);

        progressIndicator.setMinSize(28, 28);
        progressIndicator.setPrefSize(28, 28);
        progressIndicator.setMaxSize(28, 28);
        progressIndicator.setVisible(false);
        progressIndicator.setId("poster-status-progress");

        // Center the text independently; the indicator sits beside it without shifting it.
        StackPane statusRow = new StackPane(statusLabel, progressIndicator);
        statusRow.setId("poster-status-row");
        statusRow.setAlignment(Pos.CENTER);
        statusRow.setPadding(new Insets(0, 38, 0, 38));
        progressIndicator.translateXProperty().bind(statusLabel.widthProperty()
                .add(progressIndicator.widthProperty()).divide(-2).subtract(10));
        statusRow.setMinHeight(STATUS_ROW_HEIGHT);
        statusRow.setPrefHeight(STATUS_ROW_HEIGHT);
        statusRow.setMaxHeight(STATUS_ROW_HEIGHT);

        carouselControls = createCarouselControls();
        carouselControls.setMinHeight(Region.USE_PREF_SIZE);
        root.getChildren().addAll(toolbar, statusRow, previewScroll, carouselControls);
        return root;
    }

    private VBox createCarouselControls() {
        String arrowStyle = "-fx-background-color: transparent; -fx-border-color: transparent; -fx-padding: 0; -fx-cursor: hand;";
        Button previous = createToolbarButton("", createIcon("M15.41 7.41 14 6l-6 6 6 6 1.41-1.41L10.83 12z"));
        previous.setStyle(arrowStyle);
        previous.setPickOnBounds(true);
        previous.setAccessibleText("上一个海报配色");
        previous.setId("poster-theme-previous");
        previous.setMinSize(44, 44);
        previous.setOnAction(event -> selectColorTheme(colorTheme.get().offset(-1)));
        HBox controls = new HBox(4, previous);
        controls.setAlignment(Pos.CENTER);
        Color ringColor = Color.web(ThemeService.getInstance().isDarkTheme() ? "#E6EDF3" : "#24292F");
        for (PosterColorTheme theme : PosterColorTheme.values()) {
            Circle swatch = new Circle(8, Color.web(theme.backgroundCss()));
            swatch.setStroke(Color.web("#8B949E"));
            Circle ring = new Circle(13, Color.TRANSPARENT);
            ring.setStroke(ringColor);
            ring.setStrokeWidth(2);
            ring.visibleProperty().bind(colorTheme.isEqualTo(theme));
            Button choice = new Button();
            choice.setGraphic(new StackPane(ring, swatch));
            choice.setMinSize(44, 44);
            choice.setPrefSize(44, 44);
            choice.setStyle("-fx-background-color: transparent; -fx-padding: 0; -fx-cursor: hand;");
            choice.setId("poster-theme-" + theme.name().toLowerCase(java.util.Locale.ROOT));
            choice.setTooltip(new Tooltip(theme.getLabel()));
            choice.accessibleTextProperty().bind(Bindings.createStringBinding(
                    () -> theme.getLabel() + (colorTheme.get() == theme ? "，已选中" : ""), colorTheme));
            choice.setOnAction(event -> selectColorTheme(theme));
            controls.getChildren().add(choice);
        }
        Button next = createToolbarButton("", createIcon("M8.59 16.59 10 18l6-6-6-6-1.41 1.41L13.17 12z"));
        next.setStyle(arrowStyle);
        next.setPickOnBounds(true);
        next.setAccessibleText("下一个海报配色");
        next.setId("poster-theme-next");
        next.setMinSize(44, 44);
        next.setOnAction(event -> selectColorTheme(colorTheme.get().offset(1)));
        controls.getChildren().add(next);
        Label name = new Label();
        name.setId("poster-theme-name");
        name.textProperty().bind(Bindings.createStringBinding(() -> colorTheme.get().getLabel(), colorTheme));
        name.setStyle(resolveStatusStyle(false));
        VBox carousel = new VBox(3, controls, name);
        carousel.setAlignment(Pos.CENTER);
        return carousel;
    }

    private void selectColorTheme(PosterColorTheme theme) {
        if (colorTheme.get() == theme) return;
        colorTheme.set(theme);
        refreshPreview();
    }

    private HBox createToolbar() {
        cycleThemeButton = createToolbarButton("换一换", createCycleIcon());
        cycleThemeButton.setTooltip(new Tooltip("切换水墨背景"));
        cycleThemeButton.setOnAction(e -> cycleTheme());

        copyPosterButton = createToolbarButton("复制海报", createCopyIcon());
        copyPosterButton.setTooltip(new Tooltip("复制海报到剪切板"));
        copyPosterButton.setOnAction(e -> copyPoster());

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

        HBox toolbar = new HBox(8, cycleThemeButton, spacer, copyPosterButton, savePosterButton, saveVideoButton, closeButton);
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
        if (renderTask != null) renderTask.cancel();
        currentPoster = null;
        setBusy(true);
        PosterInkTheme inkSnapshot = inkTheme;
        PosterColorTheme colorSnapshot = colorTheme.get();
        Task<BufferedImage> task = new Task<>() {
            @Override
            protected BufferedImage call() throws Exception {
                return NotePosterRenderer.renderPosterImage(noteData, hiddenMessage, inkSnapshot, colorSnapshot);
            }
        };
        renderTask = task;
        task.setOnSucceeded(event -> {
            if (renderTask != task) return;
            try {
                BufferedImage poster = task.getValue();
                posterPreview.setImage(NotePosterRenderer.toFxImage(poster));
                currentPoster = poster;
                setStatus("当前背景：" + inkSnapshot.getLabel(), false);
                setBusy(false);
                configureVideoAvailability();
            } catch (Exception error) {
                showRenderFailure(error);
            }
        });
        task.setOnFailed(event -> {
            if (renderTask == task) showRenderFailure(task.getException());
        });
        AppExecutors.media().execute(task);
    }

    private void showRenderFailure(Throwable error) {
        currentPoster = null;
        posterPreview.setImage(null);
        setBusy(false);
        setStatus("海报预览生成失败：" + (error == null ? "未知错误" : error.getMessage()), true);
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
    }

    private void copyPoster() {
        if (currentPoster == null) {
            refreshPreview();
        }
        if (currentPoster == null) {
            return;
        }

        if (copyPosterToClipboard()) {
            setStatus("海报已复制到剪切板。", false);
        } else {
            setStatus("复制海报到剪切板失败。", true);
        }
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
        File selected = showSaveDialog(chooser);
        if (selected == null) {
            return;
        }

        try {
            NotePosterRenderer.writePng(currentPoster, selected);
            if (copyPosterToClipboard()) {
                setStatus("海报已保存，并已复制到剪切板：" + selected.getName(), false);
            } else {
                setStatus("海报已保存，但复制到剪切板失败：" + selected.getName(), true);
            }
        } catch (Exception e) {
            setStatus("保存海报失败：" + e.getMessage(), true);
        }
    }

    private boolean copyPosterToClipboard() {
        try {
            ClipboardContent clipboardContent = new ClipboardContent();
            clipboardContent.putImage(NotePosterRenderer.toFxImage(currentPoster));
            return Clipboard.getSystemClipboard().setContent(clipboardContent);
        } catch (Exception e) {
            return false;
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
        File selected = showSaveDialog(chooser);
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

    private File showSaveDialog(FileChooser chooser) {
        Window dialogWindow = resolveDialogWindow();
        try {
            return chooser.showSaveDialog(dialogWindow);
        } finally {
            restoreDialogFocus(dialogWindow);
        }
    }

    private Window resolveDialogWindow() {
        if (getDialogPane().getScene() != null && getDialogPane().getScene().getWindow() != null) {
            return getDialogPane().getScene().getWindow();
        }
        return owner;
    }

    private void restoreDialogFocus(Window dialogWindow) {
        if (dialogWindow == null) {
            return;
        }
        Platform.runLater(() -> {
            if (!dialogWindow.isShowing()) {
                return;
            }
            if (dialogWindow instanceof Stage stage) {
                stage.toFront();
            }
            dialogWindow.requestFocus();
        });
    }

    private void setBusy(boolean busy) {
        // Visibility does not affect the centered text or the fixed status row height.
        progressIndicator.setVisible(busy);
        cycleThemeButton.setDisable(busy);
        carouselControls.setDisable(busy);
        copyPosterButton.setDisable(busy || currentPoster == null);
        savePosterButton.setDisable(busy || currentPoster == null);
        saveVideoButton.setDisable(busy || currentPoster == null || !NotePosterVideoExporter.isFfmpegAvailable());
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

    private SVGPath createCopyIcon() {
        return createIcon("M16 1H4c-1.1 0-2 .9-2 2v12h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z");
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
