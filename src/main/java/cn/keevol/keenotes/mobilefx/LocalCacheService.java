package cn.keevol.keenotes.mobilefx;

import com.gluonhq.attach.storage.StorageService;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 本地缓存服务，管理客户端的SQLite数据库
 * - 存储从服务器同步的笔记（已解密内容，用于搜索）
 * - 存储同步状态（last_sync_id）
 * 
 * 平台支持：
 * - Desktop 和 Android 都使用 SQLite JDBC (org.sqlite.JDBC)
 * - Android 需要包含 ARM64 原生库
 */
public class LocalCacheService {
    private static final String DB_NAME = "keenotes_cache.db";
    private static LocalCacheService instance;
    private String dbPathString;
    private Connection connection;
    private final CryptoService cryptoService;
    private volatile boolean initialized = false;
    
    // 平台检测
    private final boolean isAndroid;
    
    // 用于追踪初始化步骤
    private volatile String initStep = "not started";

    private LocalCacheService() {
        this.cryptoService = new CryptoService();
        this.isAndroid = detectAndroid();
        this.dbPathString = resolveDbPath();
    }
    
    private boolean detectAndroid() {
        try {
            String platform = com.gluonhq.attach.util.Platform.getCurrent().name();
            return "ANDROID".equalsIgnoreCase(platform);
        } catch (Exception e) {
            return false;
        }
    }

    public static synchronized LocalCacheService getInstance() {
        if (instance == null) {
            instance = new LocalCacheService();
        }
        return instance;
    }
    
    public String getInitStep() {
        return initStep;
    }

