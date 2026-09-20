package cn.keevol.keenotes.mobilefx.search;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Blocking operations. The desktop facade schedules every call off the JavaFX application thread. */
public final class LocalSearchEngine implements AutoCloseable {
    private final SearchStore store;
    private final EmbeddingClient embeddings;
    private final Path root;
    private final Function<String, String> credentials;
    private final Map<String, Handle> families = new HashMap<>();
    private final Set<String> building = ConcurrentHashMap.newKeySet();
    private final ObjectMapper json = new ObjectMapper();
    private String epoch;
    private volatile boolean closed;
    private volatile EmbeddingConfig desired = EmbeddingConfig.disabled();
    private volatile EmbeddingConfig activeVector;

    public LocalSearchEngine(Path database, EmbeddingClient embeddings) throws SQLException, IOException {
        this(database, embeddings, profile -> "");
    }

    public LocalSearchEngine(Path database, EmbeddingClient embeddings, Function<String, String> credentials) throws SQLException, IOException {
        this.store = new SearchStore(database);
        this.embeddings = embeddings;
        this.credentials = credentials;
        this.root = database.toAbsolutePath().getParent().resolve("search");
        this.epoch = store.epoch();
        loadActiveConfig();
        cleanupRetiredEpochs();
    }

    private void loadActiveConfig() throws SQLException, IOException {
        String config = store.meta("active.vector");
        activeVector = config == null ? null : json.readValue(config, EmbeddingConfig.class);
        if (activeVector != null) activeVector = new EmbeddingConfig(true, activeVector.baseUrl(), activeVector.model(),
                credentials.apply(activeVector.profile()), activeVector.documentPrefix(), activeVector.queryPrefix());
    }

    private synchronized Handle family(String name, String profile) throws SQLException, IOException {
        ensureCurrent();
        String key = name + "/" + profile;
        Handle handle = families.get(key);
        if (handle == null) {
            Path path = root.resolve(epoch).resolve(key);
            handle = new Handle(new IndexFamily(path, store.meta("base." + name + "." + profile)), epoch, path);
            families.put(key, handle);
        }
        return handle;
    }

    private synchronized void ensureCurrent() throws SQLException, IOException {
        if (closed) throw new IllegalStateException("Search is closed");
        String current = store.epoch();
        if (!current.equals(epoch)) {
            for (Handle h : families.values()) h.index().close();
            families.clear(); epoch = current;
            loadActiveConfig();
            store.configure(desired, activeVector == null ? null : activeVector.profile());
        }
    }

    public synchronized void configure(EmbeddingConfig config) throws SQLException, IOException {
        ensureCurrent();
        desired = config;
        if (activeVector != null && activeVector.profile().equals(config.profile())) activeVector = config;
        store.configure(config, activeVector == null ? null : activeVector.profile());
    }

    public void drainKeywords() throws SQLException, IOException {
        Handle handle = family(SearchStore.KEYWORD, SearchStore.KEYWORD_PROFILE);
        List<SearchStore.Work> jobs = store.pending(SearchStore.KEYWORD, SearchStore.KEYWORD_PROFILE, 64);
        if (!handle.epoch().equals(store.epoch())) return;
        List<IndexFamily.Entry> entries = new ArrayList<>();
        for (SearchStore.Work job : jobs) if (store.current(handle.epoch(), job)) {
            if (!handle.index().contains(job.id(), job.hash())) entries.add(new IndexFamily.Entry(job, null));
            else store.completed(handle.epoch(), SearchStore.KEYWORD, SearchStore.KEYWORD_PROFILE, job);
        }
        if (!entries.isEmpty()) {
            try {
                handle.index().apply(entries);
                for (IndexFamily.Entry entry : entries)
                    store.completed(handle.epoch(), SearchStore.KEYWORD, SearchStore.KEYWORD_PROFILE, entry.work());
            } catch (IOException | IllegalArgumentException batchFailure) {
                // Isolate a malformed note without losing the rest of a committed sync batch.
                for (IndexFamily.Entry entry : entries) try {
                    handle.index().apply(List.of(entry));
                    store.completed(handle.epoch(), SearchStore.KEYWORD, SearchStore.KEYWORD_PROFILE, entry.work());
                } catch (IOException | IllegalArgumentException e) {
                    store.failed(handle.epoch(), SearchStore.KEYWORD, SearchStore.KEYWORD_PROFILE, entry.work(), safeError(e));
                }
            }
        }
        cleanupRetiredEpochs();
    }

