package cn.keevol.keenotes.mobilefx.search;

import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Index bookkeeping in the existing cache DB. No remote cursor or business ID semantics are changed. */
public final class SearchStore {
    static final String KEYWORD = "keyword";
    static final String VECTOR = "vector";
    static final String KEYWORD_PROFILE = "jieba-1.0.2-index-search-v1";
    private final Path database;

    public SearchStore(Path database) throws SQLException {
        this.database = database;
        try (Connection c = connect()) { ensureSchema(c); }
    }

    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + database);
        try (Statement s = c.createStatement()) { s.execute("PRAGMA busy_timeout=10000"); }
        catch (SQLException e) { c.close(); throw e; }
        return c;
    }

    public static void ensureSchema(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS search_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS search_source (note_id INTEGER PRIMARY KEY, input_hash TEXT NOT NULL, eligible INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS search_profiles (profile TEXT PRIMARY KEY, enabled INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS search_pending (note_id INTEGER NOT NULL, family TEXT NOT NULL, profile TEXT NOT NULL, input_hash TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, retry_at INTEGER NOT NULL DEFAULT 0, error TEXT, PRIMARY KEY(note_id,family,profile))");
            s.execute("CREATE INDEX IF NOT EXISTS search_pending_ready ON search_pending(family,profile,retry_at)");
            s.execute("CREATE TABLE IF NOT EXISTS search_builds (build_key TEXT PRIMARY KEY, id TEXT NOT NULL, epoch TEXT NOT NULL, total INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS search_build_notes (build_id TEXT NOT NULL,note_id INTEGER NOT NULL,body TEXT NOT NULL,eligible INTEGER NOT NULL,PRIMARY KEY(build_id,note_id))");
        }
        try (PreparedStatement p = c.prepareStatement("INSERT OR IGNORE INTO search_meta VALUES('epoch',?)")) {
            p.setString(1, UUID.randomUUID().toString()); p.executeUpdate();
        }
    }

    /** Called inside the same transaction that writes a remotely synchronized note. */
    public static void recordSynced(Connection c, long id, String body, boolean eligible) throws SQLException {
        String hash = EmbeddingConfig.hash(body == null ? "" : body);
        eligible &= body != null && !body.isBlank();
        try (PreparedStatement p = c.prepareStatement("INSERT INTO search_source VALUES(?,?,?) ON CONFLICT(note_id) DO UPDATE SET input_hash=excluded.input_hash, eligible=excluded.eligible")) {
            p.setLong(1, id); p.setString(2, hash); p.setBoolean(3, eligible); p.executeUpdate();
        }
        enqueue(c, id, KEYWORD, KEYWORD_PROFILE, hash);
        try (PreparedStatement p = c.prepareStatement("SELECT profile FROM search_profiles WHERE enabled=1"); ResultSet rs = p.executeQuery()) {
            while (rs.next()) enqueue(c, id, VECTOR, rs.getString(1), hash);
        }
    }

    private static void enqueue(Connection c, long id, String family, String profile, String hash) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("INSERT INTO search_pending(note_id,family,profile,input_hash) VALUES(?,?,?,?) ON CONFLICT(note_id,family,profile) DO UPDATE SET input_hash=excluded.input_hash, attempts=0,retry_at=0,error=NULL WHERE search_pending.input_hash<>excluded.input_hash")) {
            p.setLong(1, id); p.setString(2, family); p.setString(3, profile); p.setString(4, hash); p.executeUpdate();
        }
    }

    public static void clear(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM search_pending"); s.executeUpdate("DELETE FROM search_source");
            // Provider configuration belongs to settings, not the cleared account. Keep capture enabled
            // so a sync committed before the worker notices the new epoch still gets a durable job.
            s.executeUpdate("DELETE FROM search_meta");
            s.executeUpdate("DELETE FROM search_build_notes"); s.executeUpdate("DELETE FROM search_builds");
        }
        try (PreparedStatement p = c.prepareStatement("INSERT INTO search_meta VALUES('epoch',?)")) {
            p.setString(1, UUID.randomUUID().toString()); p.executeUpdate();
        }
    }

    public String epoch() throws SQLException { return meta("epoch"); }
    String meta(String key) throws SQLException {
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement("SELECT value FROM search_meta WHERE key=?")) {
            p.setString(1, key);
            try (ResultSet rs = p.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    List<Work> pending(String family, String profile, int limit) throws SQLException {
        String sql = "SELECT p.note_id,n.content,p.input_hash,s.eligible,p.attempts FROM search_pending p JOIN notes_cache n ON n.id=p.note_id JOIN search_source s ON s.note_id=p.note_id WHERE p.family=? AND p.profile=? AND p.retry_at<=? AND p.attempts<3 ORDER BY p.retry_at,p.note_id LIMIT ?";
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, family); p.setString(2, profile); p.setLong(3, System.currentTimeMillis()); p.setInt(4, limit);
            List<Work> result = new ArrayList<>();
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) result.add(new Work(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getInt(5)));
            }
            return result;
        }
    }

    Work currentWork(long id) throws SQLException {
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement("SELECT n.content,s.input_hash,s.eligible FROM notes_cache n JOIN search_source s ON n.id=s.note_id WHERE n.id=?")) {
            p.setLong(1, id);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? new Work(id, rs.getString(1), rs.getString(2), rs.getBoolean(3), 0) : null;
            }
        }
    }

    void completed(String epoch, String family, String profile, Work work) throws SQLException {
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement("DELETE FROM search_pending WHERE note_id=? AND family=? AND profile=? AND input_hash=? AND EXISTS(SELECT 1 FROM search_meta WHERE key='epoch' AND value=?)")) {
            p.setLong(1, work.id()); p.setString(2, family); p.setString(3, profile); p.setString(4, work.hash()); p.setString(5, epoch); p.executeUpdate();
        }
    }

    void configure(EmbeddingConfig config, String activeProfile) throws SQLException {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) { s.executeUpdate("UPDATE search_profiles SET enabled=0"); }
            if (config.usable()) {
                try (PreparedStatement p = c.prepareStatement("INSERT INTO search_profiles VALUES(?,1) ON CONFLICT(profile) DO UPDATE SET enabled=1")) {
                    p.setString(1, config.profile()); p.executeUpdate();
                    if (activeProfile != null) { p.setString(1, activeProfile); p.executeUpdate(); }
                }
            }
            c.commit();
        }
    }

    void failed(String epoch, String family, String profile, Work work, String error) throws SQLException {
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement("UPDATE search_pending SET attempts=attempts+1,retry_at=?,error=? WHERE note_id=? AND family=? AND profile=? AND input_hash=? AND EXISTS(SELECT 1 FROM search_meta WHERE key='epoch' AND value=?)")) {
            p.setLong(1, System.currentTimeMillis() + (work.attempts() == 0 ? 2000 : 15000));
            p.setString(2, error); p.setLong(3, work.id()); p.setString(4, family); p.setString(5, profile);
            p.setString(6, work.hash()); p.setString(7, epoch); p.executeUpdate();
        }
    }

    public void retryFailures(String family) throws SQLException {
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement(
                "UPDATE search_pending SET attempts=0,retry_at=0,error=NULL WHERE family=? AND attempts>0")) {
            p.setString(1, family); p.executeUpdate();
        }
    }

    boolean current(String epoch, Work work) throws SQLException {
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement("SELECT 1 FROM search_source s,search_meta m WHERE s.note_id=? AND s.input_hash=? AND m.key='epoch' AND m.value=?")) {
            p.setLong(1, work.id()); p.setString(2, work.hash()); p.setString(3, epoch);
            try (ResultSet rs = p.executeQuery()) { return rs.next(); }
        }
    }

    Build beginBuild(String family, String profile) throws SQLException {
        String key = family + "/" + profile;
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try (PreparedStatement p = c.prepareStatement("SELECT id,epoch,total FROM search_builds WHERE build_key=?")) {
                p.setString(1, key);
                try (ResultSet rs = p.executeQuery()) {
                    if (rs.next()) return new Build(key, rs.getString(1), rs.getString(2), rs.getInt(3));
                }
            }
            String id = "base-" + UUID.randomUUID();
            String epoch;
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT value FROM search_meta WHERE key='epoch'")) { rs.next(); epoch = rs.getString(1); }
            int total;
            // A single INSERT SELECT establishes a fixed local snapshot, including bodies. Network work never holds this transaction.
            try (PreparedStatement p = c.prepareStatement("INSERT INTO search_build_notes SELECT ?,n.id,n.content,CASE WHEN trim(n.content)='' THEN 0 ELSE COALESCE(s.eligible,CASE WHEN n.content=n.encrypted_content THEN 0 ELSE 1 END) END FROM notes_cache n LEFT JOIN search_source s ON n.id=s.note_id")) {
                p.setString(1, id); total = p.executeUpdate();
            }
            try (PreparedStatement p = c.prepareStatement("INSERT INTO search_builds VALUES(?,?,?,?)")) {
                p.setString(1, key); p.setString(2, id); p.setString(3, epoch); p.setInt(4, total); p.executeUpdate();
            }
            c.commit();
            return new Build(key, id, epoch, total);
        }
    }

    List<Work> page(Build build, long after) throws SQLException {
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement("SELECT note_id,body,eligible FROM search_build_notes WHERE build_id=? AND note_id>? ORDER BY note_id LIMIT 128")) {
            p.setString(1, build.id()); p.setLong(2, after);
            List<Work> result = new ArrayList<>();
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) result.add(new Work(rs.getLong(1), rs.getString(2), EmbeddingConfig.hash(rs.getString(2)), rs.getBoolean(3), 0));
            }
            return result;
        }
    }

    void publish(Build build, String family, String profile, String vectorConfig) throws SQLException {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try (PreparedStatement p = c.prepareStatement("SELECT 1 FROM search_meta WHERE key='epoch' AND value=?")) {
                p.setString(1, build.epoch());
                try (ResultSet rs = p.executeQuery()) { if (!rs.next()) throw new SQLException("Search data changed during rebuild"); }
            }
            putMeta(c, "base." + family + "." + profile, build.id());
            if (vectorConfig != null) {
                putMeta(c, "active.vector", vectorConfig);
                try (PreparedStatement p = c.prepareStatement("UPDATE search_profiles SET enabled=CASE WHEN profile=? THEN 1 ELSE 0 END")) {
                    p.setString(1, profile); p.executeUpdate();
                }
                try (PreparedStatement p = c.prepareStatement("DELETE FROM search_pending WHERE family='vector' AND profile<>?")) {
                    p.setString(1, profile); p.executeUpdate();
                }
            }
            finishBuild(c, build);
            c.commit();
        }
    }

    private static void putMeta(Connection c, String key, String value) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("INSERT INTO search_meta VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            p.setString(1, key); p.setString(2, value); p.executeUpdate();
        }
    }

    private static void finishBuild(Connection c, Build build) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("DELETE FROM search_build_notes WHERE build_id=?")) { p.setString(1, build.id()); p.executeUpdate(); }
        try (PreparedStatement p = c.prepareStatement("DELETE FROM search_builds WHERE id=?")) { p.setString(1, build.id()); p.executeUpdate(); }
    }

    int[] pendingCounts(String family, String profile) throws SQLException {
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement("SELECT COUNT(*),COALESCE(SUM(CASE WHEN attempts>=3 THEN 1 ELSE 0 END),0) FROM search_pending WHERE family=? AND profile=?")) {
            p.setString(1, family); p.setString(2, profile);
            try (ResultSet rs = p.executeQuery()) { rs.next(); return new int[]{rs.getInt(1), rs.getInt(2)}; }
        }
    }

    record Build(String key, String id, String epoch, int total) { }

    record Work(long id, String body, String hash, boolean eligible, int attempts) { }
}
