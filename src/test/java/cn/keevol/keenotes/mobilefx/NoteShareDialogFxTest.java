package cn.keevol.keenotes.mobilefx;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.stage.Screen;
import org.junit.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;

import static org.junit.Assert.*;

/** Run alone with -Dtest=NoteShareDialogFxTest -Dkeenotes.poster.fx.tests=true. */
public class NoteShareDialogFxTest {
    private static String originalHome;
    private static boolean started;

    @BeforeClass public static void start() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("keenotes.poster.fx.tests"));
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createTempDirectory("keenotes-poster-fx-").toString());
        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); });
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        started = true;
    }

    @AfterClass public static void stop() {
        if (started) {
            AppExecutors.shutdown();
            Platform.exit();
        }
        if (originalHome != null) System.setProperty("user.home", originalHome);
    }

    @Test public void manualCarouselExportsCurrentPaletteAndReopensOnPaper() throws Exception {
        var note = new LocalCacheService.NoteData(2,
                "真正重要的，\n不是拥有多少答案，\n而是能否提出\n更好的问题。\n\n让记录成为思考的起点，\n让时间留下自己的判断。",
                "desktop", "2026-10-11T01:41:00Z", null);
        NoteShareDialog dialog = fx(() -> {
            NoteShareDialog result = new NoteShareDialog(null, note);
            result.show();
            return result;
        });
        try {
            awaitReady(dialog);
            assertTheme(dialog, PosterColorTheme.PAPER);
            for (PosterColorTheme palette : PosterColorTheme.values()) {
                click(dialog, "poster-theme-" + palette.name().toLowerCase(java.util.Locale.ROOT));
                awaitReady(dialog);
                assertTheme(dialog, palette);
                BufferedImage expected = NotePosterRenderer.renderPosterImage(note, "", PosterInkTheme.ZHU, palette);
                fx(() -> {
                    ImageView image = (ImageView) dialog.getDialogPane().lookup(".image-view");
                    assertEquals(expected.getRGB(540, 55), image.getImage().getPixelReader().getArgb(540, 55));
                    Files.createDirectories(Path.of("target/poster-carousel"));
                    ImageIO.write(expected, "png", Path.of("target/poster-carousel/" + palette.name() + ".png").toFile());
                    saveDialogSnapshot(dialog, palette.name() + "-dialog");
                    ScrollPane scroll = (ScrollPane) dialog.getDialogPane().lookup(".scroll-pane");
                    var controls = dialog.getDialogPane().lookup("#poster-theme-name").getParent();
                    assertFalse(scroll.getContent().lookupAll(".button").contains(button(dialog, "poster-theme-next")));
                    assertTrue(controls.localToScene(controls.getBoundsInLocal()).getMinY()
                            >= scroll.localToScene(scroll.getBoundsInLocal()).getMaxY());
                    return null;
                });
            }
            click(dialog, "poster-theme-next"); awaitReady(dialog);
            assertTheme(dialog, PosterColorTheme.PAPER);
            click(dialog, "poster-theme-previous"); awaitReady(dialog);
            assertTheme(dialog, PosterColorTheme.PINE);
            fx(() -> {
                dialog.getDialogPane().lookupAll(".button").stream().filter(node -> node instanceof Button b && b.getText().equals("换一换"))
                        .map(node -> (Button) node).findFirst().orElseThrow().fire();
                return null;
            });
            awaitReady(dialog);
            assertTheme(dialog, PosterColorTheme.PINE);
        } finally {
            fx(() -> { dialog.close(); return null; });
        }
        NoteShareDialog reopened = fx(() -> { var result = new NoteShareDialog(null, note); result.show(); return result; });
        try { awaitReady(reopened); assertTheme(reopened, PosterColorTheme.PAPER); }
        finally { fx(() -> { reopened.close(); return null; }); }
    }

    @Test public void longPosterScrollsWithoutMovingCarousel() throws Exception {
        var note = new LocalCacheService.NoteData(3, "长文仍然可以完整阅读，底部配色控件保持可用。\n".repeat(40),
                "desktop", "2026-10-11T01:41:00Z", null);
        NoteShareDialog dialog = fx(() -> { var result = new NoteShareDialog(null, note); result.show(); return result; });
        try {
            awaitReady(dialog);
            fx(() -> {
                ScrollPane scroll = (ScrollPane) dialog.getDialogPane().lookup(".scroll-pane");
                var controls = button(dialog, "poster-theme-next").getParent();
                var before = controls.localToScene(controls.getBoundsInLocal());
                assertTrue(scroll.getContent().getLayoutBounds().getHeight() > scroll.getViewportBounds().getHeight());
                assertPosterAndControlsCentered(dialog);
                scroll.setVvalue(1);
                dialog.getDialogPane().layout();
                assertPosterAndControlsCentered(dialog);
                assertEquals(before.getMinY(), controls.localToScene(controls.getBoundsInLocal()).getMinY(), 0.1);
                assertFalse(button(dialog, "poster-theme-next").isDisabled());
                return null;
            });
            click(dialog, "poster-theme-klein"); awaitReady(dialog);
            assertTheme(dialog, PosterColorTheme.KLEIN);
            fx(() -> {
                var scroll = (ScrollPane) dialog.getDialogPane().lookup(".scroll-pane");
                scroll.setVvalue(0);
                dialog.getDialogPane().layout();
                assertPosterAndControlsCentered(dialog);
                saveDialogSnapshot(dialog, "LONG-dialog");
                return null;
            });
        } finally {
            fx(() -> { dialog.close(); return null; });
        }
    }

    @Test public void windowUsesScreenHeightAndPreviewAdaptsToRemainingSpace() throws Exception {
        var note = new LocalCacheService.NoteData(4, "一段思考。", "desktop", "2026-10-11T01:41:00Z", null);
        NoteShareDialog dialog = fx(() -> { var result = new NoteShareDialog(null, note); result.show(); return result; });
        try {
            awaitReady(dialog);
            fx(() -> {
                var screen = Screen.getPrimary().getVisualBounds();
                assertEquals("Outer dialog height must be 80% of usable screen height",
                        screen.getHeight() * 0.8, dialog.getHeight(), 2);
                assertTrue(dialog.getY() >= screen.getMinY());
                assertTrue(dialog.getY() + dialog.getHeight() <= screen.getMaxY() + 1);
                var pane = dialog.getDialogPane();
                ScrollPane scroll = (ScrollPane) pane.lookup(".scroll-pane");
                ImageView image = (ImageView) pane.lookup(".image-view");
                double previousWidth = 0;
                // Exercise layout at several available heights without changing display resolution.
                for (double height : new double[]{540, 760, 1100}) {
                    pane.resize(560, height);
                    pane.applyCss();
                    pane.layout();
                    scroll.layout();
                    var viewport = scroll.getViewportBounds();
                    assertTrue("Preview should grow with available height", image.getFitWidth() > previousWidth);
                    assertTrue("Short poster must fit vertically", image.getBoundsInParent().getHeight() <= viewport.getHeight() + 1);
                    assertTrue("Poster must fit horizontally", image.getBoundsInParent().getWidth() <= viewport.getWidth() + 1);
                    var controls = pane.lookup("#poster-theme-name").getParent();
                    assertTrue(controls.localToScene(controls.getBoundsInLocal()).getMaxY() <= pane.getHeight());
                    previousWidth = image.getFitWidth();
                }
                assertTrue("Preview area must no longer be capped at 590", scroll.getViewportBounds().getHeight() > 590);
                assertTrue("Preview width must no longer be capped at 390", image.getFitWidth() > 390);
                assertEquals("Only preview size changes, export keeps its resolution", 1080, image.getImage().getWidth(), 0);
                return null;
            });
        } finally {
            fx(() -> { dialog.close(); return null; });
        }
    }

    @Test public void loadingIndicatorDoesNotMoveStatusTextOrResizePoster() throws Exception {
        var note = new LocalCacheService.NoteData(5, "切换时，海报和状态文案都应保持稳定。", "desktop", "2026-10-11T01:41:00Z", null);
        NoteShareDialog dialog = fx(() -> { var result = new NoteShareDialog(null, note); result.show(); return result; });
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            awaitReady(dialog);
            double[] idle = fx(() -> statusAndPreviewLayout(dialog));
            // Hold the media executor so the loading layout can be checked deterministically.
            AppExecutors.media().submit(() -> {
                blocked.countDown();
                try { release.await(15, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            click(dialog, "poster-theme-next");
            fx(() -> {
                assertTrue(dialog.getDialogPane().lookup("#poster-status-progress").isVisible());
                assertArrayEquals("Loading must not move the status text or resize the preview", idle, statusAndPreviewLayout(dialog), 0.1);
                return null;
            });
            release.countDown();
            awaitReady(dialog);
            fx(() -> {
                assertFalse(dialog.getDialogPane().lookup("#poster-status-progress").isVisible());
                assertArrayEquals("Finishing must preserve the same layout", idle, statusAndPreviewLayout(dialog), 0.1);
                return null;
            });
        } finally {
            release.countDown();
            fx(() -> { dialog.close(); return null; });
        }
    }

    private static double[] statusAndPreviewLayout(NoteShareDialog dialog) {
        var pane = dialog.getDialogPane();
        pane.applyCss();
        pane.layout();
        var status = (javafx.scene.layout.StackPane) pane.lookup("#poster-status-row");
        var label = (Label) pane.lookup("#poster-status-label");
        var progress = pane.lookup("#poster-status-progress");
        var scroll = (ScrollPane) pane.lookup(".scroll-pane");
        var image = (ImageView) pane.lookup(".image-view");
        var statusBounds = status.localToScene(status.getBoundsInLocal());
        var labelBounds = label.localToScene(label.getBoundsInLocal());
        var scrollBounds = scroll.localToScene(scroll.getBoundsInLocal());
        assertTrue("Status belongs above the poster", statusBounds.getMaxY() <= scrollBounds.getMinY());
        assertEquals("The status text itself must be centered, regardless of spinner visibility",
                statusBounds.getCenterX(), labelBounds.getCenterX(), 0.6);
        var progressBounds = progress.localToScene(progress.getLayoutBounds());
        assertEquals("The spinner's layout box must keep a fixed gap beside the text",
                labelBounds.getMinX() - 10, progressBounds.getMaxX(), 0.6);
        assertTrue("The animated spinner must not overlap the text",
                progress.localToScene(progress.getBoundsInLocal()).getMaxX() < labelBounds.getMinX());
        assertPosterAndControlsCentered(dialog);
        return new double[]{statusBounds.getHeight(), labelBounds.getMinX(), labelBounds.getMinY(),
                scrollBounds.getMinY(), scrollBounds.getHeight(), image.getFitWidth()};
    }

    private static void assertPosterAndControlsCentered(NoteShareDialog dialog) {
        var pane = dialog.getDialogPane();
        var image = pane.lookup(".image-view");
        var label = pane.lookup("#poster-status-label");
        var controls = button(dialog, "poster-theme-next").getParent();
        double center = pane.localToScene(pane.getLayoutBounds()).getCenterX();
        assertEquals("The status text must align with the dialog center",
                center, label.localToScene(label.getLayoutBounds()).getCenterX(), 0.6);
        assertEquals("The poster must share that center even when a vertical scrollbar is visible",
                center, image.localToScene(image.getLayoutBounds()).getCenterX(), 0.6);
        assertEquals("The carousel must share the same center",
                center, controls.localToScene(controls.getLayoutBounds()).getCenterX(), 0.6);
    }

    private static void saveDialogSnapshot(NoteShareDialog dialog, String name) throws Exception {
        Files.createDirectories(Path.of("target/poster-carousel"));
        var screenshot = dialog.getDialogPane().snapshot(null, null);
        var png = new BufferedImage((int) screenshot.getWidth(), (int) screenshot.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++)
            png.setRGB(x, y, screenshot.getPixelReader().getArgb(x, y));
        ImageIO.write(png, "png", Path.of("target/poster-carousel/" + name + ".png").toFile());
    }

    private static void assertTheme(NoteShareDialog dialog, PosterColorTheme expected) throws Exception {
        assertEquals(expected.getLabel(), fx(() -> ((Label) dialog.getDialogPane().lookup("#poster-theme-name")).getText()));
    }

    private static Button button(NoteShareDialog dialog, String id) {
        return (Button) dialog.getDialogPane().lookup("#" + id);
    }

    private static void click(NoteShareDialog dialog, String id) throws Exception {
        fx(() -> { button(dialog, id).fire(); return null; });
    }

    private static void awaitReady(NoteShareDialog dialog) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (fx(() -> button(dialog, "poster-theme-next").isDisabled())) {
            assertTrue("Poster rendering did not finish", System.nanoTime() < deadline);
            Thread.sleep(30);
        }
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(15, TimeUnit.SECONDS);
    }
}
