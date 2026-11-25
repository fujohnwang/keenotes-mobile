package cn.keevol.keenotes.mobilefx;

import cn.keevol.keenotes.mobilefx.utils.DateTimeUtil;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
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
import java.util.function.Consumer;

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
    private final Label keywordTag;
    private final Label semanticTag;
    private final HBox searchSourceTags;
    private final Button shareButton;
    private final SVGPath shareIcon;
    private final Button reviseButton;
    private final SVGPath reviseIcon;
    private final Consumer<LocalCacheService.NoteData> onReviseNote;

    private NoteSendBorder sendBorder;
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
        this(noteData, null);
    }

    public NoteCardView(LocalCacheService.NoteData noteData, Consumer<LocalCacheService.NoteData> onReviseNote) {
        this.noteData = noteData;
        this.onReviseNote = onReviseNote;
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

        reviseIcon = createReviseIcon();
        reviseButton = new Button();
        reviseButton.setGraphic(reviseIcon);
        reviseButton.setTooltip(new Tooltip("Revise as new note"));
        reviseButton.setFocusTraversable(false);
        boolean reviseEnabled = onReviseNote != null;
        reviseButton.setVisible(reviseEnabled);
        reviseButton.setManaged(reviseEnabled);
        reviseButton.setOnAction(e -> {
            e.consume();
            if (this.onReviseNote != null) {
                this.onReviseNote.accept(this.noteData);
            }
        });

        shareIcon = createShareIcon();
        shareButton = new Button();
        shareButton.setGraphic(shareIcon);
        shareButton.setTooltip(new Tooltip("Share as poster or video"));
        shareButton.setFocusTraversable(false);
        shareButton.setOnAction(e -> {
            e.consume();
            showShareDialog();
        });
        updateActionButtonStyles();

        keywordTag = new Label("KS");
        keywordTag.getStyleClass().add("search-source-tag");
        keywordTag.setTooltip(new Tooltip("Keyword search"));
        semanticTag = new Label("SS");
        semanticTag.getStyleClass().add("search-source-tag");
        semanticTag.setTooltip(new Tooltip("Semantic search"));
        searchSourceTags = new HBox(4, keywordTag, semanticTag);
        searchSourceTags.setAlignment(Pos.CENTER_RIGHT);
        setSearchSources(false, false);

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
            } else {
                cancelBorderAnimation();
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

        // Show copy feedback beside the header actions only while it is visible.
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
        copiedPopup.managedProperty().bind(copiedPopup.visibleProperty());
        copiedPopup.setOpacity(0);
        copiedPopup.setMouseTransparent(true);
        headerRow.getChildren().addAll(dateLabel, channelLabel, headerSpacer, searchSourceTags,
                copiedPopup, reviseButton, shareButton);

        StackPane contentCopyArea = new StackPane(contentArea);
        contentCopyArea.setMaxWidth(Double.MAX_VALUE);

        contentBox.getChildren().addAll(headerRow, contentCopyArea);

        getChildren().add(contentBox);

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

    public void setSearchSources(boolean keyword, boolean semantic) {
        keywordTag.setVisible(keyword);
        keywordTag.setManaged(keyword);
        semanticTag.setVisible(semantic);
        semanticTag.setManaged(semantic);
        searchSourceTags.setVisible(keyword || semantic);
        searchSourceTags.setManaged(keyword || semantic);
    }

    /**
     * Update card with new data (for cell reuse in ListView).
     * Also reapplies current theme/font in case they changed since construction.
     */
    public void update(LocalCacheService.NoteData newData) {
        if (noteData != newData) cancelBorderAnimation();
        this.noteData = newData;
        dateLabel.setText(DateTimeUtil.utcToLocalDisplay(newData.createdAt));
        String ch = (newData.channel != null && !newData.channel.isEmpty()) ? newData.channel : "default";
        channelLabel.setText("• " + ch);
        contentArea.setText(newData.content);
        updateThemeColors();
        updateContentTypography();
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
        updateActionButtonStyles();
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

    private SVGPath createReviseIcon() {
        SVGPath icon = new SVGPath();
        icon.setContent("M3 17.25 V21 H6.75 L17.81 9.94 L14.06 6.19 L3 17.25 Z M20.71 7.04 C21.1 6.65 21.1 6.02 20.71 5.63 L18.37 3.29 C17.98 2.9 17.35 2.9 16.96 3.29 L15.13 5.12 L18.88 8.87 L20.71 7.04 Z");
        icon.setScaleX(0.62);
        icon.setScaleY(0.62);
        return icon;
    }

    private void updateActionButtonStyles() {
        boolean isDark = ThemeService.getInstance().isDarkTheme();
        String iconColor = isDark ? "#8B949E" : "#57606A";
        String hoverBg = isDark ? "rgba(255, 255, 255, 0.08)" : "rgba(9, 105, 218, 0.08)";
        updateActionButtonStyle(reviseButton, reviseIcon, iconColor, hoverBg);
        updateActionButtonStyle(shareButton, shareIcon, iconColor, hoverBg);
    }

    private void updateActionButtonStyle(Button button, SVGPath icon, String iconColor, String hoverBg) {
        icon.setFill(Color.web(iconColor));
        button.setStyle(
                "-fx-background-color: transparent; " +
                        "-fx-background-radius: 16; " +
                        "-fx-padding: 4 6; " +
                        "-fx-min-width: 28; " +
                        "-fx-min-height: 28; " +
                        "-fx-cursor: hand;");
        button.setOnMouseEntered(e -> button.setStyle(
                "-fx-background-color: " + hoverBg + "; " +
                        "-fx-background-radius: 16; " +
                        "-fx-padding: 4 6; " +
                        "-fx-min-width: 28; " +
                        "-fx-min-height: 28; " +
                        "-fx-cursor: hand;"));
        button.setOnMouseExited(e -> updateActionButtonStyles());
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

    public void startBorderAnimation() {
        cancelBorderAnimation();
        Color color = ThemeService.getInstance().isDarkTheme() ? Color.web("#00D4FF") : Color.web("#0969DA");
        sendBorder = new NoteSendBorder(color);
        getChildren().add(sendBorder);
        sendBorder.start();
    }

    public void completeBorderAnimation() {
        if (sendBorder != null) sendBorder.finish(this::cancelBorderAnimation);
    }

    public void cancelBorderAnimation() {
        if (sendBorder == null) return;
        sendBorder.stop();
        getChildren().remove(sendBorder);
        sendBorder = null;
    }
}