    private void writeVector(Handle handle, EmbeddingConfig config, SearchStore.Work job) throws SQLException, IOException {
        if (!handle.index().contains(job.id(), job.hash())) {
            float[] vector = job.eligible() ? embeddings.embedDocuments(config, List.of(job.body())).getFirst() : null;
            if (!allowed(config) || !store.current(handle.epoch(), job)) return;
            handle.index().apply(List.of(new IndexFamily.Entry(job, vector)));
        }
        store.completed(handle.epoch(), SearchStore.VECTOR, config.profile(), job);
    }

    public void drainVectors(EmbeddingConfig config) throws SQLException, IOException {
        if (!config.usable() || !desired.usable()) return;
        drainVectorProfile(config);
        EmbeddingConfig previous = activeVector;
        if (previous != null && !previous.profile().equals(config.profile()) && desired.usable()) drainVectorProfile(previous);
    }

    private void drainVectorProfile(EmbeddingConfig config) throws SQLException, IOException {
        Handle handle = family(SearchStore.VECTOR, config.profile());
        for (SearchStore.Work job : store.pending(SearchStore.VECTOR, config.profile(), 4)) {
            if (!allowed(config) || !store.current(handle.epoch(), job)) return;
            try {
                writeVector(handle, config, job);
            } catch (IOException | IllegalArgumentException e) {
                store.failed(handle.epoch(), SearchStore.VECTOR, config.profile(), job, safeError(e));
            }
        }
    }

    private boolean allowed(EmbeddingConfig config) {
        EmbeddingConfig selected = desired;
        EmbeddingConfig active = activeVector;
        return !closed && selected.usable() && (selected.profile().equals(config.profile())
                || active != null && active.profile().equals(config.profile()));
    }

    public void rebuildKeywords(BooleanSupplier cancelled, BiConsumer<Integer, Integer> progress) throws Exception {
        rebuild(SearchStore.KEYWORD, SearchStore.KEYWORD_PROFILE, null, cancelled, progress);
    }

    public void rebuildVectors(EmbeddingConfig config, BooleanSupplier cancelled, BiConsumer<Integer, Integer> progress) throws Exception {
        if (!config.usable()) throw new IllegalStateException("Enable and configure the embedding provider first");
        rebuild(SearchStore.VECTOR, config.profile(), config, cancelled, progress);
    }

