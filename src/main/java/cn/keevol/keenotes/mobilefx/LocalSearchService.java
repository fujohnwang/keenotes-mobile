package cn.keevol.keenotes.mobilefx;

import cn.keevol.keenotes.mobilefx.search.*;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.concurrent.Task;

import java.util.List;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Application lifetime owner. Every database, model and Lucene operation runs on a background executor. */
public final class LocalSearchService implements AutoCloseable {
    private final LocalCacheService cache;
    private final SettingsService settings;
    private final ScheduledExecutorService workers = Executors.newScheduledThreadPool(2, factory("search-index"));
    private final ExecutorService queries = Executors.newFixedThreadPool(2, factory("search-query"));
    private final ExecutorService builders = Executors.newFixedThreadPool(2, factory("search-rebuild"));
    private final ExecutorService controls = Executors.newFixedThreadPool(2, factory("search-config"));
    private final OpenAiEmbeddingClient client = new OpenAiEmbeddingClient();
    private final java.util.Set<OpenAiEmbeddingClient> probes = ConcurrentHashMap.newKeySet();
    private final CompletableFuture<LocalSearchEngine> ready;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean cancelKeyword = new AtomicBoolean();
    private final AtomicBoolean cancelVector = new AtomicBoolean();
    private final ReadOnlyObjectWrapper<LocalSearchEngine.Status> status = new ReadOnlyObjectWrapper<>();
    private final ReadOnlyStringWrapper keywordError = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper vectorError = new ReadOnlyStringWrapper("");
    private final ReadOnlyStringWrapper keywordBuild = new ReadOnlyStringWrapper("Idle");
    private final ReadOnlyStringWrapper vectorBuild = new ReadOnlyStringWrapper("Idle");
    private final ReadOnlyBooleanWrapper keywordRebuilding = new ReadOnlyBooleanWrapper(false);
    private final ReadOnlyBooleanWrapper vectorRebuilding = new ReadOnlyBooleanWrapper(false);
    private final ReadOnlyObjectWrapper<EmbeddingModelCatalog> modelCatalog = new ReadOnlyObjectWrapper<>();
    private volatile EmbeddingConfig config;

    public LocalSearchService(LocalCacheService cache, SettingsService settings) {
        this.cache = cache; this.settings = settings; this.config = settings.getEmbeddingConfig();
        modelCatalog.set(settings.getEmbeddingModelCatalog());
        ready = CompletableFuture.supplyAsync(() -> {
            try {
                cache.initialize();
                LocalSearchEngine engine = new LocalSearchEngine(cache.getDatabasePath(), client, settings::getEmbeddingApiKey);
                engine.configure(config);
                return engine;
            } catch (Exception e) { throw new CompletionException(e); }
        }, workers);
        workers.scheduleWithFixedDelay(() -> maintain(false), 0, 500, TimeUnit.MILLISECONDS);
        workers.scheduleWithFixedDelay(() -> maintain(true), 0, 1000, TimeUnit.MILLISECONDS);
    }

    private static ThreadFactory factory(String name) {
        return runnable -> { Thread thread = new Thread(runnable, name); thread.setDaemon(true); return thread; };
    }

    private void maintain(boolean vector) {
        if (closed.get() || !ready.isDone()) return;
        ReadOnlyStringWrapper error = vector ? vectorError : keywordError;
        try {
            LocalSearchEngine engine = ready.join();
            if (vector) engine.drainVectors(config);
            else {
                engine.drainKeywords();
                LocalSearchEngine.Status snapshot = engine.status(config);
                ui(() -> status.set(snapshot));
            }
            ui(() -> error.set(""));
        } catch (Exception e) { ui(() -> error.set("Search indexing: " + message(e))); }
    }

    public Task<ViewResult> search(String query, Consumer<ViewResult> preview) {
        SearchCancellation cancellation = new SearchCancellation();
        Task<ViewResult> task = new Task<>() {
            @Override protected void cancelled() { cancellation.cancel(); }
            @Override protected ViewResult call() throws Exception {
                LocalSearchEngine engine = ready.get();
                checkRunning();
                String epoch = cache.getSearchEpoch();
                EmbeddingConfig requestConfig = config;
                LocalSearchEngine.SearchResult keyword = engine.search(query, EmbeddingConfig.disabled(), cancellation);
                ViewResult first = hydrate(epoch, keyword);
                if (!isCancelled() && requestConfig.usable()) ui(() -> { if (!isCancelled()) preview.accept(first); });
                if (isCancelled() || !requestConfig.usable()) return first;
                return hydrate(epoch, engine.search(query, requestConfig, cancellation));
            }
        };
        queries.execute(task);
        return task;
    }

