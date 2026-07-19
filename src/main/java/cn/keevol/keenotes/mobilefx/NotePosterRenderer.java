package cn.keevol.keenotes.mobilefx;

import cn.keevol.keenotes.mobilefx.utils.DateTimeUtil;
import javafx.scene.image.Image;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public final class NotePosterRenderer {
    public static final int EXPORT_WIDTH = 1080;
    public static final double ASPECT_RATIO = 9.0 / 16.0;
    private static final String POSTER_FONT_RESOURCE = "/fonts/MiSans-Regular.ttf";
    private static final Font BASE_POSTER_FONT = loadBasePosterFont();

    private static final int PAPER_RGB = 0xFDFCFB;
    private static final Color INK_TEXT = new Color(26, 24, 22);
    private static final Color FOOTER_TEXT = new Color(26, 24, 22, 88);
    private static final Color BADGE_FILL = new Color(0, 0, 0, 12);
    private static final Color BADGE_STROKE = new Color(0, 0, 0, 14);
    private static final Color DIVIDER = new Color(0, 0, 0, 15);

    private NotePosterRenderer() {
    }

    public static BufferedImage renderPosterImage(
            LocalCacheService.NoteData noteData,
            String hiddenMessage,
            PosterInkTheme inkTheme
    ) throws IOException {
        return renderPosterImage(noteData.content, formatPosterDate(noteData.createdAt), hiddenMessage, inkTheme, EXPORT_WIDTH);
    }

    public static BufferedImage renderPosterImage(
            String noteContent,
            String posterDate,
            String hiddenMessage,
            PosterInkTheme inkTheme,
            int width
    ) throws IOException {
        String content = noteContent == null ? "" : noteContent;
        String author = trimToNull(hiddenMessage);
        int contentLength = content.codePointCount(0, content.length());
        double scale = width / 390.0;

        int cardPadding = scaled(contentLength > 700 ? 28 : 34, scale);
        int contentVerticalPadding = scaled(resolveContentVerticalPadding(contentLength), scale);
        int footerVerticalPadding = scaled(contentLength > 700 ? 18 : 22, scale);
        int contentFontSize = scaled(resolveContentFontSize(contentLength), scale);
        int contentLineSpacing = scaled(resolveContentLineSpacing(contentLength), scale);
        int footerFontSize = scaled(11, scale);

        Font contentFont = posterFont(Font.BOLD, contentFontSize);
        Font footerFont = posterFont(Font.PLAIN, footerFontSize);
        Font badgeFont = posterFont(Font.BOLD, footerFontSize);

        BufferedImage measureImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D measureGraphics = measureImage.createGraphics();
        configureGraphics(measureGraphics);
        measureGraphics.setFont(contentFont);
        FontMetrics contentMetrics = measureGraphics.getFontMetrics();
        List<String> lines = wrapText(content, contentMetrics, width - cardPadding * 2);
        int lineAdvance = contentMetrics.getHeight() + contentLineSpacing;
        int textHeight = lines.isEmpty() ? contentMetrics.getHeight() : lines.size() * lineAdvance - contentLineSpacing;

        measureGraphics.setFont(footerFont);
        int footerTextHeight = measureGraphics.getFontMetrics().getHeight();
        measureGraphics.dispose();

        int footerHeight = footerTextHeight + footerVerticalPadding * 2;
        int dividerHeight = Math.max(1, scaled(1, scale));
        int minimumHeight = exportHeightFor(width);
        int requiredHeight = contentVerticalPadding * 2 + textHeight + dividerHeight + footerHeight;
        int height = Math.max(minimumHeight, requiredHeight);
        int extraVerticalSpace = height - requiredHeight;
        int textY = contentVerticalPadding + extraVerticalSpace / 2;
        int footerY = height - footerHeight;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        configureGraphics(g);

        int cornerRadius = scaled(32, scale);
        Shape posterShape = new RoundRectangle2D.Double(0, 0, width, height, cornerRadius, cornerRadius);
        g.setClip(posterShape);
        g.setColor(new Color(PAPER_RGB));
        g.fillRect(0, 0, width, height);

        paintPaperGrain(g, width, height, scale);
        paintInkTheme(g, inkTheme, contentLength, width, height);
        paintVignette(g, width, height);
        paintContent(g, lines, contentFont, contentMetrics, cardPadding, textY, lineAdvance);
        paintFooter(g, author, posterDate, footerFont, badgeFont, cardPadding, footerY, footerHeight, footerVerticalPadding, width);

        g.setColor(DIVIDER);
        g.fillRect(0, footerY, width, dividerHeight);

        g.setClip(null);
        g.setStroke(new BasicStroke(Math.max(1f, (float) (0.8 * scale))));
        g.setColor(new Color(255, 255, 255, 210));
        g.draw(posterShape);
        g.dispose();
        return image;
    }

    public static Image toFxImage(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return new Image(new ByteArrayInputStream(output.toByteArray()));
    }

    public static void writePng(BufferedImage image, File outputFile) throws IOException {
        ImageIO.write(image, "png", outputFile);
    }

    public static int exportHeightFor(int width) {
        return (int) Math.ceil(width / ASPECT_RATIO);
    }

    public static String formatPosterDate(String createdAt) {
        String local = DateTimeUtil.utcToLocalDisplay(createdAt);
        if (local != null && local.length() >= 10) {
            return local.substring(0, 10);
        }
        return local == null || local.isBlank() ? "" : local;
    }

    private static int resolveContentFontSize(int contentLength) {
        if (contentLength > 900) {
            return 16;
        }
        if (contentLength > 500) {
            return 18;
        }
        if (contentLength > 220) {
            return 20;
        }
        return 24;
    }

    private static int resolveContentLineSpacing(int contentLength) {
        if (contentLength > 700) {
            return 8;
        }
        if (contentLength > 300) {
            return 10;
        }
        return 12;
    }

    private static int resolveContentVerticalPadding(int contentLength) {
        if (contentLength > 900) {
            return 30;
        }
        if (contentLength > 500) {
            return 38;
        }
        return 52;
    }

    private static void configureGraphics(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    }

    private static Font posterFont(int style, int size) {
        return BASE_POSTER_FONT.deriveFont(style, (float) size);
    }

    private static Font loadBasePosterFont() {
        try (InputStream input = NotePosterRenderer.class.getResourceAsStream(POSTER_FONT_RESOURCE)) {
            if (input != null) {
                return Font.createFont(Font.TRUETYPE_FONT, input);
            }
        } catch (FontFormatException | IOException e) {
            // Fall through to platform sans-serif if the bundled font cannot be loaded.
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    }

    private static void paintPaperGrain(Graphics2D g, int width, int height, double scale) {
        int step = Math.max(1, scaled(7, scale));
        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                int seed = Math.round(x * 31 + y * 17);
                int alpha = seed % 5 == 0 ? 7 : 4;
                g.setColor(new Color(0, 0, 0, alpha));
                g.fillRect(x, y, Math.max(1, scaled(1, scale)), Math.max(1, scaled(1, scale)));
            }
        }
    }

    private static void paintInkTheme(Graphics2D g, PosterInkTheme theme, int contentLength, int width, int height) throws IOException {
        try (InputStream input = NotePosterRenderer.class.getResourceAsStream(theme.getResourcePath())) {
            if (input == null) {
                return;
            }
            BufferedImage ink = ImageIO.read(input);
            if (ink == null) {
                return;
            }
            Composite previousComposite = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, theme.opacityFor(contentLength)));
            int inkHeight = exportHeightFor(width);
            int y = height - inkHeight;
            g.drawImage(ink, 0, y, width, inkHeight, null);
            g.setComposite(previousComposite);
        }
    }

    private static void paintVignette(Graphics2D g, int width, int height) {
        LinearGradientPaint edgeShade = new LinearGradientPaint(
                0, 0, width, height,
                new float[]{0f, 0.5f, 1f},
                new Color[]{new Color(255, 255, 255, 0), new Color(255, 255, 255, 0), new Color(0, 0, 0, 8)}
        );
        g.setPaint(edgeShade);
        g.fillRect(0, 0, width, height);
    }

    private static void paintContent(Graphics2D g, List<String> lines, Font font, FontMetrics metrics, int x, int y, int lineAdvance) {
        g.setFont(font);
        g.setColor(INK_TEXT);
        int baseline = y + metrics.getAscent();
        if (lines.isEmpty()) {
            return;
        }
        for (String line : lines) {
            g.drawString(line, x, baseline);
            baseline += lineAdvance;
        }
    }

    private static void paintFooter(
            Graphics2D g,
            String author,
            String posterDate,
            Font footerFont,
            Font badgeFont,
            int cardPadding,
            int footerY,
            int footerHeight,
            int footerVerticalPadding,
            int width
    ) {
        String leftText = author == null ? posterDate : author + " · " + posterDate;
        g.setFont(footerFont);
        FontMetrics footerMetrics = g.getFontMetrics();
        int baseline = footerY + footerVerticalPadding + footerMetrics.getAscent();

        g.setFont(badgeFont);
        FontMetrics badgeMetrics = g.getFontMetrics();
        String badgeText = "KeeNotes";
        int badgeHorizontalPadding = Math.max(10, badgeMetrics.getHeight() / 2);
        int badgeVerticalPadding = Math.max(5, badgeMetrics.getHeight() / 4);
        int badgeWidth = badgeMetrics.stringWidth(badgeText) + badgeHorizontalPadding * 2;
        int badgeHeight = badgeMetrics.getHeight() + badgeVerticalPadding * 2;
        int badgeX = width - cardPadding - badgeWidth;
        int badgeY = footerY + (footerHeight - badgeHeight) / 2;
        int badgeArc = badgeHeight;

        g.setColor(BADGE_FILL);
        g.fillRoundRect(badgeX, badgeY, badgeWidth, badgeHeight, badgeArc, badgeArc);
        g.setColor(BADGE_STROKE);
        g.drawRoundRect(badgeX, badgeY, badgeWidth, badgeHeight, badgeArc, badgeArc);
        g.setColor(new Color(26, 24, 22, 148));
        g.drawString(badgeText, badgeX + badgeHorizontalPadding, badgeY + badgeVerticalPadding + badgeMetrics.getAscent());

        g.setFont(footerFont);
        g.setColor(FOOTER_TEXT);
        int availableLeftWidth = Math.max(0, badgeX - cardPadding - Math.max(12, footerMetrics.getHeight()));
        g.drawString(ellipsize(leftText, footerMetrics, availableLeftWidth), cardPadding, baseline);
    }

    private static List<String> wrapText(String text, FontMetrics metrics, int maxWidth) {
        List<String> lines = new ArrayList<>();
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] paragraphs = normalized.split("\n", -1);
        for (int i = 0; i < paragraphs.length; i++) {
            wrapParagraph(paragraphs[i], metrics, maxWidth, lines);
            if (i < paragraphs.length - 1) {
                lines.add("");
            }
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private static void wrapParagraph(String paragraph, FontMetrics metrics, int maxWidth, List<String> lines) {
        if (paragraph.isEmpty()) {
            return;
        }

        StringBuilder current = new StringBuilder();
        for (int offset = 0; offset < paragraph.length(); ) {
            int codePoint = paragraph.codePointAt(offset);
            String next = new String(Character.toChars(codePoint));
            String candidate = current + next;
            if (metrics.stringWidth(candidate) <= maxWidth || current.isEmpty()) {
                current.append(next);
            } else {
                lines.add(current.toString());
                current.setLength(0);
                current.append(next);
            }
            offset += Character.charCount(codePoint);
        }

        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
    }

    private static String ellipsize(String text, FontMetrics metrics, int maxWidth) {
        if (text == null || metrics.stringWidth(text) <= maxWidth) {
            return text == null ? "" : text;
        }
        String ellipsis = "...";
        StringBuilder result = new StringBuilder(text);
        while (!result.isEmpty() && metrics.stringWidth(result + ellipsis) > maxWidth) {
            result.setLength(result.length() - 1);
        }
        return result + ellipsis;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static int scaled(double value, double scale) {
        return Math.max(1, (int) Math.round(value * scale));
    }
}