    private void rebuild(String family, String profile, EmbeddingConfig config, BooleanSupplier cancelled,
                         BiConsumer<Integer, Integer> progress) throws Exception {
        String key = family + "/" + profile;
        if (!building.add(key)) throw new IllegalStateException("A rebuild is already running");
        try {
            Handle handle = family(family, profile);
            SearchStore.Build build = store.beginBuild(family, profile);
            Path path = handle.path().resolve(build.id());
            Map<Long, String> expected = new HashMap<>();
            Map<Long, String> snapshotHashes = new HashMap<>();
            int done = 0;
            progress.accept(0, build.total());
            try (IndexFamily.Builder writer = new IndexFamily.Builder(path)) {
                long after = Long.MIN_VALUE;
                List<SearchStore.Work> page;
                while (!(page = store.page(build, after)).isEmpty()) {
                    checkBuild(build, config, cancelled);
                    for (SearchStore.Work note : page) {
                        checkBuildCancellation(config, cancelled);
                        snapshotHashes.put(note.id(), note.hash());
                        if (note.eligible()) {
                            float[] vector = null;
                            if (config != null) {
                                vector = writer.vector(note.id(), note.hash());
                                if (vector == null) vector = handle.index().vector(note.id(), note.hash());
                                if (vector == null) {
                                    checkBuild(build, config, cancelled);
                                    vector = embeddings.embedDocuments(config, List.of(note.body())).getFirst();
                                }
                            }
                            checkBuildCancellation(config, cancelled);
                            writer.add(new IndexFamily.Entry(note, vector));
                            expected.put(note.id(), note.hash());
                        }
                        after = note.id();
                        progress.accept(++done, build.total());
                    }
                    writer.checkpoint();
                }
                if (done != build.total()) throw new IOException("Rebuild snapshot changed");
                writer.validate(expected);
            }
            checkBuild(build, config, cancelled);
            // Model migration is separate from the fixed Base snapshot: fill a fixed sample of
            // the previous visible Delta, then guard cutover. If it advanced again, retain the old
            // view and let the user resume after candidate incremental indexing catches up.
            EmbeddingConfig previousConfig = activeVector;
            Handle previous = config != null && previousConfig != null && !previousConfig.profile().equals(profile)
                    ? family(SearchStore.VECTOR, previousConfig.profile()) : null;
            if (previous != null) {
                for (var entry : previous.index().visibleHashes().entrySet()) {
                    if (covered(handle, expected, entry)) continue;
                    checkBuild(build, config, cancelled);
                    SearchStore.Work work = store.currentWork(entry.getKey());
                    if (work != null) writeVector(handle, config, work);
                }
            }
            synchronized (this) {
                checkBuild(build, config, cancelled);
                synchronized (previous == null ? handle.index() : previous.index()) {
                    synchronized (handle.index()) {
                        if (previous != null && previous.index().visibleHashes().entrySet().stream()
                                .anyMatch(entry -> !covered(handle, expected, entry)))
                            throw new IOException("Candidate semantic Delta is not ready; resume rebuild after incremental indexing catches up");
                        String publicConfig = config == null ? null : json.writeValueAsString(new EmbeddingConfig(true,
                                config.baseUrl(), config.model(), "", config.documentPrefix(), config.queryPrefix()));
                        store.publish(build, family, profile, publicConfig);
                        handle.index().installBase(path);
                        if (config != null) activeVector = config;
                        handle.index().removeSupersededDelta(snapshotHashes, store);
                        handle.index().cleanCoveredDelta();
                    }
                }
            }
            cleanupBases(handle.path(), build.id());
        } finally { building.remove(key); }
    }

    private static boolean covered(Handle handle, Map<Long, String> expected, Map.Entry<Long, String> note) {
        return note.getValue().equals(expected.get(note.getKey())) || handle.index().contains(note.getKey(), note.getValue());
    }

