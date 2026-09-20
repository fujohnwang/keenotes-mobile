package cn.keevol.keenotes.mobilefx.search;

import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

import static org.junit.Assert.*;

/** Opt-in capacity smoke test with synthetic local data, never a real provider/account. */
public class SearchScaleTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void oneHundredThousandNotes() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("keenotes.search.scale"));
        int size = 100_000;
        Path db = temp.getRoot().toPath().resolve("scale.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("CREATE TABLE notes_cache(id INTEGER PRIMARY KEY,content TEXT,encrypted_content TEXT)");
            c.setAutoCommit(false);
            try (PreparedStatement p = c.prepareStatement("INSERT INTO notes_cache VALUES(?,?,NULL)")) {
                for (int i = 1; i <= size; i++) {
                    p.setInt(1, i);
                    p.setString(2, "笔记 " + i + " 数据库缓存与计算机系统优化，周末公园散步。keyword" + i);
                    p.addBatch();
                    if (i % 1000 == 0) p.executeBatch();
                }
            }
            c.commit();
        }
        EmbeddingConfig config = new EmbeddingConfig(true, "http://synthetic.invalid/v1", "synthetic-768", "", "", "");
        EmbeddingClient synthetic = new EmbeddingClient() {
            private float[] vector(String text) {
                Random random = new Random(text.hashCode());
                float[] result = new float[768];
                for (int i = 0; i < result.length; i++) result[i] = random.nextFloat() - .5f;
                return result;
            }
            public List<float[]> embedDocuments(EmbeddingConfig c, List<String> notes) { return notes.stream().map(this::vector).toList(); }
            public float[] embedQuery(EmbeddingConfig c, String query) { return vector(query); }
        };
        try (LocalSearchEngine engine = new LocalSearchEngine(db, synthetic)) {
            engine.configure(config);
            long start = System.nanoTime();
            engine.rebuildKeywords(() -> false, (done, total) -> {});
            long keywordMs = (System.nanoTime() - start) / 1_000_000;
            assertEquals(size, engine.status(config).keywords());
            assertEquals(List.of(98765L), engine.search("keyword98765", EmbeddingConfig.disabled()).ids());
            start = System.nanoTime();
            engine.rebuildVectors(config, () -> false, (done, total) -> {
                if (done % 10000 == 0) System.out.println("Capacity vectors: " + done + "/" + total);
            });
            long vectorMs = (System.nanoTime() - start) / 1_000_000;
            assertEquals(size, engine.status(config).vectors());
            List<Long> times = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                start = System.nanoTime();
                assertFalse(engine.search("keyword" + (i * 4999 + 1), config).ids().isEmpty());
                times.add((System.nanoTime() - start) / 1_000_000);
            }
            Collections.sort(times);
            long bytes;
            try (var files = Files.walk(db.getParent().resolve("search"))) {
                bytes = files.filter(Files::isRegularFile).mapToLong(path -> {
                    try { return Files.size(path); } catch (Exception e) { throw new RuntimeException(e); }
                }).sum();
            }
            System.out.printf("CAPACITY notes=%d dimension=768 keywordBuildMs=%d vectorBuildMs=%d queryMedianMs=%d queryP95Ms=%d indexMiB=%.1f%n",
                    size, keywordMs, vectorMs, times.get(10), times.get(18), bytes / 1048576.0);
        }
    }
}
