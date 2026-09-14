package cn.keevol.keenotes.mobilefx;

import org.junit.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;

import static org.junit.Assert.assertTrue;

public class NotePosterRendererTest {
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
