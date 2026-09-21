package cn.keevol.keenotes.mobilefx.search;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.QueryBuilder;
import org.apache.lucene.util.VectorUtil;
import org.apache.lucene.util.IOUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** One keyword or vector family. The short monitor protects reader ownership and publication. */
final class IndexFamily implements AutoCloseable {
    private final Analyzer analyzer = new JiebaAnalyzer();
    private final Analyzer queryAnalyzer = new JiebaAnalyzer(com.huaban.analysis.jieba.JiebaSegmenter.SegMode.SEARCH);
    private Directory deltaDirectory;
    private IndexWriter deltaWriter;
    private DirectoryReader deltaReader;
    private Directory baseDirectory;
    private DirectoryReader baseReader;
    private Map<Long, DocState> base = Map.of();
    private Map<Long, DocState> delta;
    private Query visibility;
    private int visibleCount;
    private boolean closed;

    IndexFamily(Path directory, String generation) throws IOException {
        try {
            Files.createDirectories(directory);
            deltaDirectory = FSDirectory.open(directory.resolve("delta"));
            deltaWriter = new IndexWriter(deltaDirectory, SearchVectorCodec.configure(new IndexWriterConfig(analyzer)));
            deltaReader = DirectoryReader.open(deltaWriter);
            delta = readStates(deltaReader);
            recount();
            if (generation != null) {
                try { installBase(directory.resolve(generation)); }
                catch (IOException damagedBase) {
                    // Delta remains available. The UI reports an incomplete index and manual rebuild repairs Base.
                }
            }
        } catch (IOException | RuntimeException e) {
            IOUtils.closeWhileHandlingException(deltaReader, deltaWriter, deltaDirectory, analyzer, queryAnalyzer);
            throw e;
        }
    }

    synchronized boolean contains(long id, String hash) {
        DocState state = delta.get(id);
        if (state == null) state = base.get(id);
        return state != null && state.hash().equals(hash) && state.eligible();
    }

    synchronized void apply(List<Entry> entries) throws IOException {
        ensureOpen();
        for (Entry e : entries) deltaWriter.updateDocument(new Term("key", Long.toString(e.work().id())), document(e, "delta"));
        deltaWriter.commit();
        DirectoryReader next = DirectoryReader.openIfChanged(deltaReader, deltaWriter);
        if (next != null) { deltaReader.close(); deltaReader = next; }
        for (Entry e : entries) {
            DocState before = delta.getOrDefault(e.work().id(), base.get(e.work().id()));
            if (before != null && before.eligible()) visibleCount--;
            if (e.work().eligible()) visibleCount++;
            delta.put(e.work().id(), new DocState(e.work().hash(), e.work().eligible()));
        }
        visibility = null;
    }

    static Document document(Entry entry, String layer) {
        SearchStore.Work work = entry.work();
        Document doc = new Document();
        doc.add(new StringField("key", Long.toString(work.id()), Field.Store.NO));
        doc.add(new LongPoint("id", work.id()));
        doc.add(new StoredField("id", work.id()));
        doc.add(new StringField("hash", work.hash(), Field.Store.YES));
        doc.add(new StringField("layer", layer, Field.Store.NO));
        doc.add(new StringField("eligible", work.eligible() ? "1" : "0", Field.Store.YES));
        if (work.eligible()) {
            if (entry.vector() == null) doc.add(new TextField("body", work.body(), Field.Store.NO));
            else {
                float[] vector = entry.vector().clone();
                VectorUtil.l2normalize(vector);
                doc.add(new KnnFloatVectorField("embedding", vector, VectorSimilarityFunction.DOT_PRODUCT));
            }
        }
        return doc;
    }

    synchronized List<Long> keywords(String text, int limit) throws IOException {
        ensureOpen();
        Query query = new QueryBuilder(queryAnalyzer).createBooleanQuery("body", text);
        if (query == null) return List.of();
        try (MultiReader reader = combined()) {
            IndexSearcher searcher = new IndexSearcher(reader);
            Query filtered = new BooleanQuery.Builder().add(query, BooleanClause.Occur.MUST)
                    .add(visibility(), BooleanClause.Occur.FILTER).build();
            return ids(searcher, searcher.search(filtered, limit));
        }
    }

    synchronized List<Long> vectors(float[] input, int limit) throws IOException {
        ensureOpen();
        float[] query = input.clone();
        VectorUtil.l2normalize(query);
        try (MultiReader reader = combined()) {
            IndexSearcher searcher = new IndexSearcher(reader);
            return ids(searcher, searcher.search(new KnnFloatVectorQuery("embedding", query, limit, visibility()), limit));
        }
    }

