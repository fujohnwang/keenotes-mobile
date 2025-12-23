package cn.keevol.keenotes.mobilefx;

import com.gluonhq.attach.storage.StorageService;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 本地缓存服务，管理客户端的SQLite数据库
 * - 存储从服务器同步的笔记（已解密内容，用于搜索）
 * - 存储同步状态（last_sync_id）
 */
public class LocalCacheService {
    private static final String DB_NAME = "keenotes_cache.db";
    private static LocalCacheService instance;
    private final Path dbPath;
    private Connection connection;
    private final CryptoService cryptoService;

    private LocalCacheService() {
        this.cryptoService = new CryptoService();
        this.dbPath = resolveDbPath();
        initDatabase();
    }

    public static synchronized LocalCacheService getInstance() {
        if (instance == null) {
            instance = new LocalCacheService();
        }
        return instance;
    }

    private Path resolveDbPath() {
        return StorageService.create()
                .flatMap(StorageService::getPrivateStorage)
                .map(file -> file.toPath().resolve(DB_NAME))
                .orElseGet(() -> {
                    File userHomeDir = new File(System.getProperty("user.home"), ".keenotes");
                    userHomeDir.mkdirs();
                    return userHomeDir.toPath().resolve(DB_NAME);
                });
    }

    private void initDatabase() {
        try {
            // 确保目录存在
            if (dbPath.getParent() != null) {
                Files.createDirectories(dbPath.getParent());
            }

            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);

            // 创建本地缓存表
            Statement stmt = connection.createStatement();
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS notes_cache (" +
                "  id INTEGER PRIMARY KEY, " +
                "  content TEXT NOT NULL, " +  // 解密后的内容，用于本地搜索
                "  channel TEXT DEFAULT 'mobile', " +
                "  created_at DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                "  encrypted_content TEXT, " +  // 加密的原始内容（可选）
                "  is_dirty INTEGER DEFAULT 0" +
                ")");

            // 创建索引
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_cache_created_at ON notes_cache(created_at)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_cache_content ON notes_cache(content)");

            // 创建同步状态表
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS sync_state (" +
                "  id INTEGER PRIMARY KEY CHECK (id = 1), " +
                "  last_sync_id INTEGER DEFAULT -1, " +
                "  last_sync_time DATETIME" +
                ")");

            // 初始化同步状态（如果不存在）
            stmt.executeUpdate(
                "INSERT OR IGNORE INTO sync_state (id, last_sync_id) VALUES (1, -1)");

            stmt.close();

        } catch (SQLException | java.io.IOException e) {
            System.err.println("Failed to initialize local cache database: " + e.getMessage());
            throw new RuntimeException("Database initialization failed", e);
        }
    }

    /**
     * 批量插入或更新笔记
     * @param notes 笔记列表
     */
    public void batchInsertNotes(List<NoteData> notes) throws SQLException {
        if (notes.isEmpty()) {
            System.out.println("[DEBUG LocalCache] batchInsertNotes called with empty list");
            return;
        }

        System.out.println("[DEBUG LocalCache] batchInsertNotes: inserting " + notes.size() + " notes");

        String sql = "INSERT OR REPLACE INTO notes_cache (id, content, channel, created_at, encrypted_content) VALUES (?, ?, ?, ?, ?)";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);

            for (NoteData note : notes) {
                System.out.println("[DEBUG LocalCache] Inserting note: id=" + note.id + ", content=" + (note.content != null ? note.content.substring(0, Math.min(30, note.content.length())) : "null"));
                pstmt.setLong(1, note.id);
                pstmt.setString(2, note.content);  // 解密后的内容
                pstmt.setString(3, note.channel);
                pstmt.setString(4, note.createdAt);
                pstmt.setString(5, note.encryptedContent);  // 原始加密内容
                pstmt.addBatch();
            }

            int[] results = pstmt.executeBatch();
            System.out.println("[DEBUG LocalCache] Execute batch results: " + Arrays.toString(results));
            connection.commit();
            connection.setAutoCommit(true);
            System.out.println("[DEBUG LocalCache] Batch insert completed successfully");
        } catch (SQLException e) {
            System.err.println("[DEBUG LocalCache] Batch insert failed: " + e.getMessage());
            throw e;
        }
    }

    /**
     * 插入单条笔记（用于实时同步）
     */
    public void insertNote(NoteData note) throws SQLException {
        String sql = "INSERT OR REPLACE INTO notes_cache (id, content, channel, created_at, encrypted_content) VALUES (?, ?, ?, ?, ?)";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, note.id);
            pstmt.setString(2, note.content);
            pstmt.setString(3, note.channel);
            pstmt.setString(4, note.createdAt);
            pstmt.setString(5, note.encryptedContent);
            pstmt.executeUpdate();
        }
    }

    /**
     * 搜索笔记（本地执行）
     * @param query 搜索关键词
     * @return 匹配的笔记列表
     */
    public List<NoteData> searchNotes(String query) {
        List<NoteData> results = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) {
            return results;
        }

        String sql = "SELECT id, content, channel, created_at FROM notes_cache " +
                     "WHERE content LIKE ? ORDER BY created_at DESC LIMIT 100";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, "%" + query + "%");
            ResultSet rs = pstmt.executeQuery();

            while (rs.next()) {
                results.add(new NoteData(
                    rs.getLong("id"),
                    rs.getString("content"),
                    rs.getString("channel"),
                    rs.getString("created_at"),
                    null  // 不需要返回加密内容
                ));
            }
        } catch (SQLException e) {
            System.err.println("Search failed: " + e.getMessage());
        }

        return results;
    }

    /**
     * 获取回顾笔记（本地执行）
     * @param days 天数
     * @return 指定天数内的笔记
     */
    public List<NoteData> getNotesForReview(int days) {
        List<NoteData> results = new ArrayList<>();

        String sql = "SELECT id, content, channel, created_at FROM notes_cache " +
                     "WHERE created_at >= datetime('now', '-' || ? || ' days') " +
                     "ORDER BY created_at DESC LIMIT 100";

        System.out.println("[DEBUG LocalCache] getNotesForReview: days=" + days + ", sql=" + sql);

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setInt(1, days);
            ResultSet rs = pstmt.executeQuery();

            while (rs.next()) {
                results.add(new NoteData(
                    rs.getLong("id"),
                    rs.getString("content"),
                    rs.getString("channel"),
                    rs.getString("created_at"),
                    null
                ));
            }

            System.out.println("[DEBUG LocalCache] getNotesForReview: found " + results.size() + " notes");
            for (NoteData note : results) {
                System.out.println("[DEBUG LocalCache] Note: id=" + note.id + ", content=" + note.content.substring(0, Math.min(30, note.content.length())));
            }
        } catch (SQLException e) {
            System.err.println("Review failed: " + e.getMessage());
        }

        return results;
    }

    /**
     * 获取所有笔记（用于调试或导出）
     */
    public List<NoteData> getAllNotes() {
        List<NoteData> results = new ArrayList<>();
        String sql = "SELECT id, content, channel, created_at FROM notes_cache ORDER BY created_at DESC";

        System.out.println("[DEBUG LocalCache] getAllNotes: sql=" + sql);

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                results.add(new NoteData(
                    rs.getLong("id"),
                    rs.getString("content"),
                    rs.getString("channel"),
                    rs.getString("created_at"),
                    null
                ));
            }

            System.out.println("[DEBUG LocalCache] getAllNotes: found " + results.size() + " total notes");
            for (NoteData note : results) {
                System.out.println("[DEBUG LocalCache] Total note: id=" + note.id + ", created_at=" + note.createdAt + ", content=" + (note.content != null ? note.content.substring(0, Math.min(30, note.content.length())) : "null"));
            }
        } catch (SQLException e) {
            System.err.println("getAllNotes failed: " + e.getMessage());
        }

        return results;
    }

    /**
     * 更新最后同步ID
     */
    public void updateLastSyncId(long lastSyncId) throws SQLException {
        String sql = "UPDATE sync_state SET last_sync_id = ?, last_sync_time = datetime('now') WHERE id = 1";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, lastSyncId);
            pstmt.executeUpdate();
        }
    }

    /**
     * 获取最后同步ID
     * @return last_sync_id，如果从未同步返回-1
     */
    public long getLastSyncId() {
        String sql = "SELECT last_sync_id FROM sync_state WHERE id = 1";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            if (rs.next()) {
                return rs.getLong("last_sync_id");
            }
        } catch (SQLException e) {
            System.err.println("Get last sync ID failed: " + e.getMessage());
        }

        return -1;  // 默认值
    }

    /**
     * 获取最后同步时间
     */
    public String getLastSyncTime() {
        String sql = "SELECT last_sync_time FROM sync_state WHERE id = 1";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            if (rs.next()) {
                return rs.getString("last_sync_time");
            }
        } catch (SQLException e) {
            System.err.println("Get last sync time failed: " + e.getMessage());
        }

        return null;
    }

    /**
     * 获取本地笔记总数
     */
    public int getLocalNoteCount() {
        String sql = "SELECT COUNT(*) FROM notes_cache";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            System.err.println("Get note count failed: " + e.getMessage());
        }

        return 0;
    }

    /**
     * 关闭数据库连接
     */
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            System.err.println("Failed to close database: " + e.getMessage());
        }
    }

    /**
     * 重置同步状态（用于调试或强制重新同步）
     */
    public void resetSyncState() throws SQLException {
        System.out.println("[DEBUG LocalCache] Resetting sync state...");

        // 清空笔记表
        String clearNotes = "DELETE FROM notes_cache";
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate(clearNotes);
        }

        // 重置 sync_state
        String resetSync = "UPDATE sync_state SET last_sync_id = -1, last_sync_time = NULL WHERE id = 1";
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate(resetSync);
        }

        System.out.println("[DEBUG LocalCache] Sync state reset complete");
    }

    /**
     * 数据传输对象
     */
    public static class NoteData {
        public final long id;
        public final String content;
        public final String channel;
        public final String createdAt;
        public final String encryptedContent;

        public NoteData(long id, String content, String channel, String createdAt, String encryptedContent) {
            this.id = id;
            this.content = content;
            this.channel = channel;
            this.createdAt = createdAt;
            this.encryptedContent = encryptedContent;
        }

        @Override
        public String toString() {
            return String.format("NoteData{id=%d, content='%s', channel='%s', createdAt='%s'}",
                id, content != null ? content.substring(0, Math.min(20, content.length())) : "null",
                channel, createdAt);
        }
    }
}