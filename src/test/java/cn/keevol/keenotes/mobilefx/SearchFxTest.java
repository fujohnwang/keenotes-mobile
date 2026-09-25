package cn.keevol.keenotes.mobilefx;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import cn.keevol.keenotes.mobilefx.search.*;
import org.junit.*;

import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.Assert.*;

/** Run alone with -Dtest=SearchFxTest -Dkeenotes.search.fx.tests=true. Uses an isolated user.home. */
public class SearchFxTest {
    private static String originalHome;
    private static boolean started;

    @BeforeClass public static void start() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("keenotes.search.fx.tests"));
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createTempDirectory("keenotes-search-fx-").toString());
        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); });
        assertTrue(ready.await(10, TimeUnit.SECONDS)); started = true;
    }

    @AfterClass public static void stop() throws Exception {
        if (started) {
            fx(() -> { ServiceManager.getInstance().shutdown(); return null; });
            Platform.exit();
        }
        if (originalHome != null) System.setProperty("user.home", originalHome);
    }

    @Test public void syncedNoteBecomesSearchableAndSettingsRenders() throws Exception {
        LocalSearchService service = fx(() -> ServiceManager.getInstance().getLocalSearchService());
        LocalCacheService cache = ServiceManager.getInstance().getLocalCacheService();
        fx(() -> service.saveConfiguration(EmbeddingConfig.disabled())).get(5, TimeUnit.SECONDS);
        cache.initialize();
        cache.batchInsertNotes(List.of(new LocalCacheService.NoteData(1, "incrementalsmokeprobe 数据库缓存笔记", "desktop", "2026-09-19", null)), false);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        LocalSearchService.ViewResult result;
        do {
            result = fx(() -> service.search("incrementalsmokeprobe", preview -> assertTrue(Platform.isFxApplicationThread()))).get(5, TimeUnit.SECONDS);
            if (!result.notes().isEmpty()) break;
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        assertEquals(1, result.notes().size());
        assertTrue(service.isCurrent(result));
        fx(() -> {
            SearchSettingsPane pane = new SearchSettingsPane();
            ScrollPane root = new ScrollPane(pane); root.setFitToWidth(true);
            Scene scene = new Scene(root, 750, 700);
            scene.getStylesheets().add(SearchFxTest.class.getResource("/styles/common.css").toExternalForm());
            scene.getStylesheets().add(SearchFxTest.class.getResource("/styles/dark.css").toExternalForm());
            root.applyCss(); root.layout();
            assertTrue(pane.getWidth() > 600);
            assertEquals(0, pane.lookupAll(".check-box").size());
            assertEquals(0, pane.lookupAll(".combo-box").size());
            var snapshot = root.snapshot(null, null);
            var image = new java.awt.image.BufferedImage((int) snapshot.getWidth(), (int) snapshot.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
                image.setRGB(x, y, snapshot.getPixelReader().getArgb(x, y));
            javax.imageio.ImageIO.write(image, "png", Path.of("target/search-settings-smoke.png").toFile());
            pane.dispose(); root.setContent(null);
            return null;
        });
    }

    @Test public void searchTagsFollowReusedCardSources() throws Exception {
        fx(() -> {
            var note = new LocalCacheService.NoteData(5901, "search tag", "desktop", "2026-09-24", null);
            var card = new NoteCardView(note);
            var tags = card.lookupAll(".search-source-tag").stream()
                    .map(node -> (javafx.scene.control.Label) node)
                    .collect(java.util.stream.Collectors.toMap(javafx.scene.control.Label::getText, label -> label));
            assertEquals(2, tags.size());
            var tagGroup = tags.get("KS").getParent();
            var header = (javafx.scene.layout.HBox) tagGroup.getParent();
            int firstAction = java.util.stream.IntStream.range(0, header.getChildren().size())
                    .filter(i -> header.getChildren().get(i) instanceof Button).findFirst().orElseThrow();
            assertEquals(firstAction - 1, header.getChildren().indexOf(tagGroup));
            card.setSearchSources(true, false);
            assertTrue(tags.get("KS").isManaged());
            assertFalse(tags.get("SS").isManaged());
            card.update(note);
            card.setSearchSources(true, true);
            assertTrue(tags.get("KS").isManaged());
            assertTrue(tags.get("SS").isManaged());
            card.setSearchSources(false, false);
            assertFalse(tags.get("KS").isManaged());
            assertFalse(tags.get("SS").isManaged());
            return null;
        });
    }

    @Test public void obsoleteSlowSemanticQueriesDoNotDelayKeywordPreviewsOrSavingSettings() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var requestExecutor = Executors.newCachedThreadPool();
        server.setExecutor(requestExecutor);
        Semaphore entered = new Semaphore(0);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/v1/embeddings", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (body.contains("query: ")) {
                entered.release();
                try { release.await(15, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            byte[] response = "{\"data\":[{\"index\":0,\"embedding\":[1,0]}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally { exchange.close(); }
        });
        server.start();
        LocalSearchService service = fx(() -> ServiceManager.getInstance().getLocalSearchService());
        var config = new cn.keevol.keenotes.mobilefx.search.EmbeddingConfig(true,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test", "", "", "query: ");
        try {
            fx(() -> service.saveConfiguration(config)).get(5, TimeUnit.SECONDS);
            LocalCacheService cache = ServiceManager.getInstance().getLocalCacheService();
            cache.batchInsertNotes(List.of(new LocalCacheService.NoteData(2, "数据库语义搜索", "desktop", "2026-09-19", null)), false);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!fx(() -> {
                var status = service.statusProperty().get();
                return status != null && status.vectors().base() + status.vectors().delta() > 0;
            })) {
                assertTrue("Vector worker did not index the committed note", System.nanoTime() < deadline);
                Thread.sleep(50);
            }
            for (int i = 0; i < 3; i++) {
                CountDownLatch previewed = new CountDownLatch(1);
                var task = fx(() -> service.search("数据库", result -> {
                    assertTrue(Platform.isFxApplicationThread());
                    assertFalse(result.notes().isEmpty()); previewed.countDown();
                }));
                assertTrue("Keyword preview waited on an obsolete HTTP request", previewed.await(2, TimeUnit.SECONDS));
                assertTrue(entered.tryAcquire(2, TimeUnit.SECONDS));
                fx(() -> task.cancel(false));
            }
            fx(() -> service.saveConfiguration(cn.keevol.keenotes.mobilefx.search.EmbeddingConfig.disabled())).get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown(); server.stop(0); requestExecutor.shutdownNow();
            fx(() -> service.saveConfiguration(cn.keevol.keenotes.mobilefx.search.EmbeddingConfig.disabled())).get(5, TimeUnit.SECONDS);
        }
    }

    @Test public void aiTabsShowSavedModelsAndKeepAddEditInADialog() throws Exception {
        LocalSearchService service = fx(() -> ServiceManager.getInstance().getLocalSearchService());
        var local = new SavedEmbeddingModel("ollama", "Local Ollama", new EmbeddingConfig(false, "http://localhost:11434/v1", "nomic-embed-text", "", "", ""));
        var llama = new SavedEmbeddingModel("llama", "llama.cpp", new EmbeddingConfig(false, "http://localhost:8080/v1", "Qwen3-Embedding-0.6B", "", "", ""));
        var remote = new SavedEmbeddingModel("remote", "Remote provider", new EmbeddingConfig(false, "https://embedding.example/v1", "text-embedding-3-small", "private-test-key", "", ""));
        fx(() -> service.saveModelCatalog(new EmbeddingModelCatalog(List.of(local, llama, remote), "ollama", false))).get(5, TimeUnit.SECONDS);
        AIView view = fx(AIView::new);
        ScrollPane root = fx(() -> {
            ScrollPane scroll = new ScrollPane(view); scroll.setFitToWidth(true); scroll.getStyleClass().add("main-view");
            Scene scene = new Scene(scroll, 1200, 920);
            scene.getStylesheets().setAll(resource("common"), resource("dark"));
            scroll.applyCss(); scroll.layout(); scroll.layout();
            TabPane tabs = (TabPane) view.lookup("#ai-settings-tabs");
            assertEquals(List.of("MCP", "Local Search"), tabs.getTabs().stream().map(tab -> tab.getText()).toList());
            assertEquals("MCP", tabs.getSelectionModel().getSelectedItem().getText());
            tabs.getSelectionModel().select(1); scroll.applyCss(); scroll.layout(); scroll.layout();
            assertEquals(4, view.lookupAll(".embedding-model-card").size());
            for (var node : view.lookupAll(".embedding-model-card")) {
                assertTrue("Model cards must size to their content", ((ToggleButton) node).getHeight() < 320);
            }
            assertEquals("Wide layouts should show None and three model cards on one row", 1L,
                    view.lookupAll(".embedding-model-card").stream().map(node -> node.getBoundsInParent().getMinY()).distinct().count());
            assertEquals(1, view.lookupAll(".selected-model-card").size());
            // Selection is shown by a check icon on the card, not by text.
            assertEquals("Only the selected card shows the check mark", 1L,
                    view.lookupAll(".model-card-check").stream().filter(javafx.scene.Node::isVisible).count());
            assertTrue(view.lookup(".selected-model-card").lookupAll(".model-card-check").stream().anyMatch(javafx.scene.Node::isVisible));
            assertNotNull("Saved models offer Configure on the card's context menu",
                    ((ToggleButton) view.lookup("#embedding-model-ollama")).getContextMenu().getItems().getFirst());
            assertNull("The None card has nothing to configure",
                    ((ToggleButton) view.lookup("#embedding-model-none")).getContextMenu());
            assertTrue(((ToggleButton) view.lookup("#embedding-model-none")).isSelected());
            assertTrue(view.lookupAll(".radio-button").isEmpty());
            assertTrue(tabs.getTabs().getLast().getContent().lookupAll(".text-field").isEmpty());
            assertTrue(view.lookupAll(".search-selection-panel").isEmpty());
            assertFalse(view.lookup("#rebuild-keyword").isDisabled());
            assertTrue(view.lookup("#rebuild-semantic").isDisabled());
            assertNotNull(view.lookup("#keyword-index-panel").lookup("#retry-keyword"));
            assertNotNull(view.lookup("#semantic-index-panel").lookup("#retry-semantic"));
            // Keyword and semantic settings are two top-level sections; models and the
            // semantic index both belong to the semantic one.
            var keywordSection = view.lookup("#keyword-search-section");
            var semanticSection = view.lookup("#semantic-search-section");
            assertNotNull(keywordSection);
            assertNotNull(semanticSection);
            assertNotNull(keywordSection.lookup("#keyword-index-panel"));
            assertNull(keywordSection.lookup("#semantic-index-panel"));
            assertNotNull(semanticSection.lookup("#semantic-index-panel"));
            assertEquals(4, semanticSection.lookupAll(".embedding-model-card").size());
            assertTrue("Keyword Search Settings must sit above Semantic Search Settings", top(keywordSection) < top(semanticSection));
            assertTrue("Semantic index must sit above the embedding model cards in its own section",
                    top(semanticSection.lookup("#semantic-index-panel")) < top(semanticSection.lookup(".embedding-model-card")));
            saveSnapshot(scroll, "ai-settings-dark");
            scene.getStylesheets().setAll(resource("common"), resource("light"));
            scroll.applyCss(); scroll.layout(); saveSnapshot(scroll, "ai-settings-light");
            scene.getStylesheets().setAll(resource("common"), resource("dark"));
            tabs.getSelectionModel().select(0); scroll.applyCss(); scroll.layout();
            saveSnapshot(scroll, "ai-settings-mcp");
            assertEquals("MCP", tabs.getSelectionModel().getSelectedItem().getText());
            assertTrue("MCP should not reserve the inactive model list height", tabs.getHeight() < 600);
            tabs.getSelectionModel().select(1); scroll.applyCss(); scroll.layout();
            for (int width : new int[]{800, 600, 1200}) {
                scroll.resize(width, 1000); scroll.layout(); scroll.layout();
                for (var card : view.lookupAll(".embedding-model-card"))
                    assertTrue("Model card exceeds viewport at width " + width, card.localToScene(card.getLayoutBounds()).getMaxX() <= width);
                for (var panel : view.lookupAll(".search-index-panel"))
                    assertTrue("Index panel exceeds viewport at width " + width, panel.localToScene(panel.getLayoutBounds()).getMaxX() <= width);
                if (width == 600) saveSnapshot(scroll, "ai-settings-narrow");
            }
            ((Button) view.lookup("#add-embedding-model")).fire();
            return scroll;
        });
        try {
            fx(() -> {
                DialogPane dialog = editor();
                ((TextField) dialog.lookup("#embedding-display-name")).setText("Local secondary");
                ((TextField) dialog.lookup("#embedding-base-url")).setText("http://localhost:8765/v1");
                ((TextField) dialog.lookup("#embedding-model-id")).setText("embedding-model-b");
                dialog.applyCss(); dialog.layout(); saveSnapshot(dialog, "ai-settings-add-model");
                ((Button) dialog.lookup("#save-embedding-model")).fire();
                return null;
            });
            awaitFx(() -> service.modelCatalogProperty().get().models().size() == 4);
            String id = fx(() -> service.modelCatalogProperty().get().models().getLast().id());
            fx(() -> {
                var card = view.lookup("#embedding-model-" + id);
                assertNotNull(card);
                assertFalse(service.modelCatalogProperty().get().enabled());
                ((ToggleButton) card.lookup(".toggle-button")).fire();
                return null;
            });
            awaitFx(() -> service.modelCatalogProperty().get().selectedId().equals(id));
            assertTrue(fx(() -> service.modelCatalogProperty().get().enabled()));
            fx(() -> {
                assertFalse(view.lookup("#rebuild-semantic").isDisabled());
                assertFalse(((ToggleButton) view.lookup("#embedding-model-none")).isSelected());
                assertEquals(1, view.lookupAll(".selected-model-card").size());
                var selected = (ToggleButton) view.lookup("#embedding-model-" + id);
                selected.fire();
                assertTrue("Clicking the selected card must retain its selection", selected.isSelected());
                return null;
            });
            fx(() -> {
                configure(view, id);
                DialogPane dialog = editor();
                assertEquals("embedding-model-b", ((TextField) dialog.lookup("#embedding-model-id")).getText());
                ((TextField) dialog.lookup("#embedding-display-name")).setText("Renamed local model");
                ((Button) dialog.lookup("#save-embedding-model")).fire(); return null;
            });
            awaitFx(() -> service.modelCatalogProperty().get().selected().name().equals("Renamed local model"));
            assertEquals(4, fx(() -> service.modelCatalogProperty().get().models().size()).intValue());
            fx(() -> { ((ToggleButton) view.lookup("#embedding-model-none")).fire(); return null; });
            awaitFx(() -> !service.modelCatalogProperty().get().enabled());
            assertNull(fx(() -> service.modelCatalogProperty().get().selected()));
            assertEquals(4, fx(() -> service.modelCatalogProperty().get().models().size()).intValue());
            fx(() -> {
                configure(view, id);
                assertFalse("Configure must not select the model", service.modelCatalogProperty().get().enabled());
                assertTrue(((ToggleButton) view.lookup("#embedding-model-none")).isSelected());
                ((Button) editor().lookupButton(javafx.scene.control.ButtonType.CANCEL)).fire();
                return null;
            });
        } finally {
            fx(() -> service.saveConfiguration(EmbeddingConfig.disabled())).get(5, TimeUnit.SECONDS);
            fx(() -> { view.dispose(); root.setContent(null); return null; });
        }
    }

    @Test public void mcpExampleExpandsWithinTheSelectedTab() throws Exception {
        fx(() -> {
            AIView view = new AIView();
            ScrollPane root = new ScrollPane(view);
            root.setFitToWidth(true);
            root.getStyleClass().add("main-view");
            Scene scene = new Scene(root, 1200, 920);
            scene.getStylesheets().setAll(resource("common"), resource("dark"));
            try {
                root.applyCss(); root.layout();
                TabPane tabs = (TabPane) view.lookup("#ai-settings-tabs");
                tabs.getSelectionModel().select(0);
                root.applyCss(); root.layout();
                double collapsedHeight = tabs.getHeight();
                var header = tabs.getSelectionModel().getSelectedItem().getContent().lookupAll(".label").stream()
                        .map(node -> (javafx.scene.control.Label) node)
                        .filter(label -> label.getText().contains("Example Configuration")).findFirst().orElseThrow();
                header.getOnMouseClicked().handle(null);
                root.applyCss(); root.layout(); root.layout();
                saveSnapshot(root, "ai-settings-mcp-expanded");
                var text = (javafx.scene.control.TextArea) tabs.getSelectionModel().getSelectedItem().getContent().lookup(".text-area");
                assertTrue(text.getText().contains("\"mcpServers\""));
                var clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
                var previous = new java.util.HashMap<javafx.scene.input.DataFormat, Object>();
                for (var format : clipboard.getContentTypes()) previous.put(format, clipboard.getContent(format));
                try {
                    ((Button) view.lookup("#copy-mcp-example")).fire();
                    assertEquals("Copy must include the complete example", text.getText(), clipboard.getString());
                } finally { clipboard.setContent(previous); }
                assertTrue("Expanded MCP example must increase the tab height: collapsed=" + collapsedHeight
                        + ", expanded=" + tabs.getHeight(), tabs.getHeight() > collapsedHeight + 100);
                assertTrue("Configuration text must fit inside the tab content without clipping",
                        text.localToScene(text.getLayoutBounds()).getMaxY()
                                <= tabs.localToScene(tabs.getLayoutBounds()).getMaxY());
                header.getOnMouseClicked().handle(null);
                root.layout(); root.layout();
                assertEquals("Collapsing the example must release its space", collapsedHeight, tabs.getHeight(), 1);
            } finally { view.dispose(); root.setContent(null); }
            return null;
        });
    }

    @Test public void indexRebuildsRunAndCancelIndependently() throws Exception {
        LocalSearchService service = fx(() -> ServiceManager.getInstance().getLocalSearchService());
        LocalCacheService cache = ServiceManager.getInstance().getLocalCacheService();
        cache.initialize();
        // Historical data has no incremental job; only the explicit semantic rebuild calls HTTP.
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + cache.getDatabasePath()); var s = c.createStatement()) {
            s.executeUpdate("INSERT OR REPLACE INTO notes_cache(id,content,channel,created_at) VALUES(1000,'independent rebuild example','desktop','2026-09-20')");
        }
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        server.createContext("/v1/embeddings", exchange -> {
            exchange.getRequestBody().readAllBytes(); entered.countDown();
            try {
                release.await(15, TimeUnit.SECONDS);
                byte[] body = "{\"data\":[{\"index\":0,\"embedding\":[1,0]}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        var config = new EmbeddingConfig(true, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "independent", "", "", "");
        try {
            fx(() -> service.saveConfiguration(config)).get(5, TimeUnit.SECONDS);
            fx(() -> { service.rebuildVectors(); return null; });
            assertTrue("Semantic rebuild did not reach the provider", entered.await(5, TimeUnit.SECONDS));
            fx(() -> {
                assertTrue(service.vectorRebuildingProperty().get());
                assertFalse(service.keywordRebuildingProperty().get());
                service.rebuildKeywords(); service.cancelKeywordRebuild(); return null;
            });
            awaitFx(() -> !service.keywordRebuildingProperty().get());
            assertTrue(fx(() -> service.keywordBuildProperty().get().startsWith("Cancelled")));
            assertTrue("Cancelling keywords must leave semantic work running", fx(() -> service.vectorRebuildingProperty().get()));
            fx(() -> { service.rebuildKeywords(); return null; });
            awaitFx(() -> !service.keywordRebuildingProperty().get());
            assertEquals("Completed", fx(() -> service.keywordBuildProperty().get()));
            fx(() -> { service.cancelVectorRebuild(); service.rebuildKeywords(); return null; });
            awaitFx(() -> !service.keywordRebuildingProperty().get());
            assertEquals("Cancelling semantics must not cancel keywords", "Completed", fx(() -> service.keywordBuildProperty().get()));
            release.countDown();
            awaitFx(() -> !service.vectorRebuildingProperty().get());
            assertTrue(fx(() -> service.vectorBuildProperty().get().startsWith("Cancelled")));
            fx(() -> { service.rebuildVectors(); return null; });
            awaitFx(() -> !service.vectorRebuildingProperty().get());
            assertEquals("Completed", fx(() -> service.vectorBuildProperty().get()));
        } finally {
            release.countDown();
            fx(() -> service.saveConfiguration(EmbeddingConfig.disabled())).get(5, TimeUnit.SECONDS);
            server.stop(0); executor.shutdownNow();
        }
    }

    @Test public void wildcardMatchesHaveNoTagsInPreviewOrHybrid() throws Exception {
        LocalSearchService service = fx(() -> ServiceManager.getInstance().getLocalSearchService());
        LocalCacheService cache = ServiceManager.getInstance().getLocalCacheService();
        fx(() -> service.saveConfiguration(EmbeddingConfig.disabled())).get(5, TimeUnit.SECONDS);
        var notes = List.of(
                new LocalCacheService.NoteData(4801, "chronologyprobe", "desktop", "2026-08-06 04:08:48", null),
                new LocalCacheService.NoteData(4802, "chronologyprobe " + "context ".repeat(30), "desktop", "2026-09-14 12:57:54", null),
                new LocalCacheService.NoteData(4803, "chronologyprobe", "desktop", "2025-11-06 17:48:34", null),
                new LocalCacheService.NoteData(4804, "chronologyprobe", "desktop", "2025-12-06 11:41:46", null),
                new LocalCacheService.NoteData(4805, "chronologyprobe " + "context ".repeat(40), "desktop", "2026-09-14 12:57:54", null),
                new LocalCacheService.NoteData(4806, "chronologyprobe", "desktop", null, null));
        cache.batchInsertNotes(notes, false);
        var expected = java.util.Set.of(4805L, 4802L, 4801L, 4804L, 4803L, 4806L);
        LocalSearchService.ViewResult result;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        do {
            result = fx(() -> service.search("chronologyprobe", preview -> { })).get(5, TimeUnit.SECONDS);
            if (result.notes().size() == notes.size()) break;
            assertTrue("Notes were not indexed", System.nanoTime() < deadline);
            Thread.sleep(50);
        } while (true);
        assertEquals(expected, result.notes().stream().map(note -> note.id).collect(java.util.stream.Collectors.toSet()));
        assertTrue(result.keywordIds().isEmpty());
        assertTrue(result.semanticIds().isEmpty());
        cache.batchInsertNotes(List.of(new LocalCacheService.NoteData(4807, "semantic-only candidate",
                "desktop", "2030-01-01", null)), false);
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var semanticQueries = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/v1/embeddings", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (request.contains("query: chronologyprobe")) semanticQueries.incrementAndGet();
            byte[] body = "{\"data\":[{\"index\":0,\"embedding\":[1,0]}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try { exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var config = new EmbeddingConfig(true, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "ordering", "", "", "query: ");
            fx(() -> service.saveConfiguration(config)).get(5, TimeUnit.SECONDS);
            fx(() -> { service.rebuildVectors(); return null; });
            awaitFx(() -> !service.vectorRebuildingProperty().get());
            assertEquals("Completed", fx(() -> service.vectorBuildProperty().get()));
            CompletableFuture<LocalSearchService.ViewResult> preview = new CompletableFuture<>();
            var hybrid = fx(() -> service.search("chronologyprobe", preview::complete)).get(5, TimeUnit.SECONDS);
            var keywordPreview = preview.get(5, TimeUnit.SECONDS);
            assertEquals(expected, keywordPreview.notes().stream().map(note -> note.id).collect(java.util.stream.Collectors.toSet()));
            assertTrue(keywordPreview.semanticIds().isEmpty());
            assertTrue(keywordPreview.keywordIds().isEmpty());
            assertTrue("The final result must exercise semantic search", semanticQueries.get() > 0);
            assertEquals("Overlapping hits appear only once", expected.size(),
                    hybrid.notes().stream().map(note -> note.id).filter(expected::contains).count());
            assertTrue(hybrid.keywordIds().isEmpty());
            assertTrue(expected.stream().noneMatch(hybrid.semanticIds()::contains));
            assertTrue(hybrid.semanticIds().contains(4807L));
        } finally {
            fx(() -> service.saveConfiguration(EmbeddingConfig.disabled())).get(5, TimeUnit.SECONDS);
            server.stop(0);
        }
    }

    @Test public void fusedRelevanceOrderIsPreservedByTheViewWithTagsOnlyOnRecallMatches() throws Exception {
        LocalSearchService service = fx(() -> ServiceManager.getInstance().getLocalSearchService());
        LocalCacheService cache = ServiceManager.getInstance().getLocalCacheService();
        fx(() -> service.saveConfiguration(EmbeddingConfig.disabled())).get(5, TimeUnit.SECONDS);
        cache.batchInsertNotes(List.of(
                new LocalCacheService.NoteData(5101, "specificityprobe matchwords " + "context ".repeat(50), "desktop", "2020-01-01", null),
                new LocalCacheService.NoteData(5102, "specificityprobe context matchwords", "desktop", "2021-01-01", null),
                new LocalCacheService.NoteData(5103, "matchwords specificityprobe " + "context ".repeat(50), "desktop", "2030-01-01", null)), false);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        LocalSearchService.ViewResult result;
        do {
            result = fx(() -> service.search("specificityprobe matchwords", preview -> { })).get(5, TimeUnit.SECONDS);
            if (result.notes().size() == 3) break;
            assertTrue("Keyword recall did not become visible", System.nanoTime() < deadline);
            Thread.sleep(50);
        } while (true);
        assertEquals(List.of(5101L, 5102L, 5103L), result.notes().stream().map(note -> note.id).toList());
        assertEquals(java.util.Set.of(5102L, 5103L), result.keywordIds());
        assertTrue(result.semanticIds().isEmpty());
    }

    private static DialogPane editor() {
        return javafx.stage.Window.getWindows().stream().filter(javafx.stage.Window::isShowing)
                .map(window -> window.getScene().lookup("#embedding-model-editor")).filter(java.util.Objects::nonNull)
                .map(node -> (DialogPane) node).findFirst().orElseThrow();
    }
    private static void awaitFx(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!fx(condition)) { assertTrue("Timed out waiting for model settings", System.nanoTime() < deadline); Thread.sleep(20); }
    }
    private static String resource(String theme) { return SearchFxTest.class.getResource("/styles/" + theme + ".css").toExternalForm(); }
    /** Editing a saved model goes through the card's context menu; None has nothing to configure. */
    private static void configure(AIView view, String id) {
        ((ToggleButton) view.lookup("#embedding-model-" + id)).getContextMenu().getItems().getFirst().fire();
    }

    private static double top(javafx.scene.Node node) {
        return node.localToScene(node.getBoundsInLocal()).getMinY();
    }

    private static void saveSnapshot(javafx.scene.Parent root, String name) throws Exception {
        var snapshot = root.snapshot(null, null);
        var image = new java.awt.image.BufferedImage((int) snapshot.getWidth(), (int) snapshot.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, snapshot.getPixelReader().getArgb(x, y));
        javax.imageio.ImageIO.write(image, "png", Path.of("target/" + name + ".png").toFile());
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); Platform.runLater(task); return task.get(10, TimeUnit.SECONDS);
    }
}
