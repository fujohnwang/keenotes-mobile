package cn.keevol.keenotes.mobilefx;

import javafx.animation.AnimationTimer;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.util.Duration;

/**
 * Individual note card component for displaying a single note
 * Click card to copy entire content to clipboard
 * Select text with mouse, right-click or Ctrl+C to copy selection
 */
public class NoteCardView extends StackPane {
    
    private final LocalCacheService.NoteData noteData;
    private final Label copiedPopup;
    private final TextArea contentArea;
    private final SettingsService settings;
    private final Text textMeasure; // Hidden text node for measuring height
    
    // Border progress animation
    private Canvas borderCanvas;
    private AnimationTimer borderTimer;
    private long borderStartNanos;
    private static final double BORDER_ANIM_DURATION = 10.0; // max seconds for one full loop
    private static final double BORDER_LINE_WIDTH = 3.0;
    private static final double BORDER_RADIUS = 12.0;
    
    public NoteCardView(LocalCacheService.NoteData noteData) {
        this.noteData = noteData;
        this.settings = SettingsService.getInstance();
        
        getStyleClass().add("search-result-card");
        
        // Listen to theme changes
        ThemeService.getInstance().currentThemeProperty().addListener((obs, oldTheme, newTheme) -> {
            javafx.application.Platform.runLater(this::updateThemeColors);
        });
        
        // Listen to font size changes
        settings.noteFontSizeProperty().addListener((obs, oldSize, newSize) -> {
            javafx.application.Platform.runLater(() -> updateFontSize(newSize.intValue()));
        });
        
        // Main content container
        VBox contentBox = new VBox(8);
        contentBox.setPadding(new Insets(16));
        
        // Header row: date and channel
        HBox headerRow = new HBox(12);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        
        Label dateLabel = new Label(noteData.createdAt);
        dateLabel.getStyleClass().add("note-date");
        
        // Channel/source label
        String channelText = (noteData.channel != null && !noteData.channel.isEmpty()) 
            ? noteData.channel 
            : "default";
        Label channelLabel = new Label("• " + channelText);
        channelLabel.getStyleClass().add("note-channel");
        
        boolean isDark = ThemeService.getInstance().isDarkTheme();
        String secondaryColor = isDark ? "#8B949E" : "#57606A";
        channelLabel.setStyle("-fx-text-fill: " + secondaryColor + "; -fx-font-size: 12px;");
        
        headerRow.getChildren().addAll(dateLabel, channelLabel);
        
        // Full content using TextArea (read-only, selectable)
        contentArea = new TextArea(noteData.content);
        contentArea.setEditable(false);
        contentArea.setWrapText(true);
        contentArea.getStyleClass().add("note-content-area");
        
        String textColor = isDark ? "#E6EDF3" : "#24292F";
        int fontSize = settings.getNoteFontSize();
        contentArea.setStyle(
            "-fx-control-inner-background: transparent; " +
            "-fx-background-color: transparent; " +
            "-fx-text-fill: " + textColor + "; " +
            "-fx-font-size: " + fontSize + "px; " +
            "-fx-border-width: 0; " +
            "-fx-focus-color: transparent; " +
            "-fx-faint-focus-color: transparent; " +
            "-fx-cursor: hand;"
        );
        
        // Disable scrollbars completely
        contentArea.setScrollTop(0);
        contentArea.setScrollLeft(0);
        
        // Create hidden Text node for accurate height measurement
        textMeasure = new Text();
        textMeasure.setFont(Font.font("MiSans", fontSize)); // Use same font family
        textMeasure.setWrappingWidth(500); // Will be updated based on actual width
        textMeasure.textProperty().bind(contentArea.textProperty());
        
        // Bind TextArea height to Text measurement
        textMeasure.layoutBoundsProperty().addListener((obs, oldBounds, newBounds) -> {
            double textHeight = newBounds.getHeight();
            double padding = 20; // Minimal padding
            double height = Math.max(30, textHeight + padding);
            contentArea.setPrefHeight(height);
            contentArea.setMinHeight(height);
            // Re-hide scrollbars after height change
            javafx.application.Platform.runLater(this::hideScrollBars);
        });
        
        // Update wrapping width when card width changes
        widthProperty().addListener((obs, oldWidth, newWidth) -> {
            if (newWidth.doubleValue() > 64) { // Ensure we have valid width
                // Card padding (16*2) + content box padding (16*2) = 64
                double wrappingWidth = newWidth.doubleValue() - 64;
                textMeasure.setWrappingWidth(wrappingWidth);
            }
        });
        
        // Initial wrapping width setup after layout
        javafx.application.Platform.runLater(() -> {
            if (getWidth() > 64) {
                textMeasure.setWrappingWidth(getWidth() - 64);
            }
            hideScrollBars();
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
        
        // Click on TextArea (when no text selected) to copy all
        contentArea.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 1) {
                String selected = contentArea.getSelectedText();
                if (selected == null || selected.isEmpty()) {
                    handleCopy();
                }
            }
        });
        
