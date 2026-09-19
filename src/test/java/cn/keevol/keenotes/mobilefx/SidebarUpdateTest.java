package cn.keevol.keenotes.mobilefx;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.util.Duration;
import okhttp3.*;
import org.junit.*;

import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Opt-in native JavaFX regression checks: -Dkeenotes.fx.tests=true. No real network or user data. */
public class SidebarUpdateTest {
    private static String originalHome;
    private static boolean toolkitStarted;

    @BeforeClass
    public static void startToolkit() throws Exception {
        Assume.assumeTrue("Requires a native JavaFX display", Boolean.getBoolean("keenotes.fx.tests"));
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createTempDirectory("keenotes-sidebar-test-").toString());
        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); });
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        toolkitStarted = true;
    }

    @AfterClass
    public static void stopToolkit() throws Exception {
        try {
            if (toolkitStarted) {
                fx(() -> { ServiceManager.getInstance().shutdown(); return null; });
                Platform.exit();
            }
        } finally {
            if (originalHome != null) System.setProperty("user.home", originalHome);
        }
    }

    @Test
    public void noticeStaysInsideWindowAndSeparateFromCharacters() throws Exception {
        fx(() -> {
            SidebarView sidebar = new SidebarView(mode -> {});
            sidebar.setMinWidth(250);
            sidebar.setPrefWidth(250);
            sidebar.setMaxWidth(250);
            BorderPane root = new BorderPane();
            root.setLeft(sidebar);
            Scene scene = new Scene(root, 1000, 800);
            try {
                Node characters = sidebar.lookup(".sidebar-companions");
                Node notice = sidebar.lookup(".update-notification");
                ScrollPane navigation = (ScrollPane) sidebar.lookup(".sidebar-navigation-scroll");
                SettingsService settings = SettingsService.getInstance();
                for (String theme : new String[]{"dark", "light"}) {
                    scene.getStylesheets().setAll(resource("common"), resource(theme));
                    for (boolean showCharacters : new boolean[]{true, false}) {
                        settings.showSidebarCharactersProperty().set(showCharacters);
                        for (boolean showOverview : new boolean[]{true, false}) {
                            settings.showOverviewCardProperty().set(showOverview);
                            for (DesktopMainView.ViewMode mode : DesktopMainView.ViewMode.values()) {
                                sidebar.setSelectedMode(mode);
                                for (double height : new double[]{800, 570, 600, 1000}) {
                                    root.resize(1000, height);
                                    root.applyCss(); root.layout();
                                    sidebar.showUpdateNotification("1.8.7", "https://example.com/release");
                                    root.layout();
                                    Bounds noticeBounds = notice.localToScene(notice.getLayoutBounds());
                                    Bounds charactersBounds = characters.localToScene(characters.getLayoutBounds());
                                    String context = theme + "/" + mode + "/" + height + "/characters=" + showCharacters;
                                    assertTrue(context, notice.isVisible() && notice.isManaged());
                                    assertTrue(context + " update clipped", noticeBounds.getMaxY() <= height);
                                    assertTrue(context + " update overlaps characters", charactersBounds.getMaxY() <= noticeBounds.getMinY());
                                    assertTrue(context + " navigation overlaps characters", navigation.localToScene(navigation.getLayoutBounds()).getMaxY() <= charactersBounds.getMinY());
                                    if (height == 1000) {
                                        assertTrue(context + " unnecessary navigation scrolling",
                                                navigation.getContent().getLayoutBounds().getHeight() <= navigation.getViewportBounds().getHeight() + 1);
                                    }
                                }
                            }
                        }
                    }
                }
            } finally {
                sidebar.dispose();
                root.setLeft(null);
            }
            return null;
        });
    }

    @Test
    public void retriesFailureThenNotifiesOnceOnFxThread() throws Exception {
        AtomicInteger requests = new AtomicInteger(), callbacks = new AtomicInteger();
        CountDownLatch notified = new CountDownLatch(1);
        OkHttpClient client = client(chain -> response(chain, requests.incrementAndGet() < 3 ? 503 : 200, "1.8.7"));
        UpdateCheckService service = fx(() -> {
            UpdateCheckService result = service("1.8.6", client, 10, 30);
            result.setUpdateListener((version, url) -> {
                assertTrue(Platform.isFxApplicationThread());
                assertEquals("1.8.7", version);
                callbacks.incrementAndGet();
                notified.countDown();
            });
            result.checkForUpdates();
            result.checkForUpdates();
            return result;
        });
        try {
            assertTrue(notified.await(5, TimeUnit.SECONDS));
            Thread.sleep(200);
            assertEquals(3, requests.get());
            assertEquals(1, callbacks.get());
        } finally { close(service); }
    }

    @Test
    public void givesUpAfterThreeRetries() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch fourth = new CountDownLatch(4);
        OkHttpClient client = client(chain -> {
            requests.incrementAndGet(); fourth.countDown();
            return response(chain, 503, "1.8.7");
        });
        UpdateCheckService service = fx(() -> service("1.8.6", client, 10, 30));
        try {
            assertTrue(fourth.await(5, TimeUnit.SECONDS));
            Thread.sleep(200);
            assertEquals(4, requests.get());
        } finally { close(service); }
    }

    @Test
    public void successfulCurrentVersionDoesNotRetryAndDevDoesNotRequest() throws Exception {
        for (String version : new String[]{"1.8.7", "dev"}) {
            AtomicInteger requests = new AtomicInteger(), callbacks = new AtomicInteger();
            OkHttpClient client = client(chain -> { requests.incrementAndGet(); return response(chain, 200, "1.8.7"); });
            UpdateCheckService service = fx(() -> {
                UpdateCheckService result = service(version, client, 10, 30);
                result.setUpdateListener((v, url) -> callbacks.incrementAndGet());
                return result;
            });
            try {
                Thread.sleep(300);
                assertEquals("dev".equals(version) ? 0 : 1, requests.get());
                assertEquals(0, callbacks.get());
            } finally { close(service); }
        }
    }

    @Test
    public void closeCancelsInitialDelayAndPendingRetry() throws Exception {
        for (boolean pendingRetry : new boolean[]{false, true}) {
            AtomicInteger requests = new AtomicInteger();
            CountDownLatch firstRequest = new CountDownLatch(1);
            OkHttpClient client = client(chain -> {
                requests.incrementAndGet(); firstRequest.countDown();
                return response(chain, 503, "1.8.7");
            });
            UpdateCheckService service = fx(() -> service("1.8.6", client, pendingRetry ? 10 : 300, 300));
            try {
                if (pendingRetry) {
                    assertTrue(firstRequest.await(5, TimeUnit.SECONDS));
                    Thread.sleep(70);
                }
                close(service);
                fx(() -> { service.checkForUpdates(); return null; });
                Thread.sleep(400);
                assertEquals(pendingRetry ? 1 : 0, requests.get());
                assertTrue(client.dispatcher().executorService().isShutdown());
            } finally { close(service); }
        }
    }

    @Test
    public void closeCancelsActiveHttpCallWithoutNotifying() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Call> call = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();
        OkHttpClient client = client(chain -> {
            call.set(chain.call()); entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            return response(chain, 200, "1.8.7");
        });
        UpdateCheckService service = fx(() -> {
            UpdateCheckService result = service("1.8.6", client, 10, 30);
            result.setUpdateListener((version, url) -> callbacks.incrementAndGet());
            return result;
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            close(service);
            assertTrue(call.get().isCanceled());
            release.countDown();
            Thread.sleep(200);
            assertEquals(0, callbacks.get());
            assertEquals(0, client.connectionPool().connectionCount());
        } finally { release.countDown(); close(service); }
    }

    private static UpdateCheckService service(String version, OkHttpClient client, int initialMs, int retryMs) {
        UpdateCheckService service = new UpdateCheckService(version, client, Duration.millis(initialMs), Duration.millis(retryMs));
        service.checkForUpdates();
        return service;
    }

    private static OkHttpClient client(Interceptor interceptor) {
        return new OkHttpClient.Builder().addInterceptor(interceptor).build();
    }

    private static Response response(Interceptor.Chain chain, int status, String version) {
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("test").body(ResponseBody.create(
                        "{\"version\":\"" + version + "\",\"url\":\"https://example.com/release\"}",
                        MediaType.get("application/json"))).build();
    }

    private static void close(UpdateCheckService service) throws Exception {
        fx(() -> { service.close(); return null; });
    }

    private static String resource(String theme) {
        return SidebarUpdateTest.class.getResource("/styles/" + theme + ".css").toExternalForm();
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }
}
