package cn.keevol.keenotes.mobilefx;

import cn.keevol.keenotes.mobilefx.utils.DateTimeUtil;
import javafx.animation.AnimationTimer;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Individual note card component for displaying a single note
 * Click note content area to copy entire content to clipboard
 * Select text with mouse, right-click or Ctrl+C to copy selection
 */
public class NoteCardView extends StackPane {

    private LocalCacheService.NoteData noteData;
    private final Label copiedPopup;
    private final TextArea contentArea;
    private final SettingsService settings;
    private final Text textMeasure;
    private final Label dateLabel;
    private final Label channelLabel;
    private final Button shareButton;
    private final SVGPath shareIcon;

    // Border progress animation
    private Canvas borderCanvas;
    private AnimationTimer borderTimer;
    private long borderStartNanos;
    private static final double BORDER_ANIM_DURATION = 10.0; // max seconds for one full loop
    private static final double BORDER_LINE_WIDTH = 3.0;
    private static final double BORDER_RADIUS = 12.0;
    private static final double MIN_CONTENT_HEIGHT = 30.0;
    private static final double FALLBACK_TEXT_HORIZONTAL_PADDING = 18.0;
    private static final double FALLBACK_TEXT_VERTICAL_PADDING = 24.0;

    // Precise horizontal inset: contentArea.width - viewport.width, calibrated after skin loads
    private double horizontalInset = FALLBACK_TEXT_HORIZONTAL_PADDING;
    private boolean widthCalibrated = false;
    private boolean internalTextListenerAttached = false;
    private boolean viewportWidthListenerAttached = false;
    private final AtomicBoolean layoutRefreshPending = new AtomicBoolean(false);
    private boolean updatingContentHeight = false;