        contentBox.getChildren().addAll(headerRow, contentArea);
        
        // Copied popup (positioned at top-right)
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
            "-fx-font-size: 11px;"
        );
        copiedPopup.setVisible(false);
        copiedPopup.setOpacity(0);
        StackPane.setAlignment(copiedPopup, Pos.TOP_RIGHT);
        StackPane.setMargin(copiedPopup, new Insets(8, 8, 0, 0));
        
        getChildren().addAll(contentBox, copiedPopup);
        
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
        
        // Click anywhere on card (outside TextArea) to copy all
        setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                handleCopy();
            }
        });
        setCursor(javafx.scene.Cursor.HAND);
        
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
     * Hide scrollbars from TextArea
     */
    private void hideScrollBars() {
        javafx.scene.Node scrollPane = contentArea.lookup(".scroll-pane");
        if (scrollPane != null) {
            scrollPane.setStyle("-fx-background-color: transparent;");
        }
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
        int fontSize = settings.getNoteFontSize();
        contentArea.setStyle(
            "-fx-control-inner-background: transparent; " +
            "-fx-background-color: transparent; " +
            "-fx-text-fill: " + textColor + "; " +
            "-fx-font-size: " + fontSize + "px; " +
            "-fx-border-width: 0; " +
            "-fx-focus-color: transparent; " +
            "-fx-faint-focus-color: transparent; " +
            "-fx-cursor: hand;"
        );
        
        // Update copied popup colors
        copiedPopup.setStyle(
            "-fx-background-color: " + popupBg + "; " +
            "-fx-text-fill: " + primaryColor + "; " +
            "-fx-padding: 4 8; " +
            "-fx-background-radius: 4; " +
            "-fx-font-size: 11px; " +
            "-fx-font-weight: bold;"
        );
    }
    
    /**
     * Update font size for content area
     */
    private void updateFontSize(int fontSize) {
        boolean isDark = ThemeService.getInstance().isDarkTheme();
        String textColor = isDark ? "#E6EDF3" : "#24292F";
        contentArea.setStyle(
            "-fx-control-inner-background: transparent; " +
            "-fx-background-color: transparent; " +
            "-fx-text-fill: " + textColor + "; " +
            "-fx-font-size: " + fontSize + "px; " +
            "-fx-border-width: 0; " +
            "-fx-focus-color: transparent; " +
            "-fx-faint-focus-color: transparent; " +
            "-fx-cursor: hand;"
        );
        // Update text measure font
        textMeasure.setFont(Font.font("MiSans", fontSize));
    }

    /**
     * 启动边缘色条动画：从左上角顺时针沿圆角矩形边缘增长。
     * 色条颜色使用主题的 primary color（dark: #00D4FF, light: #0969DA）。
     */
    public void startBorderAnimation() {
        borderStartNanos = System.nanoTime();
        if (borderTimer != null) borderTimer.stop();
        borderTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                double elapsed = (now - borderStartNanos) / 1_000_000_000.0;
                double t = Math.min(elapsed / BORDER_ANIM_DURATION, 1.0);
                // ease-out cubic: 前段快、后段慢，让用户立刻感受到反馈
                double progress = 1.0 - Math.pow(1.0 - t, 3);
                drawBorderProgress(progress);
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
        if (w <= 0 || h <= 0) return;

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