    public synchronized void initialize() {
        if (!initialized) {
            initDatabase();
            initialized = true;
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    private void ensureInitialized() {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    initialize();
                }
            }
        }
    }

    private String resolveDbPath() {
        // 1. 优先使用 Gluon Attach StorageService (Android/iOS)
        try {
            Optional<File> privateStorage = StorageService.create()
                    .flatMap(StorageService::getPrivateStorage);
            
            if (privateStorage.isPresent()) {
                File storageDir = privateStorage.get();
                File dbFile = new File(storageDir, DB_NAME);
                return dbFile.getAbsolutePath();
            }
        } catch (Exception e) {
            // Gluon storage not available
        }
        
        // 2. 桌面环境：使用用户主目录
        try {
            String userHome = System.getProperty("user.home");
            if (userHome != null && !userHome.isEmpty()) {
                Path path = Path.of(userHome, ".keenotes", DB_NAME);
                Files.createDirectories(path.getParent());
                return path.toString();
            }
        } catch (Exception e) {
            // User home not available
        }
        
        // 3. 回退到临时目录
        String tempDir = System.getProperty("java.io.tmpdir", "/tmp");
        return tempDir + File.separator + DB_NAME;
    }

    private void initDatabase() {
        StringBuilder initLog = new StringBuilder();
        try {
            initStep = "checking platform";
            initLog.append("Platform: ").append(isAndroid ? "Android" : "Desktop").append("\n");
            initLog.append("DB Path: ").append(dbPathString).append("\n");
            
            // 构建 JDBC URL
            initStep = "building JDBC URL";
            String jdbcUrl;
            if (isAndroid) {
                // SQLDroid URL 格式
                jdbcUrl = "jdbc:sqldroid:" + dbPathString;
            } else {
                // SQLite JDBC URL 格式，带优化参数
                jdbcUrl = "jdbc:sqlite:" + dbPathString + "?journal_mode=WAL&synchronous=NORMAL&cache_size=10000&timeout=30000";
            }
            initLog.append("JDBC URL: ").append(jdbcUrl).append("\n");
            
            // 建立连接
            initStep = "connecting to DB";
            initLog.append("Connecting...\n");
            
            if (isAndroid) {
                // Android: 直接实例化 SQLDroid Driver 并创建连接
                // 避免 DriverManager 在 Native Image 中的问题
                initStep = "creating SQLDroid driver";
                initLog.append("Creating SQLDroid driver instance...\n");
                
                Class<?> driverClass = Class.forName("org.sqldroid.SQLDroidDriver");
                initLog.append("Driver class loaded: ").append(driverClass.getName()).append("\n");
                
                initStep = "instantiating driver";
                java.sql.Driver driver = (java.sql.Driver) driverClass.getDeclaredConstructor().newInstance();
                initLog.append("Driver instantiated OK\n");
                
                // 检查 driver 是否接受这个 URL
                initStep = "checking URL acceptance";
                boolean acceptsUrl = driver.acceptsURL(jdbcUrl);
                initLog.append("Driver accepts URL '").append(jdbcUrl).append("': ").append(acceptsUrl).append("\n");
                
                if (!acceptsUrl) {
                    // 尝试不同的 URL 格式
                    String altUrl = "jdbc:sqlite:" + dbPathString;
                    acceptsUrl = driver.acceptsURL(altUrl);
                    initLog.append("Driver accepts alt URL '").append(altUrl).append("': ").append(acceptsUrl).append("\n");
                    if (acceptsUrl) {
                        jdbcUrl = altUrl;
                    }
                }
                
                initStep = "calling driver.connect()";
                initLog.append("Calling driver.connect() with URL: ").append(jdbcUrl).append("\n");
                
                // 使用带超时的连接尝试
                final java.sql.Driver finalDriver = driver;
                final String finalUrl = jdbcUrl;
                final Connection[] connHolder = new Connection[1];
                final Exception[] errHolder = new Exception[1];
                
                Thread connectThread = new Thread(() -> {
                    try {
                        connHolder[0] = finalDriver.connect(finalUrl, new java.util.Properties());
                    } catch (Exception e) {
                        errHolder[0] = e;
                    }
                }, "SQLDroidConnect");
                connectThread.start();
                connectThread.join(10000); // 10秒超时
                
                if (connectThread.isAlive()) {
                    connectThread.interrupt();
                    throw new SQLException("SQLDroid connection timeout (10s). " +
                        "This usually means android.database.sqlite classes are not accessible in Native Image.");
                }
                
                if (errHolder[0] != null) {
                    throw errHolder[0];
                }
                
                connection = connHolder[0];
                initLog.append("driver.connect() returned: ").append(connection == null ? "NULL" : connection.getClass().getName()).append("\n");
                
                // 如果 SQLDroid 返回 null，尝试直接使用 SQLDroidConnection
                if (connection == null) {
                    initStep = "trying direct SQLDroidConnection";
                    initLog.append("Trying direct SQLDroidConnection instantiation...\n");
                    
                    // 首先尝试加载 org.sqldroid.SQLiteDatabase 看看具体错误
                    try {
                        initLog.append("Trying to load org.sqldroid.SQLiteDatabase...\n");
                        Class<?> sqliteDbClass = Class.forName("org.sqldroid.SQLiteDatabase");
                        initLog.append("org.sqldroid.SQLiteDatabase loaded OK: ").append(sqliteDbClass.getName()).append("\n");
                    } catch (ExceptionInInitializerError eiie) {
                        Throwable cause = eiie.getCause();
                        initLog.append("SQLiteDatabase init error: ").append(cause != null ? cause.getClass().getName() + " - " + cause.getMessage() : "unknown").append("\n");
                        if (cause != null && cause.getCause() != null) {
                            initLog.append("Root cause: ").append(cause.getCause().getClass().getName()).append(" - ").append(cause.getCause().getMessage()).append("\n");
                        }
                    } catch (NoClassDefFoundError ncdfe) {
                        initLog.append("SQLiteDatabase NoClassDefFoundError: ").append(ncdfe.getMessage()).append("\n");
                        // 尝试加载 Android SQLite 类
                        try {
                            Class<?> androidDb = Class.forName("android.database.sqlite.SQLiteDatabase");
                            initLog.append("android.database.sqlite.SQLiteDatabase loaded: ").append(androidDb.getName()).append("\n");
                        } catch (Throwable t) {
                            initLog.append("Android SQLiteDatabase load failed: ").append(t.getClass().getName()).append(" - ").append(t.getMessage()).append("\n");
                        }
                    } catch (Throwable t) {
                        initLog.append("SQLiteDatabase load error: ").append(t.getClass().getName()).append(" - ").append(t.getMessage()).append("\n");
                    }
                    
                    try {
                        // 尝试直接创建 SQLDroidConnection
                        Class<?> connClass = Class.forName("org.sqldroid.SQLDroidConnection");
                        initLog.append("SQLDroidConnection class loaded\n");
                        
                        // SQLDroidConnection(String url, Properties info)
                        java.lang.reflect.Constructor<?> ctor = connClass.getConstructor(String.class, java.util.Properties.class);
                        connection = (Connection) ctor.newInstance(dbPathString, new java.util.Properties());
                        initLog.append("Direct SQLDroidConnection created: ").append(connection != null ? "OK" : "NULL").append("\n");
                    } catch (java.lang.reflect.InvocationTargetException ite) {
                        // 获取真正的异常原因
                        Throwable cause = ite.getCause();
                        if (cause != null) {
                            initLog.append("Direct instantiation failed (cause): ").append(cause.getClass().getName())
                                   .append(" - ").append(cause.getMessage()).append("\n");
                            // 如果有更深层的原因
                            if (cause.getCause() != null) {
                                initLog.append("Root cause: ").append(cause.getCause().getClass().getName())
                                       .append(" - ").append(cause.getCause().getMessage()).append("\n");
                            }
                        } else {
                            initLog.append("Direct instantiation failed: InvocationTargetException with no cause\n");
                        }
                    } catch (Exception directEx) {
                        initLog.append("Direct instantiation failed: ").append(directEx.getClass().getSimpleName())
                               .append(" - ").append(directEx.getMessage()).append("\n");
                    }
                }
            } else {
                // Desktop: 使用标准 DriverManager
                Class.forName("org.sqlite.JDBC");
                connection = DriverManager.getConnection(jdbcUrl);
            }
            
            if (connection == null || connection.isClosed()) {
                throw new SQLException("Failed to establish database connection - connection is null or closed");
            }
            initStep = "connected OK";
            initLog.append("Connected OK\n");

            // 创建表
            initStep = "creating tables";
            Statement stmt = connection.createStatement();
            stmt.setQueryTimeout(30);
            
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS notes_cache (" +
                "  id INTEGER PRIMARY KEY, " +
                "  content TEXT NOT NULL, " +
                "  channel TEXT DEFAULT 'mobile', " +
                "  created_at DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                "  encrypted_content TEXT, " +
                "  is_dirty INTEGER DEFAULT 0" +
                ")");

            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_cache_created_at ON notes_cache(created_at)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_cache_content ON notes_cache(content)");

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS sync_state (" +
                "  id INTEGER PRIMARY KEY, " +
                "  last_sync_id INTEGER DEFAULT -1, " +
                "  last_sync_time DATETIME" +
                ")");

            stmt.executeUpdate(
                "INSERT OR IGNORE INTO sync_state (id, last_sync_id) VALUES (1, -1)");
            
            stmt.close();
            initStep = "completed";
            initLog.append("Database init completed OK");

        } catch (Exception e) {
            initLog.append("ERROR: ").append(e.getClass().getSimpleName())
                   .append(" - ").append(e.getMessage());
            throw new RuntimeException(initLog.toString(), e);
        }
    }


    // ==================== 数据操作方法（统一使用 JDBC）====================
    
    public void batchInsertNotes(List<NoteData> notes) throws SQLException {
        ensureInitialized();
        if (notes.isEmpty()) return;

        String sql = "INSERT OR REPLACE INTO notes_cache (id, content, channel, created_at, encrypted_content) VALUES (?, ?, ?, ?, ?)";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);

            for (NoteData note : notes) {
                pstmt.setLong(1, note.id);
                pstmt.setString(2, note.content);
                pstmt.setString(3, note.channel);
                pstmt.setString(4, note.createdAt);
                pstmt.setString(5, note.encryptedContent);
                pstmt.addBatch();
            }

            pstmt.executeBatch();
            connection.commit();
            connection.setAutoCommit(true);
        }
    }

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

    public List<NoteData> searchNotes(String query) {
        ensureInitialized();
        List<NoteData> results = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) {
            return results;
        }

        String sql = "SELECT id, content, channel, created_at FROM notes_cache WHERE content LIKE ? ORDER BY created_at DESC LIMIT 100";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, "%" + query + "%");
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
        } catch (SQLException e) {
            // Search failed
        }
        return results;
    }

    public List<NoteData> getNotesForReview(int days) {
        ensureInitialized();
        List<NoteData> results = new ArrayList<>();

        String sql = "SELECT id, content, channel, created_at FROM notes_cache WHERE created_at >= datetime('now', '-' || ? || ' days') ORDER BY created_at DESC LIMIT 100";

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
        } catch (SQLException e) {
            // Review failed
        }
        return results;
    }

    public List<NoteData> getAllNotes() {
        ensureInitialized();
        List<NoteData> results = new ArrayList<>();
        String sql = "SELECT id, content, channel, created_at FROM notes_cache ORDER BY created_at DESC";

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
        } catch (SQLException e) {
            // getAllNotes failed
        }
        return results;
    }

    public void updateLastSyncId(long lastSyncId) throws SQLException {
        ensureInitialized();
        String sql = "UPDATE sync_state SET last_sync_id = ?, last_sync_time = datetime('now') WHERE id = 1";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, lastSyncId);
            pstmt.executeUpdate();
        }
    }

    public long getLastSyncId() {
        ensureInitialized();
        String sql = "SELECT last_sync_id FROM sync_state WHERE id = 1";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            if (rs.next()) {
                return rs.getLong("last_sync_id");
            }
        } catch (SQLException e) {
            // Get last sync ID failed
        }
        return -1;
    }

    public String getLastSyncTime() {
        ensureInitialized();
        String sql = "SELECT last_sync_time FROM sync_state WHERE id = 1";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            if (rs.next()) {
                return rs.getString("last_sync_time");
            }
        } catch (SQLException e) {
            // Get last sync time failed
        }
        return null;
    }

    public int getLocalNoteCount() {
        ensureInitialized();
        String sql = "SELECT COUNT(*) FROM notes_cache";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            // Get note count failed
        }
        return 0;
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            // Failed to close database
        }
    }

    public void resetSyncState() {
        ensureInitialized();
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("UPDATE sync_state SET last_sync_id = -1, last_sync_time = NULL WHERE id = 1");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to reset sync state", e);
        }
    }

    public void clearAllData() {
        ensureInitialized();
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("DELETE FROM notes_cache");
            stmt.executeUpdate("UPDATE sync_state SET last_sync_id = -1, last_sync_time = NULL WHERE id = 1");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear cache data", e);
        }
    }

    // ==================== 数据传输对象 ====================
    
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
