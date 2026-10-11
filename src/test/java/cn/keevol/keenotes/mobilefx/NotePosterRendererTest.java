package cn.keevol.keenotes.mobilefx;

import org.junit.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.awt.Color;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class NotePosterRendererTest {
    @Test
    public void originalOverloadStillUsesPaperPalette() throws IOException {
        BufferedImage original = render("默认暖白，保持原来的排版😀", "作者");
        BufferedImage explicit = NotePosterRenderer.renderPosterImage(
                "默认暖白，保持原来的排版😀", "", "作者", PosterInkTheme.MEI, 390, PosterColorTheme.PAPER);
        assertEquals(0, countDifferentPixels(original, explicit));
    }

    @Test
    public void darkPalettesKeepTheirBackgroundWithEveryInkAndRenderLightText() throws IOException {
        for (PosterColorTheme palette : PosterColorTheme.values()) {
            if (!palette.isDark()) continue;
            for (PosterInkTheme ink : PosterInkTheme.values()) {
                BufferedImage empty = NotePosterRenderer.renderPosterImage("", "", "", ink, 390, palette);
                Color background = new Color(empty.getRGB(195, 20));
                assertTrue(palette + "/" + ink + " must not acquire the artwork's white paper",
                        colorDistance(background, palette.background()) < 24);
                BufferedImage text = NotePosterRenderer.renderPosterImage("思想的深度，来自持续追问。", "", "", ink, 390, palette);
                long lightTextPixels = 0;
                for (int y = 80; y < text.getHeight() - 100; y++) {
                    for (int x = 34; x < text.getWidth() - 34; x++) {
                        Color pixel = new Color(text.getRGB(x, y));
                        if (colorDistance(pixel, palette.text()) < 20 && text.getRGB(x, y) != empty.getRGB(x, y)) {
                            lightTextPixels++;
                        }
                    }
                }
                assertTrue(palette + "/" + ink + " needs legible light foreground text", lightTextPixels > 100);
            }
        }
    }

    @Test
    public void changingPalettePreservesLongContentGeometryAndTransparentCorners() throws IOException {
        String content = "长文需要完整保留，每一段都能继续阅读。\n".repeat(70);
        int expectedHeight = 0;
        for (PosterColorTheme palette : PosterColorTheme.values()) {
            BufferedImage poster = NotePosterRenderer.renderPosterImage(content, "2026-10-11 09:41", "作者", PosterInkTheme.ZHU, 390, palette);
            assertEquals(390, poster.getWidth());
            assertTrue(poster.getHeight() > NotePosterRenderer.exportHeightFor(390));
            if (expectedHeight == 0) expectedHeight = poster.getHeight();
            assertEquals(expectedHeight, poster.getHeight());
            assertEquals(0, poster.getRGB(0, 0) >>> 24);
        }
    }

    private static int colorDistance(Color first, Color second) {
        return Math.abs(first.getRed() - second.getRed()) + Math.abs(first.getGreen() - second.getGreen())
                + Math.abs(first.getBlue() - second.getBlue());
    }

    @Test
    public void rendersDifferentEmojiAsDifferentGlyphs() throws IOException {
        BufferedImage grinningFace = render("承担风险😀");
        BufferedImage smilingFace = render("承担风险😃");

        assertTrue(
                "Different emoji must not collapse to the same missing-glyph box",
                countDifferentPixels(grinningFace, smilingFace) > 0
        );
    }

    @Test
    public void rendersDifferentChinesePunctuationAsDifferentGlyphs() throws IOException {
        BufferedImage comma = render("中文标点，");
        BufferedImage fullStop = render("中文标点。");

        assertTrue(
                "Different Chinese punctuation must not collapse to the same missing-glyph box",
                countDifferentPixels(comma, fullStop) > 0
        );
    }

    @Test
    public void rendersDifferentEmojiInFooterAuthorAsDifferentGlyphs() throws IOException {
        BufferedImage grinningAuthor = render("正文", "作者😀");
        BufferedImage smilingAuthor = render("正文", "作者😃");

        assertTrue(
                "Different author emoji must not disappear from the footer",
                countDifferentPixels(grinningAuthor, smilingAuthor) > 0
        );
    }

    private static BufferedImage render(String content) throws IOException {
        return render(content, "");
    }

    private static BufferedImage render(String content, String author) throws IOException {
        return NotePosterRenderer.renderPosterImage(content, "", author, PosterInkTheme.MEI, 390);
    }

    private static long countDifferentPixels(BufferedImage first, BufferedImage second) {
        long differentPixels = 0;
        for (int y = 0; y < first.getHeight(); y++) {
            for (int x = 0; x < first.getWidth(); x++) {
                if (first.getRGB(x, y) != second.getRGB(x, y)) {
                    differentPixels++;
                }
            }
        }
        return differentPixels;
    }
}
