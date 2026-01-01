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
 * 
 * 平台支持：
 * - Desktop: 使用 SQLite JDBC (org.sqlite.JDBC)
 * - Android: 使用 SQLDroid (org.sqldroid.SQLDroidDriver)
 */
public class LocalCacheService {
    private static final String DB_NAME = "keenotes_cache.db";
    private static LocalCacheService instance;
    private String dbPathString;  // 使用String而不是Path，因为Android上Path可能有问题
    private Connection connection;
    private final CryptoService cryptoService;
    private volatile boolean initialized = false;
    
    // 平台检测
    private final boolean isAndroid;

    private LocalCacheService() {
        this.cryptoService = new CryptoService();
        this.isAndroid = detectAndroid();
        this.dbPathString = resolveDbPath();
        
        System.out.println("[LocalCache] Platform: " + (isAndroid ? "Android (SQLDroid)" : "Desktop (SQLite JDBC)"));
        System.out.println("[LocalCache] Database path: " + dbPathString);
    }
    
    /**
     * 检测是否在Android平台上运行
     */
    private boolean detectAndroid() {
        String vmName = System.getProperty("java.vm.name", "").toLowerCase();
        String vmVendor = System.getProperty("java.vm.vendor", "").toLowerCase();
        
        // GraalVM Native Image / Substrate VM 用于 Android
        boolean isSubstrate = vmName.contains("substrate") || vmVendor.contains("gluon");
        
        // 检查是否有Android特定的类
        boolean hasAndroidClasses = false;
        try {
            Class.forName("android.database.sqlite.SQLiteDatabase");
            hasAndroidClasses = true;
        } catch (ClassNotFoundException e) {
            // 不是Android
        }
        
        System.out.println("[LocalCache] VM: " + vmName + ", Vendor: " + vmVendor);
        System.out.println("[LocalCache] isSubstrate: " + isSubstrate + ", hasAndroidClasses: " + hasAndroidClasses);
        
        return isSubstrate || hasAndroidClasses;
    }

    /**
     * 获取单例实例
     * 注意：此方法返回实例，但不一定已完成数据库初始化
     * 建议使用ServiceManager来管理服务的生命周期
     */
    public static synchronized LocalCacheService getInstance() {
        if (instance == null) {
            instance = new LocalCacheService();
            // 不再在构造函数中立即初始化数据库
            // 而是延迟到首次需要时或由ServiceManager触发
        }
        return instance;
    }

    /**
     * 显式初始化数据库
     * 由ServiceManager在后台线程调用
     */
    public synchronized void initialize() {
        if (!initialized) {
            initDatabase();
            initialized = true;
        }
    }

    /**
     * 检查是否已初始化
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 构建JDBC URL
     * - Desktop: jdbc:sqlite:/path/to/db
     * - Android: jdbc:sqldroid:/path/to/db
     */
    private String buildJdbcUrl() {
        if (isAndroid) {
            // SQLDroid URL格式
            return "jdbc:sqldroid:" + dbPathString;
        } else {
            // SQLite JDBC URL格式，带优化参数
            return "jdbc:sqlite:" + dbPathString + "?journal_mode=WAL&synchronous=NORMAL&cache_size=10000&timeout=30000";
        }
    }

    /**
     * 确保数据库已初始化（懒加载）
     * 这个方法会阻塞直到初始化完成
     */
    private void ensureInitialized() {
        if (!initialized) {
            // 如果尚未初始化，尝试初始化
            // 但需要考虑可能有其他线程正在初始化的情况
            synchronized (this) {
                if (!initialized) {
                    initialize();
                }
            }
        }
    }

    private String resolveDbPath() {
        System.out.println("[LocalCache] Resolving database path...");
        System.out.println("[LocalCache] OS: " + System.getProperty("os.name"));
        System.out.println("[LocalCache] Java version: " + System.getProperty("java.version"));
        
        // 1. 优先使用 Gluon Attach StorageService (Android/iOS)
        try {
            Optional<File> privateStorage = StorageService.create()
                    .flatMap(StorageService::getPrivateStorage);
            
            if (privateStorage.isPresent()) {
                File storageDir = privateStorage.get();
                File dbFile = new File(storageDir, DB_NAME);
                System.out.println("[LocalCache] Using Gluon private storage: " + dbFile.getAbsolutePath());
                return dbFile.getAbsolutePath();
            }
        } catch (Exception e) {
            System.out.println("[LocalCache] Gluon storage not available: " + e.getMessage());
        }
        
        // 2. 桌面环境：使用用户主目录
        try {
            String userHome = System.getProperty("user.home");
            if (userHome != null && !userHome.isEmpty()) {
                Path path = Path.of(userHome, ".keenotes", DB_NAME);
                Files.createDirectories(path.getParent());
                System.out.println("[LocalCache] Using user home: " + path);
                return path.toString();
            }
        } catch (Exception e) {
            System.out.println("[LocalCache] User home not available: " + e.getMessage());
        }
        
        // 3. 回退到临时目录
        String tempDir = System.getProperty("java.io.tmpdir", "/tmp");
        String fallback = tempDir + File.separator + DB_NAME;
        System.out.println("[LocalCache] Using fallback path: " + fallback);
        return fallback;
    }