    private MultiReader combined() throws IOException {
        return new MultiReader(baseReader == null ? new IndexReader[]{deltaReader} : new IndexReader[]{baseReader, deltaReader}, false);
    }

    private Query visibility() {
        if (visibility != null) return visibility;
        List<Long> baseHidden = new ArrayList<>();
        List<Long> deltaHidden = new ArrayList<>();
        delta.forEach((id, newer) -> {
            DocState original = base.get(id);
            if (original != null) {
                if (!newer.equals(original)) baseHidden.add(id);
                else deltaHidden.add(id);
            }
        });
        BooleanQuery.Builder either = new BooleanQuery.Builder();
        if (baseReader != null) either.add(layer("base", baseHidden), BooleanClause.Occur.SHOULD);
        either.add(layer("delta", deltaHidden), BooleanClause.Occur.SHOULD);
        visibility = new BooleanQuery.Builder().add(either.build(), BooleanClause.Occur.FILTER)
                .add(new TermQuery(new Term("eligible", "1")), BooleanClause.Occur.FILTER).build();
        return visibility;
    }

    private Query layer(String layer, List<Long> hidden) {
        BooleanQuery.Builder query = new BooleanQuery.Builder().add(new TermQuery(new Term("layer", layer)), BooleanClause.Occur.FILTER);
        if (!hidden.isEmpty()) query.add(LongPoint.newSetQuery("id", hidden), BooleanClause.Occur.MUST_NOT);
        return query.build();
    }

    private static List<Long> ids(IndexSearcher searcher, TopDocs docs) throws IOException {
        List<Long> ids = new ArrayList<>();
        StoredFields fields = searcher.storedFields();
        for (ScoreDoc hit : docs.scoreDocs) ids.add(fields.document(hit.doc).getField("id").numericValue().longValue());
        return ids;
    }

    private static Map<Long, DocState> readStates(DirectoryReader reader) throws IOException {
        Map<Long, DocState> states = new HashMap<>();
        for (LeafReaderContext leaf : reader.leaves()) {
            StoredFields fields = leaf.reader().storedFields();
            for (int id = 0; id < leaf.reader().maxDoc(); id++) {
                if (leaf.reader().getLiveDocs() != null && !leaf.reader().getLiveDocs().get(id)) continue;
                Document doc = fields.document(id);
                states.put(doc.getField("id").numericValue().longValue(), new DocState(doc.get("hash"), "1".equals(doc.get("eligible"))));
            }
        }
        return states;
    }

    synchronized void installBase(Path path) throws IOException {
        ensureOpen();
        Directory directory = FSDirectory.open(path);
        DirectoryReader reader;
        Map<Long, DocState> states;
        try { reader = DirectoryReader.open(directory); }
        catch (IOException | RuntimeException e) { directory.close(); throw e; }
        try { states = readStates(reader); }
        catch (IOException | RuntimeException e) { IOUtils.closeWhileHandlingException(reader, directory); throw e; }
        try { IOUtils.close(baseReader, baseDirectory); }
        catch (IOException e) { IOUtils.closeWhileHandlingException(reader, directory); throw e; }
        baseDirectory = directory; baseReader = reader; base = states;
        recount(); visibility = null;
    }

    synchronized int count() { return visibleCount; }

    // Base only ever holds eligible docs; Delta also keeps ineligible tombstones, so both count eligibility.
    // ponytail: rescanned per status poll (~1/s); fold into recount() if the index ever outgrows that.
    synchronized int baseCount() { return (int) base.values().stream().filter(DocState::eligible).count(); }
    synchronized int deltaCount() { return (int) delta.values().stream().filter(DocState::eligible).count(); }

    private void recount() {
        visibleCount = (int) base.values().stream().filter(DocState::eligible).count();
        delta.forEach((id, state) -> {
            if (base.containsKey(id) && base.get(id).eligible()) visibleCount--;
            if (state.eligible()) visibleCount++;
        });
    }

    synchronized Map<Long, String> visibleHashes() {
        Map<Long, String> result = new HashMap<>();
        base.forEach((id, state) -> { if (state.eligible()) result.put(id, state.hash()); });
        delta.forEach((id, state) -> { if (state.eligible()) result.put(id, state.hash()); else result.remove(id); });
        return result;
    }

    synchronized float[] vector(long id, String hash) throws IOException {
        ensureOpen();
        try (MultiReader reader = combined()) { return readVector(reader, id, hash); }
    }