    private ViewResult hydrate(String epoch, LocalSearchEngine.SearchResult result) throws Exception {
        // Preserve the fused relevance order, including date/ID tie-breaks from the engine.
        List<LocalCacheService.NoteData> notes = cache.getNotesByIds(result.ids());
        if (!Objects.equals(epoch, cache.getSearchEpoch())) return new ViewResult(List.of(), Set.of(), Set.of(), epoch, true, "Search data changed.");
        // SQL matches need no tag, even if Lucene or the vector index also found them.
        Set<Long> keywordTags = new HashSet<>(result.keywordIds());
        Set<Long> semanticTags = new HashSet<>(result.semanticIds());
        keywordTags.removeAll(result.wildcardIds());
        semanticTags.removeAll(result.wildcardIds());
        return new ViewResult(notes, Set.copyOf(keywordTags), Set.copyOf(semanticTags), epoch, result.partial(), result.message());
    }

    public boolean isCurrent(ViewResult result) { return !closed.get() && Objects.equals(result.epoch(), cache.getSearchEpoch()); }

    public Task<Void> saveConfiguration(EmbeddingConfig next) {
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                checkRunning();
                if (next.enabled() && !next.configured()) throw new IllegalArgumentException("Set Base URL and Model before enabling semantic search");
                if (next.configured()) next.endpoint();
                config = next;
                if (!next.usable()) { cancelVector.set(true); client.cancelRequests(); }
                settings.setEmbeddingConfig(next); settings.save();
                ready.get().configure(next);
                EmbeddingModelCatalog saved = settings.getEmbeddingModelCatalog();
                ui(() -> modelCatalog.set(saved));
                return null;
            }
        };
        controls.execute(task);
        return task;
    }

    public Task<Void> saveModelCatalog(EmbeddingModelCatalog catalog) {
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                checkRunning();
                settings.setEmbeddingModelCatalog(catalog);
                EmbeddingConfig next = catalog.configuration();
                config = next;
                if (!next.usable()) { cancelVector.set(true); client.cancelRequests(); }
                settings.save();
                ready.get().configure(next);
                ui(() -> modelCatalog.set(catalog));
                return null;
            }
        };
        controls.execute(task);
        return task;
    }

    public ReadOnlyObjectProperty<EmbeddingModelCatalog> modelCatalogProperty() { return modelCatalog.getReadOnlyProperty(); }

    public Task<Integer> testConnection(EmbeddingConfig testConfig) {
        java.util.concurrent.atomic.AtomicReference<OpenAiEmbeddingClient> activeProbe = new java.util.concurrent.atomic.AtomicReference<>();
        Task<Integer> task = new Task<>() {
            @Override protected void cancelled() {
                OpenAiEmbeddingClient probe = activeProbe.get();
                if (probe != null) probe.close();
            }
            @Override protected Integer call() throws Exception {
                OpenAiEmbeddingClient testClient = new OpenAiEmbeddingClient();
                try (testClient) {
                    probes.add(testClient);
                    activeProbe.set(testClient);
                    if (isCancelled()) throw new CancellationException();
                    checkRunning();
                    float[] document = testClient.embedDocuments(testConfig, List.of("KeeNotes embedding connection test.")).getFirst();
                    float[] query = testClient.embedQuery(testConfig, "connection test");
                    if (document.length != query.length) throw new OpenAiEmbeddingClient.ProviderException("Query and document dimensions differ");
                    try (var directory = new org.apache.lucene.store.ByteBuffersDirectory();
                         var writer = new org.apache.lucene.index.IndexWriter(directory, SearchVectorCodec.configure(new org.apache.lucene.index.IndexWriterConfig()))) {
                        var doc = new org.apache.lucene.document.Document();
                        doc.add(new org.apache.lucene.document.KnnFloatVectorField("embedding", document,
                                org.apache.lucene.index.VectorSimilarityFunction.DOT_PRODUCT));
                        writer.addDocument(doc); writer.commit();
                    }
                    return document.length;
                } finally { activeProbe.set(null); probes.remove(testClient); }
            }
        };
        controls.execute(task);
        return task;
    }

    public void rebuildKeywords() { startBuild(false); }
    public void rebuildVectors() { if (config.usable()) startBuild(true); }

    private void startBuild(boolean vector) {
        ReadOnlyBooleanWrapper busy = vector ? vectorRebuilding : keywordRebuilding;
        AtomicBoolean cancellation = vector ? cancelVector : cancelKeyword;
        ReadOnlyStringWrapper text = vector ? vectorBuild : keywordBuild;
        if (closed.get() || busy.get()) return;
        cancellation.set(false);
        busy.set(true);
        EmbeddingConfig buildConfig = config;
        Task<Void> task = new Task<>() {
            @Override protected Void call() throws Exception {
                LocalSearchEngine engine = ready.get();
                java.util.function.BiConsumer<Integer, Integer> progress = (done, total) -> {
                    updateProgress(done, Math.max(total, 1));
                    updateMessage(done + " / " + total + " notes");
                };
                if (vector) engine.rebuildVectors(buildConfig, cancellation::get, progress);
                else engine.rebuildKeywords(cancellation::get, progress);
                return null;
            }
        };
        text.bind(task.messageProperty());
        task.setOnSucceeded(e -> { text.unbind(); text.set("Completed"); busy.set(false); });
        task.setOnFailed(e -> {
            text.unbind();
            text.set(task.getException() instanceof CancellationException ? "Cancelled; rebuild again to resume" : "Failed: " + message(task.getException()) + ". Rebuild to retry.");
            busy.set(false);
        });
        builders.execute(task);
    }

    public void cancelKeywordRebuild() { cancelKeyword.set(true); }
    public void cancelVectorRebuild() { cancelVector.set(true); }
    public void retryKeywordFailures() { retryFailed(false); }
    public void retryVectorFailures() { if (config.usable()) retryFailed(true); }
    private void retryFailed(boolean vector) {
        ReadOnlyStringWrapper error = vector ? vectorError : keywordError;
        workers.execute(() -> {
            try {
                if (vector) ready.get().retryVectorFailures();
                else ready.get().retryKeywordFailures();
            }
            catch (Exception e) { ui(() -> error.set(message(e))); }
        });
    }

    public ReadOnlyObjectProperty<LocalSearchEngine.Status> statusProperty() { return status.getReadOnlyProperty(); }
    public ReadOnlyStringProperty keywordErrorProperty() { return keywordError.getReadOnlyProperty(); }
    public ReadOnlyStringProperty vectorErrorProperty() { return vectorError.getReadOnlyProperty(); }
    public ReadOnlyStringProperty keywordBuildProperty() { return keywordBuild.getReadOnlyProperty(); }
    public ReadOnlyStringProperty vectorBuildProperty() { return vectorBuild.getReadOnlyProperty(); }
    public ReadOnlyBooleanProperty keywordRebuildingProperty() { return keywordRebuilding.getReadOnlyProperty(); }
    public ReadOnlyBooleanProperty vectorRebuildingProperty() { return vectorRebuilding.getReadOnlyProperty(); }

    private void ui(Runnable action) { if (!closed.get()) Platform.runLater(() -> { if (!closed.get()) action.run(); }); }
    private void checkRunning() { if (closed.get()) throw new CancellationException("Search service is closed"); }
    public static String message(Throwable error) {
        while ((error instanceof ExecutionException || error instanceof CompletionException) && error.getCause() != null) error = error.getCause();
        if (error instanceof OpenAiEmbeddingClient.ProviderException || error instanceof IllegalArgumentException) return error.getMessage();
        return error.getClass().getSimpleName();
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        cancelKeywordRebuild(); cancelVectorRebuild(); client.close(); probes.forEach(OpenAiEmbeddingClient::close);
        workers.shutdown(); queries.shutdown(); builders.shutdown(); controls.shutdown();
        // Never close Lucene readers underneath a running query or block the FX thread on HTTP/IO.
        CompletableFuture.runAsync(() -> {
            try {
                workers.awaitTermination(45, TimeUnit.SECONDS);
                queries.awaitTermination(45, TimeUnit.SECONDS);
                builders.awaitTermination(45, TimeUnit.SECONDS);
                controls.awaitTermination(45, TimeUnit.SECONDS);
                if (!ready.isCompletedExceptionally()) ready.get().close();
            } catch (Exception ignored) { client.close(); }
        });
    }

    public record ViewResult(List<LocalCacheService.NoteData> notes, Set<Long> keywordIds, Set<Long> semanticIds,
                             String epoch, boolean partial, String message) { }
}