    private void initDatabase() {
        try {
            System.out.println("[LocalCache] Starting database initialization...");
            System.out.println("[LocalCache] Database path: " + dbPathString);
            System.out.println("[LocalCache] Platform: " + (isAndroid ? "Android" : "Desktop"));
            
            // 加载对应平台的JDBC驱动
            if (isAndroid) {
                // Android: 使用 SQLDroid
                try {
                    DriverManager.registerDriver((Driver) Class.forName("org.sqldroid.SQLDroidDriver").newInstance());
                    System.out.println("[LocalCache] SQLDroid driver registered successfully");
                } catch (Exception e) {
                    System.err.println("[LocalCache] SQLDroid driver not found: " + e.getMessage());
                    throw new RuntimeException("SQLDroid driver not available", e);
                }
            } else {
                // Desktop: 使用 SQLite JDBC
                try {
                    Class.forName("org.sqlite.JDBC");
                    System.out.println("[LocalCache] SQLite JDBC driver loaded successfully");
                } catch (ClassNotFoundException e) {
                    System.err.println("[LocalCache] SQLite JDBC driver not found: " + e.getMessage());
                    throw new RuntimeException("SQLite JDBC driver not available", e);
                }
            }

            // 构建JDBC URL
            String jdbcUrl = buildJdbcUrl();
            System.out.println("[LocalCache] JDBC URL: " + jdbcUrl);
            
            // 建立连接
            connection = DriverManager.getConnection(jdbcUrl);
            
            // 验证连接
            if (connection == null || connection.isClosed()) {
                throw new SQLException("Failed to establish database connection");
            }
            System.out.println("[LocalCache] Database connection established successfully");

            // 创建表
            Statement stmt = connection.createStatement();
            stmt.setQueryTimeout(30);
            
            System.out.println("[LocalCache] Creating tables...");
            
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS notes_cache (" +
                "  id INTEGER PRIMARY KEY, " +
                "  content TEXT NOT NULL, " +
                "  channel TEXT DEFAULT 'mobile', " +
                "  created_at DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                "  encrypted_content TEXT, " +
                "  is_dirty INTEGER DEFAULT 0" +
                ")");
            System.out.println("[LocalCache] notes_cache table created");

            // 创建索引
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_cache_created_at ON notes_cache(created_at)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_cache_content ON notes_cache(content)");
            System.out.println("[LocalCache] Indexes created");

            // 创建同步状态表
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS sync_state (" +
                "  id INTEGER PRIMARY KEY, " +
                "  last_sync_id INTEGER DEFAULT -1, " +
                "  last_sync_time DATETIME" +
                ")");
            System.out.println("[LocalCache] sync_state table created");

            // 初始化同步状态（如果不存在）
            stmt.executeUpdate(
                "INSERT OR IGNORE INTO sync_state (id, last_sync_id) VALUES (1, -1)");
            System.out.println("[LocalCache] sync_state initialized");

            stmt.close();
            System.out.println("[LocalCache] Database initialization completed successfully");

        } catch (SQLException e) {
            System.err.println("[LocalCache] Database initialization failed!");
            System.err.println("[LocalCache] Error: " + e.getMessage());
            System.err.println("[LocalCache] Database path: " + dbPathString);
            e.printStackTrace();
            throw new RuntimeException("Database initialization failed", e);
        } catch (Exception e) {
            System.err.println("[LocalCache] Unexpected error during database initialization: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Unexpected database initialization error", e);
        }
    }

    /**
     * 批量插入或更新笔记
     * @param notes 笔记列表
     */
    public void batchInsertNotes(List<NoteData> notes) throws SQLException {
        ensureInitialized();
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
        ensureInitialized();
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
        ensureInitialized();
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
        ensureInitialized();
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
        ensureInitialized();
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
        ensureInitialized();
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
        ensureInitialized();
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
        ensureInitialized();
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
        ensureInitialized();
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
     * 重置同步状态（保留数据，只重置同步状态）
     */
    public void resetSyncState() {
        ensureInitialized();
        System.out.println("[LocalCache] Resetting sync state (keeping data)...");

        try {
            // 只重置 sync_state，保留笔记数据
            String resetSync = "UPDATE sync_state SET last_sync_id = -1, last_sync_time = NULL WHERE id = 1";
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate(resetSync);
            }
            System.out.println("[LocalCache] Sync state reset complete (data preserved)");
        } catch (SQLException e) {
            System.err.println("[LocalCache] Failed to reset sync state: " + e.getMessage());
            throw new RuntimeException("Failed to reset sync state", e);
        }
    }

    /**
     * 完全清理所有缓存数据
     */
    public void clearAllData() {
        ensureInitialized();
        System.out.println("[LocalCache] Clearing all cache data...");

        try {
            // 清空笔记表
            String clearNotes = "DELETE FROM notes_cache";
            try (Statement stmt = connection.createStatement()) {
                int deleted = stmt.executeUpdate(clearNotes);
                System.out.println("[LocalCache] Cleared " + deleted + " cached notes");
            }

            // 重置 sync_state
            String resetSync = "UPDATE sync_state SET last_sync_id = -1, last_sync_time = NULL WHERE id = 1";
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate(resetSync);
            }

            System.out.println("[LocalCache] All cache data cleared");
        } catch (SQLException e) {
            System.err.println("[LocalCache] Failed to clear cache data: " + e.getMessage());
            throw new RuntimeException("Failed to clear cache data", e);
        }
    }

    /**
     * 重置同步状态（用于调试或强制重新同步）
     * @deprecated 使用 clearAllData() 代替
     */
    @Deprecated
    public void resetSyncState_Legacy() throws SQLException {
        clearAllData();
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