    private static float[] readVector(IndexReader reader, long id, String hash) throws IOException {
        IndexSearcher searcher = new IndexSearcher(reader);
        Query query = new BooleanQuery.Builder()
                .add(new TermQuery(new Term("key", Long.toString(id))), BooleanClause.Occur.FILTER)
                .add(new TermQuery(new Term("hash", hash)), BooleanClause.Occur.FILTER).build();
        TopDocs hits = searcher.search(query, 1);
        if (hits.scoreDocs.length == 0) return null;
        int doc = hits.scoreDocs[0].doc;
        LeafReaderContext leaf = reader.leaves().get(ReaderUtil.subIndex(doc, reader.leaves()));
        FloatVectorValues values = leaf.reader().getFloatVectorValues("embedding");
        if (values == null) return null;
        KnnVectorValues.DocIndexIterator iterator = values.iterator();
        if (iterator.advance(doc - leaf.docBase) != doc - leaf.docBase) return null;
        return values.vectorValue(iterator.index()).clone();
    }

    synchronized void cleanCoveredDelta() throws IOException {
        List<Long> covered = new ArrayList<>();
        delta.forEach((id, state) -> { if (state.equals(base.get(id))) covered.add(id); });
        if (covered.isEmpty()) return;
        deltaWriter.deleteDocuments(LongPoint.newSetQuery("id", covered));
        deltaWriter.commit();
        DirectoryReader next = DirectoryReader.openIfChanged(deltaReader, deltaWriter);
        if (next != null) { deltaReader.close(); deltaReader = next; }
        covered.forEach(delta::remove);
        visibility = null;
    }

    synchronized void removeSupersededDelta(Map<Long, String> snapshot, SearchStore store) throws IOException, java.sql.SQLException {
        List<Long> remove = new ArrayList<>();
        for (var entry : delta.entrySet()) {
            String snapshotHash = snapshot.get(entry.getKey());
            if (snapshotHash == null || snapshotHash.equals(entry.getValue().hash())) continue;
            SearchStore.Work current = store.currentWork(entry.getKey());
            // Hashes are identities, not sequence numbers. Consult the committed source to decide
            // whether this differing Delta is older than Base or an update made during the build.
            if (current != null && snapshotHash.equals(current.hash())) remove.add(entry.getKey());
        }
        if (remove.isEmpty()) return;
        deltaWriter.deleteDocuments(LongPoint.newSetQuery("id", remove));
        deltaWriter.commit();
        DirectoryReader next = DirectoryReader.openIfChanged(deltaReader, deltaWriter);
        if (next != null) { deltaReader.close(); deltaReader = next; }
        remove.forEach(delta::remove);
        recount(); visibility = null;
    }

    static final class Builder implements AutoCloseable {
        private Directory directory;
        private final Analyzer analyzer = new JiebaAnalyzer();
        private IndexWriter writer;
        private DirectoryReader previous;
        private Map<Long, DocState> previousStates;

        Builder(Path path) throws IOException {
            try {
                directory = FSDirectory.open(path);
                writer = new IndexWriter(directory, SearchVectorCodec.configure(new IndexWriterConfig(analyzer)));
                previous = DirectoryReader.open(writer);
                previousStates = readStates(previous);
            } catch (IOException | RuntimeException e) {
                IOUtils.closeWhileHandlingException(previous, writer, directory, analyzer);
                throw e;
            }
        }
        float[] vector(long id, String hash) throws IOException { return readVector(previous, id, hash); }
        void add(Entry entry) throws IOException {
            DocState saved = previousStates.get(entry.work().id());
            if (saved != null && saved.hash().equals(entry.work().hash()) && saved.eligible() == entry.work().eligible()) return;
            writer.updateDocument(new Term("key", Long.toString(entry.work().id())), document(entry, "base"));
        }
        void checkpoint() throws IOException { writer.commit(); }
        void validate(Map<Long, String> expected) throws IOException {
            writer.commit();
            try (DirectoryReader reader = DirectoryReader.open(writer)) {
                Map<Long, DocState> states = readStates(reader);
                if (states.size() != expected.size()) throw new IOException("Rebuild contains missing or unexpected notes");
                for (var entry : expected.entrySet()) {
                    DocState state = states.get(entry.getKey());
                    if (state == null || !state.eligible() || !state.hash().equals(entry.getValue()))
                        throw new IOException("Rebuild validation failed for note " + entry.getKey());
                }
                new IndexSearcher(reader).search(new MatchAllDocsQuery(), 1);
            }
        }
        @Override public void close() throws IOException {
            IOUtils.close(previous, writer, directory, analyzer);
        }
    }
    synchronized boolean hasBase() { return baseReader != null; }
    private void ensureOpen() { if (closed) throw new IllegalStateException("Search index is closed"); }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        IOUtils.close(deltaReader, deltaWriter, deltaDirectory, baseReader, baseDirectory, analyzer, queryAnalyzer);
    }
    record Entry(SearchStore.Work work, float[] vector) { }
    private record DocState(String hash, boolean eligible) { }
}
