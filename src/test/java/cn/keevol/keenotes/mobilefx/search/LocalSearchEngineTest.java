package cn.keevol.keenotes.mobilefx.search;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.sql.*;
import java.util.List;

import static org.junit.Assert.*;

public class LocalSearchEngineTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private Path database() throws Exception {
        Path db = temp.newFolder().toPath().resolve("notes.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE notes_cache(id INTEGER PRIMARY KEY, content TEXT, channel TEXT, created_at TEXT, encrypted_content TEXT)");
            SearchStore.ensureSchema(c);
        }
        return db;
    }

    private void sync(Path db, long id, String body, boolean eligible) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            c.setAutoCommit(false);
            try (PreparedStatement p = c.prepareStatement("INSERT OR REPLACE INTO notes_cache VALUES(?, ?, 'desktop', '2026-09-19', NULL)")) {
                p.setLong(1, id);
                p.setString(2, body);
                p.executeUpdate();
            }
            SearchStore.recordSynced(c, id, body, eligible);
            c.commit();
        }
    }

    @Test public void keywordSearchWorksWithoutProviderAndSurvivesRestart() throws Exception {
        Path db = database();
        sync(db, 10, "使用缓存优化数据库查询", true);
        sync(db, 11, "周末去公园散步", true);
        sync(db, 12, "中华人民共和国计算机科学研究院 CACHE systems", true);
        try (LocalSearchEngine engine = new LocalSearchEngine(db, EmbeddingClient.unavailable())) {
            engine.drainKeywords();
            assertEquals(List.of(10L), engine.search("数据库", EmbeddingConfig.disabled()).ids());
            assertEquals(List.of(12L), engine.search("计算机", EmbeddingConfig.disabled()).ids());
            assertEquals(List.of(12L), engine.search("cache", EmbeddingConfig.disabled()).ids());
            var substring = engine.search("ach", EmbeddingConfig.disabled());
            assertEquals(List.of(12L), substring.ids());
            assertEquals(java.util.Set.of(12L), substring.wildcardIds());
            assertTrue(substring.keywordIds().isEmpty());
        }
        try (LocalSearchEngine engine = new LocalSearchEngine(db, EmbeddingClient.unavailable())) {
            assertEquals(List.of(10L), engine.search("数据库", EmbeddingConfig.disabled()).ids());
        }
    }

    @Test public void rebuildUsesFixedSnapshotWhileLowerIdNewNoteIsImmediatelySearchable() throws Exception {
        Path db = database();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement s = c.createStatement()) {
            s.execute("INSERT INTO notes_cache VALUES(100,'历史数据库笔记','desktop','2026-09-19',NULL)");
        }
        try (LocalSearchEngine engine = new LocalSearchEngine(db, EmbeddingClient.unavailable())) {
            engine.rebuildKeywords(() -> false, (done, total) -> {
                if (done != 0) return;
                try {
                    sync(db, 5, "新增缓存笔记", true);
                    engine.drainKeywords();
                    assertEquals(List.of(5L), engine.search("缓存", EmbeddingConfig.disabled()).ids());
                    var historical = engine.search("数据库", EmbeddingConfig.disabled());
                    assertEquals(List.of(100L), historical.ids());
                    assertTrue("SQL already covers history, but the new Base is not published", historical.keywordIds().isEmpty());
                    // The snapshot is still building, so only the incremental layer reports the new note.
                    LocalSearchEngine.Layer building = engine.status(EmbeddingConfig.disabled()).keywords();
                    assertEquals(0, building.base());
                    assertEquals(1, building.delta());
                    assertFalse(building.built());
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            assertEquals(List.of(100L), engine.search("数据库", EmbeddingConfig.disabled()).ids());
            assertEquals(List.of(5L), engine.search("缓存", EmbeddingConfig.disabled()).ids());
            engine.rebuildKeywords(() -> false, (done, total) -> { });
            assertEquals(2, engine.search("笔记", EmbeddingConfig.disabled()).ids().size());
            // The second snapshot covers both notes, so the now-redundant Delta entry is dropped.
            LocalSearchEngine.Layer published = engine.status(EmbeddingConfig.disabled()).keywords();
            assertEquals(2, published.base());
            assertEquals(0, published.delta());
            assertTrue(published.built());
        }
    }

    @Test public void optionalVectorsReuseEmbeddingsAndDisabledSearchNeverCallsProvider() throws Exception {
        Path db = database();
        CountingEmbeddings client = new CountingEmbeddings();
        EmbeddingConfig on = new EmbeddingConfig(true, "http://localhost:11434/v1", "test", "", "", "");
        try (LocalSearchEngine engine = new LocalSearchEngine(db, client)) {
            engine.configure(on);
            sync(db, 1, "缓存数据库", true);
            sync(db, 2, "公园散步", true);
            engine.drainKeywords();
            engine.drainVectors(on);
            assertEquals(2, client.documents);
            assertTrue(engine.search("放松身体", EmbeddingConfig.disabled()).ids().isEmpty());
            assertEquals(0, client.queries);
            var keywordOnly = engine.search("缓存", EmbeddingConfig.disabled());
            assertEquals(java.util.Set.of(1L), keywordOnly.keywordIds());
            assertTrue(keywordOnly.semanticIds().isEmpty());
            var hybrid = engine.search("缓存", on);
            assertTrue(hybrid.keywordIds().contains(1L));
            assertTrue(hybrid.semanticIds().contains(1L));
            assertEquals(Long.valueOf(2), engine.search("放松身体", on).ids().getFirst());
            engine.rebuildVectors(on, () -> false, (done, total) -> { });
            assertEquals("Full rebuild must reuse matching vectors", 2, client.documents);
        }
    }

    private static class CountingEmbeddings implements EmbeddingClient {
        int documents;
        int queries;
        public List<float[]> embedDocuments(EmbeddingConfig config, List<String> inputs) {
            documents += inputs.size();
            return inputs.stream().map(s -> s.contains("散步") ? new float[]{0, 1} : new float[]{1, 0}).toList();
        }
        public float[] embedQuery(EmbeddingConfig config, String query) { queries++; return new float[]{0, 1}; }
    }

    @Test public void recoveredDecryptionOfOldIdIsNotHiddenByNewerBase() throws Exception {
        Path db = database();
        sync(db, 1, "ciphertext", false);
        sync(db, 100, "正常笔记", true);
        try (LocalSearchEngine engine = new LocalSearchEngine(db, EmbeddingClient.unavailable())) {
            engine.drainKeywords();
            engine.rebuildKeywords(() -> false, (done, total) -> { });
            assertTrue(engine.search("ciphertext", EmbeddingConfig.disabled()).ids().isEmpty());
            sync(db, 1, "恢复解密之后的数据库笔记", true);
            engine.drainKeywords();
            assertEquals(List.of(1L), engine.search("数据库", EmbeddingConfig.disabled()).ids());
        }
    }

    @Test public void cancellationLeavesPublishedBaseAndDeltaAvailableAndCanResume() throws Exception {
        Path db = database();
        sync(db, 1, "历史数据库", true);
        try (LocalSearchEngine engine = new LocalSearchEngine(db, EmbeddingClient.unavailable())) {
            engine.rebuildKeywords(() -> false, (done, total) -> { });
            sync(db, 2, "新的缓存", true);
            engine.drainKeywords();
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> engine.rebuildKeywords(() -> true, (done, total) -> { }));
            assertEquals(List.of(1L), engine.search("数据库", EmbeddingConfig.disabled()).ids());
            assertEquals(List.of(2L), engine.search("缓存", EmbeddingConfig.disabled()).ids());
            engine.rebuildKeywords(() -> false, (done, total) -> { });
            assertFalse(engine.search("缓存", EmbeddingConfig.disabled()).partial());
        }
    }

    @Test public void failedEmbeddingRemainsRetryableAndKeywordStillWorks() throws Exception {
        Path db = database();
        EmbeddingConfig config = new EmbeddingConfig(true, "http://localhost/v1", "test", "", "", "");
        java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        EmbeddingClient client = new EmbeddingClient() {
            public List<float[]> embedDocuments(EmbeddingConfig c, List<String> inputs) throws java.io.IOException {
                if (fail.get()) throw new java.io.IOException("unavailable");
                return inputs.stream().map(s -> new float[]{1, 0}).toList();
            }
            public float[] embedQuery(EmbeddingConfig c, String q) { return new float[]{1, 0}; }
        };
        try (LocalSearchEngine engine = new LocalSearchEngine(db, client)) {
            engine.configure(config);
            sync(db, 1, "数据库", true);
            engine.drainKeywords(); engine.drainVectors(config);
            assertEquals(1, engine.status(config).vectors().pending());
            assertEquals(List.of(1L), engine.search("数据库", config).ids());
            fail.set(false); engine.retryVectorFailures(); engine.drainVectors(config);
            assertEquals(0, engine.status(config).vectors().pending());
            assertEquals(List.of(1L), engine.search("relational storage", config).ids());
        }
    }

    @Test public void retryingOneIndexLeavesTheOtherIndexFailuresUntouched() throws Exception {
        Path db = database();
        EmbeddingConfig config = new EmbeddingConfig(true, "http://localhost/v1", "test", "", "", "");
        try (LocalSearchEngine engine = new LocalSearchEngine(db, new CountingEmbeddings())) {
            engine.configure(config);
            sync(db, 1, "数据库", true);
            SearchStore store = new SearchStore(db);
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement s = c.createStatement()) {
                s.executeUpdate("UPDATE search_pending SET attempts=3, retry_at=9999999999999, error='failed'");
            }
            engine.retryKeywordFailures();
            assertEquals(1, store.pending(SearchStore.KEYWORD, SearchStore.KEYWORD_PROFILE, 10).size());
            assertTrue(store.pending(SearchStore.VECTOR, config.profile(), 10).isEmpty());
            engine.drainKeywords();
            assertEquals(1, engine.status(config).vectors().failed());
            engine.retryVectorFailures();
            assertEquals(1, store.pending(SearchStore.VECTOR, config.profile(), 10).size());
            assertTrue(store.pending(SearchStore.KEYWORD, SearchStore.KEYWORD_PROFILE, 10).isEmpty());
            engine.drainVectors(config);
            assertEquals(0, engine.status(config).vectors().pending());
        }
    }

    @Test public void modelMigrationDoesNotDropAlreadySearchableDelta() throws Exception {
        Path db = database();
        EmbeddingConfig first = new EmbeddingConfig(true, "http://localhost/v1", "first", "", "", "");
        EmbeddingConfig next = new EmbeddingConfig(true, "http://localhost/v1", "next", "", "", "");
        try (LocalSearchEngine engine = new LocalSearchEngine(db, new CountingEmbeddings())) {
            engine.configure(first); sync(db, 1, "公园散步", true);
            engine.drainKeywords(); engine.drainVectors(first);
            engine.rebuildVectors(first, () -> false, (done, total) -> { });
            engine.configure(next);
            engine.rebuildVectors(next, () -> false, (done, total) -> {
                if (done != 0) return;
                try {
                    sync(db, 2, "河边散步", true);
                    engine.drainVectors(first); // Old profile becomes visible before the candidate Delta catches up.
                    assertTrue(engine.search("relax", next).ids().contains(2L));
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            assertTrue("Publishing the new profile must not hide a previously visible note", engine.search("relax", next).ids().contains(2L));
            sync(db, 3, "山间散步", true);
            assertEquals("Retired profiles must stop accumulating jobs", 0, new SearchStore(db).pendingCounts(SearchStore.VECTOR, first.profile())[0]);
        }
    }

    @Test public void clearRemovesOldIndexFilesAndCapturesImmediateSyncForConfiguredProvider() throws Exception {
        Path db = database();
        EmbeddingConfig config = new EmbeddingConfig(true, "http://localhost/v1", "test", "", "", "");
        try (LocalSearchEngine engine = new LocalSearchEngine(db, new CountingEmbeddings())) {
            engine.configure(config);
            sync(db, 1, "旧账号数据库", true);
            engine.drainKeywords(); engine.drainVectors(config);
            String oldEpoch = new SearchStore(db).epoch();
            Path oldIndex = db.getParent().resolve("search").resolve(oldEpoch);
            assertTrue(java.nio.file.Files.isDirectory(oldIndex));
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement s = c.createStatement()) {
                c.setAutoCommit(false);
                s.executeUpdate("DELETE FROM notes_cache"); SearchStore.clear(c); c.commit();
            }
            sync(db, 1, "新账号散步", true); // Before the background worker observes the account change.
            engine.drainKeywords(); engine.drainVectors(config);
            assertFalse(java.nio.file.Files.exists(oldIndex));
            assertTrue(engine.search("数据库", EmbeddingConfig.disabled()).ids().isEmpty());
            assertEquals(List.of(1L), engine.search("relax", config).ids());
        }
    }

    @Test public void interruptedBuildResumesAfterRestartAndMissingBaseCanBeRebuilt() throws Exception {
        Path db = database();
        sync(db, 1, "数据库恢复", true);
        try (LocalSearchEngine engine = new LocalSearchEngine(db, EmbeddingClient.unavailable())) {
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> engine.rebuildKeywords(() -> true, (done, total) -> { }));
        }
        SearchStore store = new SearchStore(db);
        try (LocalSearchEngine engine = new LocalSearchEngine(db, EmbeddingClient.unavailable())) {
            engine.rebuildKeywords(() -> false, (done, total) -> { });
        }
        Path base = db.getParent().resolve("search").resolve(store.epoch()).resolve("keyword")
                .resolve(SearchStore.KEYWORD_PROFILE).resolve(store.meta("base.keyword." + SearchStore.KEYWORD_PROFILE));
        try (var paths = java.nio.file.Files.walk(base)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(path);
        }
        try (LocalSearchEngine engine = new LocalSearchEngine(db, EmbeddingClient.unavailable())) {
            assertTrue(engine.search("数据库", EmbeddingConfig.disabled()).partial());
            engine.rebuildKeywords(() -> false, (done, total) -> { });
            assertEquals(List.of(1L), engine.search("数据库", EmbeddingConfig.disabled()).ids());
        }
    }

    @Test public void fusionUsesNoteIdsAndDeduplicatesEachRanking() {
        var scores = LocalSearchEngine.fuse(List.of(42L, 7L, 42L), List.of(9L, 42L), List.of(10L, 42L));
        assertEquals(4, scores.size());
        assertEquals(3.0 / 61 + 2.0 / 62 + 1.0 / 62, scores.get(42L), 1e-12);
        assertEquals(3.0 / 62, scores.get(7L), 1e-12);
        assertEquals(2.0 / 61, scores.get(9L), 1e-12);
        assertEquals(1.0 / 61, scores.get(10L), 1e-12);
    }

    @Test public void wildcardSearchCoversUnindexedHistoryAndPreservesLikeSemantics() throws Exception {
        Path db = database();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement s = c.createStatement()) {
            s.execute("INSERT INTO notes_cache VALUES(1,'Historical CACHE owners'' notes','desktop','2026-09-19',NULL)");
            s.execute("INSERT INTO notes_cache VALUES(2,'hidden ciphertext','desktop','2026-09-20','hidden ciphertext')");
            s.execute("INSERT INTO notes_cache VALUES(3,'   ','desktop','2026-09-21',NULL)");
        }
        sync(db, 4, "hidden decryption failure", false);
        try (LocalSearchEngine engine = new LocalSearchEngine(db, EmbeddingClient.unavailable())) {
            assertEquals(List.of(1L), engine.search("ach", EmbeddingConfig.disabled()).ids());
            assertEquals(List.of(1L), engine.search("owners'", EmbeddingConfig.disabled()).ids());
            assertEquals(List.of(1L), engine.search("C_C%E", EmbeddingConfig.disabled()).ids());
            assertTrue(engine.search("' OR 1=1 --", EmbeddingConfig.disabled()).ids().isEmpty());
            assertTrue(engine.search("hidden", EmbeddingConfig.disabled()).ids().isEmpty());
            assertEquals(List.of(1L), engine.search("%", EmbeddingConfig.disabled()).ids());
            assertTrue(engine.search("  ", EmbeddingConfig.disabled()).ids().isEmpty());
        }
    }

    @Test public void threeSearchBranchesContributeAndOverlapsAppearOnce() throws Exception {
        Path db = database();
        var config = new EmbeddingConfig(true, "http://localhost/v1", "test", "", "", "");
        try (LocalSearchEngine engine = new LocalSearchEngine(db, new CountingEmbeddings())) {
            engine.configure(config);
            sync(db, 1, "precision recall " + "context ".repeat(50), true);
            sync(db, 2, "precision recall", true);
            sync(db, 3, "precision context recall", true);
            sync(db, 4, "precision", true);
            sync(db, 5, "公园散步", true); // Best vector match, no literal/keyword match.
            engine.drainKeywords(); engine.drainVectors(config);
            engine.drainVectors(config); // The vector worker processes four notes per batch.
            var result = engine.search("precision recall", config);
            assertEquals(List.of(2L, 1L, 3L, 4L, 5L), result.ids());
            assertEquals(java.util.Set.of(1L, 2L), result.wildcardIds());
            assertEquals(java.util.Set.of(1L, 2L, 3L, 4L), result.keywordIds());
            assertTrue(result.semanticIds().containsAll(result.ids()));
        }
    }

    @Test public void combinedRecallCanOutrankSqlAndLimitAppliesAfterFusion() throws Exception {
        Path db = database();
        for (int id = 9; id <= 109; id++) sync(db, id, "note", true);
        var wildcards = java.util.stream.LongStream.rangeClosed(10, 109).boxed().toList();
        var scores = LocalSearchEngine.fuse(wildcards, List.of(9L), List.of(9L));
        assertTrue(scores.get(9L) > scores.get(109L));
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement s = c.createStatement()) {
            s.execute("UPDATE notes_cache SET created_at='2026-09-25' WHERE id=9");
        }
        var result = new SearchStore(db).rank(scores, 100);
        assertEquals(100, result.size());
        assertEquals("Equal relevance uses date rather than SQL membership", Long.valueOf(9), result.getFirst());
        assertEquals(Long.valueOf(10), result.get(1));
        assertFalse(result.contains(109L));
    }

    @Test public void scoreTiesUseDateThenIdWithMissingDatesLastBeforeLimiting() throws Exception {
        Path db = database();
        for (int id = 1; id <= 5; id++) sync(db, id, "note", true);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement s = c.createStatement()) {
            s.execute("UPDATE notes_cache SET created_at='2025-01-01' WHERE id=1");
            s.execute("UPDATE notes_cache SET created_at='2030-01-01' WHERE id=4");
            s.execute("UPDATE notes_cache SET created_at=NULL WHERE id=5");
        }
        var scores = java.util.Map.of(1L, 1.0, 2L, 1.0, 3L, 1.0, 4L, 0.5, 5L, 1.0);
        SearchStore store = new SearchStore(db);
        assertEquals(List.of(3L, 2L, 1L, 5L, 4L), store.rank(scores, 100));
        assertEquals(List.of(3L, 2L), store.rank(scores, 2));
    }

    @Test public void restartingDuringModelMigrationResolvesThePublishedProvidersCredentials() throws Exception {
        Path db = database();
        EmbeddingConfig first = new EmbeddingConfig(true, "http://old-provider/v1", "first", "secret", "", "");
        EmbeddingConfig next = new EmbeddingConfig(true, "http://new-provider/v1", "next", "new-secret", "", "");
        try (LocalSearchEngine engine = new LocalSearchEngine(db, new CountingEmbeddings())) {
            engine.configure(first); sync(db, 1, "公园散步", true);
            engine.rebuildVectors(first, () -> false, (done, total) -> {});
            engine.configure(next);
        }
        assertFalse(new SearchStore(db).meta("active.vector").contains("secret"));
        CountingEmbeddings client = new CountingEmbeddings() {
            @Override public float[] embedQuery(EmbeddingConfig config, String query) {
                assertEquals(first.profile(), config.profile());
                assertEquals("secret", config.apiKey());
                return super.embedQuery(config, query);
            }
        };
        try (LocalSearchEngine engine = new LocalSearchEngine(db, client, profile -> profile.equals(first.profile()) ? "secret" : "")) {
            engine.configure(next);
            assertEquals(List.of(1L), engine.search("relax", next).ids());
        }
    }

    @Test public void highDimensionVectorsRemainReadableAfterRestart() throws Exception {
        Path db = database();
        EmbeddingConfig config = new EmbeddingConfig(true, "http://localhost/v1", "3072-dimensions", "", "", "");
        EmbeddingClient client = new EmbeddingClient() {
            private float[] vector() { float[] vector = new float[3072]; vector[1000] = 1; return vector; }
            public List<float[]> embedDocuments(EmbeddingConfig c, List<String> inputs) { return inputs.stream().map(s -> vector()).toList(); }
            public float[] embedQuery(EmbeddingConfig c, String query) { return vector(); }
        };
        try (LocalSearchEngine engine = new LocalSearchEngine(db, client)) {
            engine.configure(config); sync(db, 1, "数据库", true);
            engine.drainVectors(config);
            engine.rebuildVectors(config, () -> false, (done, total) -> {});
            assertEquals(List.of(1L), engine.search("storage", config).ids());
        }
        try (LocalSearchEngine engine = new LocalSearchEngine(db, client)) {
            engine.configure(config);
            assertEquals(List.of(1L), engine.search("storage", config).ids());
        }
    }

    @Test public void rebuiltBaseSupersedesAnOlderDeltaVersionWithoutDiscardingNewerDelta() throws Exception {
        Path db = database();
        try (LocalSearchEngine engine = new LocalSearchEngine(db, EmbeddingClient.unavailable())) {
            sync(db, 1, "旧版本数据库", true); engine.drainKeywords();
            sync(db, 1, "当前版本缓存", true); // Worker has not drained the correction yet.
            engine.rebuildKeywords(() -> false, (done, total) -> {});
            assertEquals(List.of(1L), engine.search("缓存", EmbeddingConfig.disabled()).ids());
            assertTrue(engine.search("数据库", EmbeddingConfig.disabled()).ids().isEmpty());
            engine.rebuildKeywords(() -> false, (done, total) -> {
                if (done != 0) return;
                try { sync(db, 1, "构建中的网络修订", true); engine.drainKeywords(); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            assertEquals(List.of(1L), engine.search("网络", EmbeddingConfig.disabled()).ids());
            assertTrue(engine.search("缓存", EmbeddingConfig.disabled()).ids().isEmpty());
        }
    }
}