    public NoteCardView(LocalCacheService.NoteData noteData) {
        this.noteData = noteData;
        this.settings = SettingsService.getInstance();

        getStyleClass().add("search-result-card");
        setCache(false);
        setCacheShape(false);

        // Main content container
        VBox contentBox = new VBox(8);
        contentBox.setPadding(new Insets(16));

        // Header row: date and channel
        HBox headerRow = new HBox(12);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        headerRow.setMaxWidth(Double.MAX_VALUE);

        dateLabel = new Label(DateTimeUtil.utcToLocalDisplay(noteData.createdAt));
        dateLabel.getStyleClass().add("note-date");

        // Channel/source label
        String channelText = (noteData.channel != null && !noteData.channel.isEmpty())
                ? noteData.channel
                : "default";
        channelLabel = new Label("• " + channelText);
        channelLabel.getStyleClass().add("note-channel");

        boolean isDark = ThemeService.getInstance().isDarkTheme();
        String secondaryColor = isDark ? "#8B949E" : "#57606A";
        channelLabel.setStyle("-fx-text-fill: " + secondaryColor + "; -fx-font-size: 12px;");

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        shareIcon = createShareIcon();
        shareButton = new Button();
        shareButton.setGraphic(shareIcon);
        shareButton.setTooltip(new Tooltip("Share as poster or video"));
        shareButton.setFocusTraversable(false);
        shareButton.setOnAction(e -> {
            e.consume();
            showShareDialog();
        });
        updateShareButtonStyle();

        headerRow.getChildren().addAll(dateLabel, channelLabel, headerSpacer, shareButton);

        // Full content using TextArea (read-only, selectable)
        contentArea = new TextArea(noteData.content);
        contentArea.setEditable(false);
        contentArea.setWrapText(true);
        contentArea.setMaxWidth(Double.MAX_VALUE);
        contentArea.getStyleClass().add("note-content-area");

        String textColor = isDark ? "#E6EDF3" : "#24292F";
        int fontSize = settings.getNoteFontSize();
        String fontFamily = settings.getEffectiveNoteFontFamily();
        applyContentAreaStyle(textColor, fontSize, fontFamily);

        // Disable scrollbars completely
        contentArea.setScrollTop(0);
        contentArea.setScrollLeft(0);
        contentArea.skinProperty().addListener((obs, oldSkin, newSkin) -> {
            if (newSkin != null) {
                scheduleLayoutRefresh();
            }
        });

        // Create hidden Text node for height measurement (fallback before skin loads)
        textMeasure = new Text();
        textMeasure.setFont(Font.font(fontFamily, fontSize));
        textMeasure.setWrappingWidth(0);
        textMeasure.textProperty().bind(contentArea.textProperty());

        // Reactive: textMeasure layoutBounds change → update height
        textMeasure.layoutBoundsProperty().addListener((obs, oldBounds, newBounds) -> {
            updateContentAreaHeight();
        });

        // Reactive: contentArea width change → recalibrate wrapping width and relayout
        contentArea.widthProperty().addListener((obs, oldWidth, newWidth) -> {
            double w = newWidth.doubleValue();
            if (w > 0 && Math.abs(w - oldWidth.doubleValue()) > 0.5) {
                scheduleLayoutRefresh();
            }
        });

        // Card width is available only after ListView lays out the cell
        widthProperty().addListener((obs, oldWidth, newWidth) -> {
            double w = newWidth.doubleValue();
            if (w > 0 && Math.abs(w - oldWidth.doubleValue()) > 0.5) {
                scheduleLayoutRefresh();
            }
        });

        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                scheduleLayoutRefresh();
            }
        });

        // Custom context menu for copy
        ContextMenu contextMenu = new ContextMenu();
        contextMenu.getStyleClass().add("note-context-menu");
        MenuItem copyItem = new MenuItem("Copy");
        copyItem.setOnAction(e -> copySelectedOrAll());
        MenuItem copyAllItem = new MenuItem("Copy All");
        copyAllItem.setOnAction(e -> handleCopy());
        contextMenu.getItems().addAll(copyItem, copyAllItem);
        contentArea.setContextMenu(contextMenu);

        // Keyboard shortcut Ctrl+C / Cmd+C for copy
        contentArea.setOnKeyPressed(e -> {
            if (new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN).match(e)) {
                copySelectedOrAll();
                e.consume();
            }
        });

        // Click on TextArea (when no text selected) to copy all.
        contentArea.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 1) {
                String selected = contentArea.getSelectedText();
                if (selected == null || selected.isEmpty()) {
                    handleCopy();
                    e.consume();
                }
            }
        });

        // Copied popup (positioned at top-right of the note content area)
        copiedPopup = new Label("✓ Copied");
        copiedPopup.getStyleClass().add("copied-popup");

        boolean isDarkPopup = ThemeService.getInstance().isDarkTheme();
        String primaryColor = isDarkPopup ? "#00D4FF" : "#0969DA";
        String popupBg = isDarkPopup ? "rgba(0, 212, 255, 0.2)" : "rgba(9, 105, 218, 0.2)";
        copiedPopup.setStyle(
                "-fx-background-color: " + popupBg + "; " +
                        "-fx-text-fill: " + primaryColor + "; " +
                        "-fx-padding: 4 8; " +
                        "-fx-background-radius: 4; " +
                        "-fx-font-size: 11px;");
        copiedPopup.setVisible(false);
        copiedPopup.setOpacity(0);
        copiedPopup.setMouseTransparent(true);
        StackPane.setAlignment(copiedPopup, Pos.TOP_RIGHT);
        StackPane.setMargin(copiedPopup, new Insets(8, 8, 0, 0));

        StackPane contentCopyArea = new StackPane(contentArea, copiedPopup);
        contentCopyArea.setMaxWidth(Double.MAX_VALUE);

        contentBox.getChildren().addAll(headerRow, contentCopyArea);

        getChildren().add(contentBox);

        // Border animation canvas (on top, mouse transparent, does not affect layout)
        borderCanvas = new Canvas();
        borderCanvas.setMouseTransparent(true);
        borderCanvas.setManaged(false); // 不参与布局计算
        getChildren().add(borderCanvas);

        // Canvas 尺寸跟随卡片实际尺寸，但不影响 preferred size
        layoutBoundsProperty().addListener((obs, oldBounds, newBounds) -> {
            borderCanvas.setWidth(newBounds.getWidth());
            borderCanvas.setHeight(newBounds.getHeight());
        });

        // Hover effect on card
        setOnMouseEntered(e -> {
            setStyle("-fx-background-color: rgba(255, 255, 255, 0.05); " +
                    "-fx-border-color: #30363D; " +
                    "-fx-border-radius: 6; " +
                    "-fx-background-radius: 6;");
        });

        setOnMouseExited(e -> {
            setStyle("");
        });
    }

    /**
     * Coalesce layout refresh requests onto a single FX pulse to avoid synchronous
     * width/listener feedback loops that can freeze the UI thread.
     */
    private void scheduleLayoutRefresh() {
        if (layoutRefreshPending.compareAndSet(false, true)) {
            javafx.application.Platform.runLater(() -> {
                layoutRefreshPending.set(false);
                if (getScene() != null) {
                    requestLayoutRefresh();
                }
            });
        }
    }

    /**
     * Recalibrate text wrapping and relayout the card subtree.
     * Mirrors the layout flush that hover's setStyle() used to trigger implicitly.
     */
    private void requestLayoutRefresh() {
        calibrateHorizontalInset();
        setupInternalTextListener();
        setupViewportWidthListener();
        hideScrollBars();
        double w = contentArea.getWidth();
        if (w > 0) {
            syncTextMeasureWrappingWidth(w);
        }
        requestLayout();
        updateContentAreaHeight();
    }

    /**
     * Calibrate the precise horizontal inset by measuring the difference between
     * contentArea width and viewport width. Called once after skin loads.
     */
    private void calibrateHorizontalInset() {
        javafx.scene.Node viewportNode = contentArea.lookup(".scroll-pane .viewport");
        if (viewportNode instanceof Region viewportRegion && viewportRegion.getWidth() > 0) {
            double contentAreaWidth = contentArea.getWidth();
            if (contentAreaWidth > 0) {
                horizontalInset = contentAreaWidth - viewportRegion.getWidth();
            }
        }
        // Sync textMeasure with the now-precise inset
        syncTextMeasureWrappingWidth(contentArea.getWidth());
    }

    /**
     * Sync textMeasure wrappingWidth from contentArea width using calibrated inset.
     * This is the synchronous path — fires immediately when contentArea width changes.
     */
    private void syncTextMeasureWrappingWidth(double contentAreaWidth) {
        if (contentAreaWidth <= 0) return;
        double wrappingWidth = Math.max(0, contentAreaWidth - horizontalInset);
        if (wrappingWidth > 0 && Math.abs(textMeasure.getWrappingWidth() - wrappingWidth) > 0.5) {
            textMeasure.setWrappingWidth(wrappingWidth);
            widthCalibrated = true;
        }
    }

    /**
     * Reactive: listen to viewport width for precise async correction.
     * Viewport width is the ground truth for text wrapping.
     */
    private void setupViewportWidthListener() {
        if (viewportWidthListenerAttached) {
            return;
        }
        javafx.scene.Node viewportNode = contentArea.lookup(".scroll-pane .viewport");
        if (viewportNode instanceof Region viewportRegion) {
            viewportRegion.widthProperty().addListener((obs, oldW, newW) -> {
                double w = newW.doubleValue();
                if (w > 0 && Math.abs(textMeasure.getWrappingWidth() - w) > 0.5) {
                    textMeasure.setWrappingWidth(w);
                    widthCalibrated = true;
                    updateContentAreaHeight();
                }
            });
            viewportWidthListenerAttached = true;
        }
    }

    /**
     * Reactive: listen to the actual rendered Text node inside TextArea.
     * Any change (width/font/content) that causes the internal Text to re-wrap
     * will automatically flow through this listener to update contentArea height.
     */
    private void setupInternalTextListener() {
        if (internalTextListenerAttached) {
            return;
        }
        javafx.scene.Node internalText = contentArea.lookup(".text");
        if (internalText != null) {
            internalText.layoutBoundsProperty().addListener((obs, oldBounds, newBounds) -> {
                updateContentAreaHeight();
            });
            internalTextListenerAttached = true;
            updateContentAreaHeight();
        }
    }

    /**
     * Hide scrollbars from TextArea (called once after skin is loaded)
     */
    private void hideScrollBars() {
        javafx.scene.Node vbar = contentArea.lookup(".scroll-bar:vertical");
        javafx.scene.Node hbar = contentArea.lookup(".scroll-bar:horizontal");
        if (vbar != null) {
            vbar.setVisible(false);
            vbar.setManaged(false);
        }
        if (hbar != null) {
            hbar.setVisible(false);
            hbar.setManaged(false);
        }
    }

    /**
     * Copy selected text, or all content if nothing selected
     */
    private void copySelectedOrAll() {
        String selected = contentArea.getSelectedText();
        if (selected != null && !selected.isEmpty()) {
            // Copy selection
            Clipboard clipboard = Clipboard.getSystemClipboard();
            ClipboardContent content = new ClipboardContent();
            String hiddenMessage = SettingsService.getInstance().getHiddenMessage();
            content.putString(ZeroWidthSteganography.embedIfNeeded(selected, hiddenMessage));
            clipboard.setContent(content);
            showCopiedPopup();
            // Deselect text after copy
            contentArea.deselect();
        } else {
            // Copy all
            handleCopy();
        }
    }

    private void handleCopy() {
        // Copy entire content to clipboard
        Clipboard clipboard = Clipboard.getSystemClipboard();
        ClipboardContent content = new ClipboardContent();
        String hiddenMessage = SettingsService.getInstance().getHiddenMessage();
        content.putString(ZeroWidthSteganography.embedIfNeeded(noteData.content, hiddenMessage));
        clipboard.setContent(content);

        showCopiedPopup();
        // Deselect text after copy
        contentArea.deselect();
    }

    private void showCopiedPopup() {
        // Show popup with fade in/out animation
        copiedPopup.setVisible(true);

        // Fade in
        FadeTransition fadeIn = new FadeTransition(Duration.millis(150), copiedPopup);
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);
        fadeIn.play();

        // Fade out after delay
        PauseTransition pause = new PauseTransition(Duration.millis(1200));
        pause.setOnFinished(e -> {
            FadeTransition fadeOut = new FadeTransition(Duration.millis(150), copiedPopup);
            fadeOut.setFromValue(1);
            fadeOut.setToValue(0);
            fadeOut.setOnFinished(ev -> {
                copiedPopup.setVisible(false);
            });
            fadeOut.play();
        });
        pause.play();
    }

    public LocalCacheService.NoteData getNoteData() {
        return noteData;
    }

    /**
     * Update card with new data (for cell reuse in ListView).
     * Also reapplies current theme/font in case they changed since construction.
     */
    public void update(LocalCacheService.NoteData newData) {
        this.noteData = newData;
        dateLabel.setText(DateTimeUtil.utcToLocalDisplay(newData.createdAt));
        String ch = (newData.channel != null && !newData.channel.isEmpty()) ? newData.channel : "default";
        channelLabel.setText("• " + ch);
        contentArea.setText(newData.content);
        updateThemeColors();
        updateContentTypography();
        cancelBorderAnimation();
        scheduleLayoutRefresh();
    }

    /**
     * Lightweight optimistic-resolve path: content is unchanged, only server metadata differs.
     * Avoids TextArea relayout that can deadlock the FX thread during send completion.
     */
    public void resolveWithRealNote(LocalCacheService.NoteData realNote) {
        this.noteData = realNote;
        dateLabel.setText(DateTimeUtil.utcToLocalDisplay(realNote.createdAt));
        String ch = (realNote.channel != null && !realNote.channel.isEmpty()) ? realNote.channel : "default";
        channelLabel.setText("• " + ch);
    }

    /**
     * Update colors based on current theme
     */
    private void updateThemeColors() {
        boolean isDark = ThemeService.getInstance().isDarkTheme();
        String secondaryColor = isDark ? "#8B949E" : "#57606A";
        String textColor = isDark ? "#E6EDF3" : "#24292F";
        String primaryColor = isDark ? "#00D4FF" : "#0969DA";
        String popupBg = isDark ? "rgba(0, 212, 255, 0.2)" : "rgba(9, 105, 218, 0.2)";

        // Update channel label color
        if (getChildren().size() > 0 && getChildren().get(0) instanceof VBox) {
            VBox contentBox = (VBox) getChildren().get(0);
            if (contentBox.getChildren().size() > 0 && contentBox.getChildren().get(0) instanceof HBox) {
                HBox headerRow = (HBox) contentBox.getChildren().get(0);
                if (headerRow.getChildren().size() > 1) {
                    javafx.scene.Node channelNode = headerRow.getChildren().get(1);
                    if (channelNode instanceof Label) {
                        ((Label) channelNode).setStyle("-fx-text-fill: " + secondaryColor + "; -fx-font-size: 12px;");
                    }
                }
            }
        }

        // Update content area text color
        applyContentAreaStyle(textColor, settings.getNoteFontSize(), settings.getEffectiveNoteFontFamily());

        // Update copied popup colors
        copiedPopup.setStyle(
                "-fx-background-color: " + popupBg + "; " +
                        "-fx-text-fill: " + primaryColor + "; " +
                        "-fx-padding: 4 8; " +
                        "-fx-background-radius: 4; " +
                        "-fx-font-size: 11px; " +
                        "-fx-font-weight: bold;");
        updateShareButtonStyle();
    }

    /**
     * Update font size for content area
     */
    private void updateContentTypography() {
        boolean isDark = ThemeService.getInstance().isDarkTheme();
        String textColor = isDark ? "#E6EDF3" : "#24292F";
        int fontSize = settings.getNoteFontSize();
        String fontFamily = settings.getEffectiveNoteFontFamily();
        applyContentAreaStyle(textColor, fontSize, fontFamily);
        textMeasure.setFont(Font.font(fontFamily, fontSize));
        // Height update is reactive: CSS/font change → internal Text layoutBounds change → listener
    }

    private void applyContentAreaStyle(String textColor, int fontSize, String fontFamily) {
        contentArea.setStyle(
                "-fx-control-inner-background: transparent; " +
                        "-fx-background-color: transparent; " +
                        "-fx-text-fill: " + textColor + "; " +
                        "-fx-font-family: " + toCssFontFamily(fontFamily) + "; " +
                        "-fx-font-size: " + fontSize + "px; " +
                        "-fx-border-width: 0; " +
                        "-fx-focus-color: transparent; " +
                        "-fx-faint-focus-color: transparent; " +
                        "-fx-cursor: hand;");
    }

    private String toCssFontFamily(String fontFamily) {
        String safeFontFamily = (fontFamily == null || fontFamily.isBlank()) ? "System" : fontFamily;
        return "\"" + safeFontFamily
                .replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\"";
    }

    private SVGPath createShareIcon() {
        SVGPath icon = new SVGPath();
        icon.setContent("M18 16.5 C17.2 16.5 16.5 16.8 16 17.3 L8.9 13.2 C9 12.8 9 12.4 9 12 C9 11.6 9 11.2 8.9 10.8 L16 6.7 C16.5 7.2 17.2 7.5 18 7.5 C19.7 7.5 21 6.2 21 4.5 C21 2.8 19.7 1.5 18 1.5 C16.3 1.5 15 2.8 15 4.5 C15 4.9 15.1 5.3 15.2 5.6 L8.1 9.7 C7.6 9.3 6.9 9 6 9 C4.3 9 3 10.3 3 12 C3 13.7 4.3 15 6 15 C6.9 15 7.6 14.7 8.1 14.3 L15.2 18.4 C15.1 18.7 15 19.1 15 19.5 C15 21.2 16.3 22.5 18 22.5 C19.7 22.5 21 21.2 21 19.5 C21 17.8 19.7 16.5 18 16.5 Z");
        icon.setScaleX(0.58);
        icon.setScaleY(0.58);
        return icon;
    }

    private void updateShareButtonStyle() {
        boolean isDark = ThemeService.getInstance().isDarkTheme();
        String iconColor = isDark ? "#8B949E" : "#57606A";
        String hoverBg = isDark ? "rgba(255, 255, 255, 0.08)" : "rgba(9, 105, 218, 0.08)";
        shareIcon.setFill(Color.web(iconColor));
        shareButton.setStyle(
                "-fx-background-color: transparent; " +
                        "-fx-background-radius: 16; " +
                        "-fx-padding: 4 6; " +
                        "-fx-min-width: 28; " +
                        "-fx-min-height: 28; " +
                        "-fx-cursor: hand;");
        shareButton.setOnMouseEntered(e -> shareButton.setStyle(
                "-fx-background-color: " + hoverBg + "; " +
                        "-fx-background-radius: 16; " +
                        "-fx-padding: 4 6; " +
                        "-fx-min-width: 28; " +
                        "-fx-min-height: 28; " +
                        "-fx-cursor: hand;"));
        shareButton.setOnMouseExited(e -> updateShareButtonStyle());
    }

    private void showShareDialog() {
        Window owner = getScene() == null ? null : getScene().getWindow();
        LocalCacheService.NoteData snapshot = new LocalCacheService.NoteData(
                noteData.id,
                noteData.content,
                noteData.channel,
                noteData.createdAt,
                noteData.encryptedContent
        );
        NoteShareDialog dialog = new NoteShareDialog(owner, snapshot);
        dialog.show();
    }

    /**
     * Single reactive handler: compute the correct contentArea height from
     * the best available text measurement and apply it.
     * Called only by layoutBoundsProperty listeners — never manually.
     */
    private void updateContentAreaHeight() {
        if (updatingContentHeight) {
            return;
        }
        updatingContentHeight = true;
        try {
            updateContentAreaHeightImpl();
        } finally {
            updatingContentHeight = false;
        }
    }

    private void updateContentAreaHeightImpl() {
        double textHeight = 0;

        // Prefer actual rendered Text node height — ground truth after skin load
        javafx.scene.Node internalText = contentArea.lookup(".text");
        if (internalText != null) {
            double internalHeight = Math.ceil(internalText.getLayoutBounds().getHeight());
            if (internalHeight > 0) {
                textHeight = internalHeight;
            }
        }

        // Fallback to hidden Text node only after wrapping width has been calibrated
        if (textHeight <= 0 && widthCalibrated) {
            textHeight = Math.ceil(textMeasure.getLayoutBounds().getHeight());
        }
        if (textHeight <= 0) {
            return;
        }

        double padding = resolveTextVerticalPadding();
        double height = Math.max(MIN_CONTENT_HEIGHT, textHeight + padding);
        if (Math.abs(contentArea.getPrefHeight() - height) > 0.5 || Math.abs(contentArea.getMaxHeight() - height) > 0.5) {
            contentArea.setPrefHeight(height);
            contentArea.setMinHeight(height);
            contentArea.setMaxHeight(height);
        }
        hideScrollBars();
    }

    private double resolveTextVerticalPadding() {
        double padding = contentArea.snappedTopInset() + contentArea.snappedBottomInset() + FALLBACK_TEXT_VERTICAL_PADDING;

        javafx.scene.Node contentNode = contentArea.lookup(".content");
        if (contentNode instanceof Region contentRegion) {
            Insets insets = contentRegion.getInsets();
            padding = Math.max(padding, insets.getTop() + insets.getBottom() + FALLBACK_TEXT_VERTICAL_PADDING);
        }

        return padding;
    }

    /**
     * 启动边缘色条动画：从左上角顺时针沿圆角矩形边缘增长。
     * 色条颜色使用主题的 primary color（dark: #00D4FF, light: #0969DA）。
     */
    public void startBorderAnimation() {
        borderStartNanos = System.nanoTime();
        if (borderTimer != null)
            borderTimer.stop();
        borderTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                double elapsed = (now - borderStartNanos) / 1_000_000_000.0;
                double t = Math.min(elapsed / BORDER_ANIM_DURATION, 1.0);
                // ease-out cubic: 前段快、后段慢，让用户立刻感受到反馈
                double progress = 1.0 - Math.pow(1.0 - t, 3);
                drawBorderProgress(progress);
                if (t >= 1.0) {
                    stop();
                    borderTimer = null;
                }
            }
        };
        borderTimer.start();
    }

    /**
     * 远程同步到达，完成边缘动画闭环后停止。
     */
    public void completeBorderAnimation() {
        if (borderTimer != null) {
            borderTimer.stop();
        }
        // 直接画满一圈，然后淡出
        drawBorderProgress(1.0);
        FadeTransition fadeOut = new FadeTransition(Duration.millis(600), borderCanvas);
        fadeOut.setFromValue(1.0);
        fadeOut.setToValue(0.0);
        fadeOut.setOnFinished(e -> {
            borderCanvas.setOpacity(1.0);
            clearBorderCanvas();
        });
        fadeOut.play();
    }

    /**
     * 发送失败，停止动画并清除画布。
     */
    public void cancelBorderAnimation() {
        if (borderTimer != null) {
            borderTimer.stop();
            borderTimer = null;
        }
        clearBorderCanvas();
    }

    /**
     * 在 canvas 上绘制顺时针边缘进度条（从左上角开始）。
     * 将圆角矩形周长按 progress 比例绘制。
     */
    private void drawBorderProgress(double progress) {
        double w = borderCanvas.getWidth();
        double h = borderCanvas.getHeight();
        if (w <= 0 || h <= 0)
            return;

        GraphicsContext gc = borderCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, w, h);

        boolean isDark = ThemeService.getInstance().isDarkTheme();
        Color color = isDark ? Color.web("#00D4FF") : Color.web("#0969DA");
        gc.setStroke(color);
        gc.setLineWidth(BORDER_LINE_WIDTH);
        gc.setLineCap(javafx.scene.shape.StrokeLineCap.ROUND);

        double r = BORDER_RADIUS;
        // 圆角矩形周长：4条直边 + 4个90°圆弧
        double straightTop = w - 2 * r;
        double straightRight = h - 2 * r;
        double straightBottom = w - 2 * r;
        double straightLeft = h - 2 * r;
        double arcLen = 0.5 * Math.PI * r; // 每个90°圆弧长度
        double totalPerimeter = straightTop + straightRight + straightBottom + straightLeft + 4 * arcLen;
        double drawLen = progress * totalPerimeter;

        // 起点：左上角圆弧的顶部中点（即 top-left corner 的弧顶）
        // 顺时针8段：topEdge, trArc, rightEdge, brArc, bottomEdge, blArc, leftEdge, tlArc
        double[] segLengths = {
                straightTop, arcLen, straightRight, arcLen,
                straightBottom, arcLen, straightLeft, arcLen
        };

        gc.beginPath();
        double remaining = drawLen;
        double cx = r, cy = BORDER_LINE_WIDTH / 2; // 起始点：top-left圆弧结束处

        gc.moveTo(cx, cy);

        for (int seg = 0; seg < 8 && remaining > 0; seg++) {
            double segLen = segLengths[seg];
            double draw = Math.min(remaining, segLen);
            double frac = draw / segLen;

            switch (seg) {
                case 0: // top edge: left to right
                    gc.lineTo(cx + draw, cy);
                    cx += draw;
                    break;
                case 1: // top-right arc
                    drawArcSegment(gc, w - r, r, r, -90, frac * 90);
                    cx = w - BORDER_LINE_WIDTH / 2;
                    cy = r - r * Math.cos(Math.toRadians(frac * 90));
                    break;
                case 2: // right edge: top to bottom
                    cx = w - BORDER_LINE_WIDTH / 2;
                    gc.moveTo(cx, r + (straightRight > 0 ? 0 : 0));
                    double rightStart = r;
                    gc.moveTo(cx, rightStart);
                    gc.lineTo(cx, rightStart + draw);
                    cy = rightStart + draw;
                    break;
                case 3: // bottom-right arc
                    drawArcSegment(gc, w - r, h - r, r, 0, frac * 90);
                    cx = w - r + r * Math.cos(Math.toRadians(90 - frac * 90));
                    cy = h - BORDER_LINE_WIDTH / 2;
                    break;
                case 4: // bottom edge: right to left
                    double bStartX = w - r;
                    gc.moveTo(bStartX, h - BORDER_LINE_WIDTH / 2);
                    gc.lineTo(bStartX - draw, h - BORDER_LINE_WIDTH / 2);
                    cx = bStartX - draw;
                    break;
                case 5: // bottom-left arc
                    drawArcSegment(gc, r, h - r, r, 90, frac * 90);
                    cx = BORDER_LINE_WIDTH / 2;
                    cy = h - r + r * Math.cos(Math.toRadians(frac * 90));
                    break;
                case 6: // left edge: bottom to top
                    double lStartY = h - r;
                    gc.moveTo(BORDER_LINE_WIDTH / 2, lStartY);
                    gc.lineTo(BORDER_LINE_WIDTH / 2, lStartY - draw);
                    cy = lStartY - draw;
                    break;
                case 7: // top-left arc
                    drawArcSegment(gc, r, r, r, 180, frac * 90);
                    break;
            }
            remaining -= draw;
        }
        gc.stroke();
    }

    /**
     * 在指定圆心绘制一段圆弧。
     */
    private void drawArcSegment(GraphicsContext gc, double centerX, double centerY,
            double radius, double startAngle, double sweepAngle) {
        int steps = Math.max(8, (int) (sweepAngle / 2));
        double startRad = Math.toRadians(startAngle);
        double sweepRad = Math.toRadians(sweepAngle);

        for (int i = 0; i <= steps; i++) {
            double angle = startRad + sweepRad * i / steps;
            double x = centerX + radius * Math.cos(angle);
            double y = centerY + radius * Math.sin(angle);
            if (i == 0) {
                gc.moveTo(x, y);
            } else {
                gc.lineTo(x, y);
            }
        }
    }

    private void clearBorderCanvas() {
        borderCanvas.getGraphicsContext2D().clearRect(0, 0, borderCanvas.getWidth(), borderCanvas.getHeight());
    }
}