    private synchronized void cleanupRetiredEpochs() throws IOException {
        if (!building.isEmpty() || !Files.isDirectory(root)) return;
        try (var dirs = Files.list(root)) {
            for (Path dir : dirs.toList()) {
                String name = dir.getFileName().toString();
                if (!name.equals(epoch) && name.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) deleteTree(dir);
            }
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        try (var files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }

    private void checkBuild(SearchStore.Build build, EmbeddingConfig config, BooleanSupplier cancelled) throws SQLException {
        checkBuildCancellation(config, cancelled);
        if (!build.epoch().equals(store.epoch())) throw new CancellationException();
    }

    private void checkBuildCancellation(EmbeddingConfig config, BooleanSupplier cancelled) {
        if (closed || cancelled.getAsBoolean()
                || config != null && (!desired.usable() || !desired.profile().equals(config.profile()))) throw new CancellationException();
    }

    private static void cleanupBases(Path parent, String active) throws IOException {
        try (var dirs = Files.list(parent)) {
            for (Path dir : dirs.filter(p -> p.getFileName().toString().startsWith("base-") && !p.getFileName().toString().equals(active)).toList()) {
                deleteTree(dir);
            }
        }
    }

    public SearchResult search(String query, EmbeddingConfig config) throws SQLException, IOException {
        return search(query, config, new SearchCancellation());
    }

    public SearchResult search(String query, EmbeddingConfig config, SearchCancellation cancellation) throws SQLException, IOException {
        cancellation.check();
        if (query == null || query.isBlank()) return new SearchResult(List.of(), false, "");
        Handle keyword = family(SearchStore.KEYWORD, SearchStore.KEYWORD_PROFILE);
        List<Long> keywords = keyword.index().keywords(query, 100);
        List<Long> result = keywords;
        boolean partial = !keyword.index().hasBase();
        String message = "";
        if (config.usable() && desired.usable()) {
            EmbeddingConfig active = activeVector;
            EmbeddingConfig queryConfig = active == null || active.profile().equals(config.profile()) ? config : active;
            try {
                Handle vector = family(SearchStore.VECTOR, queryConfig.profile());
                partial |= !vector.index().hasBase();
                if (vector.index().count() > 0) {
                    float[] queryVector = embeddings.embedQuery(queryConfig, query, cancellation);
                    if (allowed(queryConfig)) result = fuse(keywords, vector.index().vectors(queryVector, 100));
                } else message = "No semantic index yet; showing keyword matches.";
                if (!queryConfig.profile().equals(config.profile())) message = "Using the previous embedding model until the new index is rebuilt.";
            } catch (IOException | IllegalArgumentException e) {
                message = "Semantic search unavailable; showing keyword matches. " + safeError(e);
            }
        }
        cancellation.check();
        if (!keyword.epoch().equals(store.epoch())) return new SearchResult(List.of(), true, "Search data changed. Search again.");
        return new SearchResult(result, partial, message);
    }

    static List<Long> fuse(List<Long> keywords, List<Long> vectors) {
        Map<Long, Double> scores = new HashMap<>();
        for (List<Long> branch : List.of(keywords, vectors)) {
            Set<Long> seen = new HashSet<>();
            for (int i = 0; i < branch.size(); i++) if (seen.add(branch.get(i))) scores.merge(branch.get(i), 1.0 / (61 + i), Double::sum);
        }
        return scores.entrySet().stream().sorted(Map.Entry.<Long, Double>comparingByValue().reversed()
                .thenComparing(Map.Entry.<Long, Double>comparingByKey().reversed())).limit(100).map(Map.Entry::getKey).toList();
    }

    public Status status(EmbeddingConfig config) throws SQLException, IOException {
        Handle keywords = family(SearchStore.KEYWORD, SearchStore.KEYWORD_PROFILE);
        int[] kw = store.pendingCounts(SearchStore.KEYWORD, SearchStore.KEYWORD_PROFILE);
        int[] vec = config.configured() ? store.pendingCounts(SearchStore.VECTOR, config.profile()) : new int[]{0, 0};
        IndexFamily vector = config.usable() ? family(SearchStore.VECTOR, config.profile()).index() : null;
        return new Status(keywords.index().count(), kw[0], kw[1], keywords.index().hasBase(),
                vector == null ? 0 : vector.count(), vec[0], vec[1], vector != null && vector.hasBase());
    }

    public void retryKeywordFailures() throws SQLException { store.retryFailures(SearchStore.KEYWORD); }
    public void retryVectorFailures() throws SQLException { store.retryFailures(SearchStore.VECTOR); }
    public static String safeError(Exception e) { return e instanceof OpenAiEmbeddingClient.ProviderException ? e.getMessage() : e.getClass().getSimpleName(); }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try { for (Handle handle : families.values()) handle.index().close(); }
        finally { embeddings.close(); }
    }

    private record Handle(IndexFamily index, String epoch, Path path) { }
    public record SearchResult(List<Long> ids, boolean partial, String message) { }
    public record Status(int keywords, int keywordPending, int keywordFailed, boolean keywordBase,
                         int vectors, int vectorPending, int vectorFailed, boolean vectorBase) { }
}
