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
 * - Desktop: 使用 SQLite JDBC (org.sqlite.JDBC)
 * - Android: 使用 Android 原生 SQLite API (通过反射)
 */
public class LocalCacheService {
    private static final String DB_NAME = "keenotes_cache.db";
    private static LocalCacheService instance;
    private String dbPathString;
    private Connection connection;  // Desktop only
    private final CryptoService cryptoService;
    private volatile boolean initialized = false;
    
    // 平台检测
    private final boolean isAndroid;
    
    // Android 原生数据库引用
    private Object androidDatabase;
    private Class<?> androidDbClass;
    
    // 用于追踪初始化步骤
    private volatile String initStep = "not started";

    private LocalCacheService() {
        this.cryptoService = new CryptoService();
        this.isAndroid = detectAndroid();
        this.dbPathString = resolveDbPath();
    }
    
    /**
     * 检测是否在Android平台上运行
     */
    private boolean detectAndroid() {
        try {
            String platform = com.gluonhq.attach.util.Platform.getCurrent().name();
            if ("ANDROID".equalsIgnoreCase(platform)) {
                return true;
            }
        } catch (Exception e) {
            // Gluon Attach 不可用
        }
        
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("android")) {
            return true;
        }
        
        return false;
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

    private String buildJdbcUrl() {
        return "jdbc:sqlite:" + dbPathString + "?journal_mode=WAL&synchronous=NORMAL&cache_size=10000&timeout=30000";
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
            
            if (isAndroid) {
                initAndroidDatabase(initLog);
            } else {
                initDesktopDatabase(initLog);
            }
            
            initLog.append("Database init completed OK");

        } catch (Exception e) {
            initLog.append("ERROR: ").append(e.getClass().getSimpleName())
                   .append(" - ").append(e.getMessage());
            throw new RuntimeException(initLog.toString(), e);
        }
    }
    
    private void initAndroidDatabase(StringBuilder initLog) throws Exception {
        initStep = "opening Android SQLite";
        initLog.append("Using Android native SQLite...\n");
        
        // 使用反射调用 Android SQLite API
        Class<?> sqliteDbClass = Class.forName("android.database.sqlite.SQLiteDatabase");
        java.lang.reflect.Method openOrCreate = sqliteDbClass.getMethod(
            "openOrCreateDatabase", String.class, int.class, 
            Class.forName("android.database.sqlite.SQLiteDatabase$CursorFactory"));
        
        Object androidDb = openOrCreate.invoke(null, dbPathString, 0, null);
        
        initStep = "Android SQLite opened";
        initLog.append("Android SQLite opened OK\n");
        
        // 创建表
        initStep = "creating tables (Android)";
        java.lang.reflect.Method execSQL = sqliteDbClass.getMethod("execSQL", String.class);
        
        execSQL.invoke(androidDb,
            "CREATE TABLE IF NOT EXISTS notes_cache (" +
            "  id INTEGER PRIMARY KEY, " +
            "  content TEXT NOT NULL, " +
            "  channel TEXT DEFAULT 'mobile', " +
            "  created_at DATETIME DEFAULT CURRENT_TIMESTAMP, " +
            "  encrypted_content TEXT, " +
            "  is_dirty INTEGER DEFAULT 0" +
            ")");
        
        execSQL.invoke(androidDb, "CREATE INDEX IF NOT EXISTS idx_cache_created_at ON notes_cache(created_at)");
        execSQL.invoke(androidDb, "CREATE INDEX IF NOT EXISTS idx_cache_content ON notes_cache(content)");
        
        execSQL.invoke(androidDb,
            "CREATE TABLE IF NOT EXISTS sync_state (" +
            "  id INTEGER PRIMARY KEY, " +
            "  last_sync_id INTEGER DEFAULT -1, " +
            "  last_sync_time DATETIME" +
            ")");
        
        execSQL.invoke(androidDb,
            "INSERT OR IGNORE INTO sync_state (id, last_sync_id) VALUES (1, -1)");
        
        this.androidDatabase = androidDb;
        this.androidDbClass = sqliteDbClass;
        
        initStep = "completed (Android native)";
        initLog.append("Android SQLite init completed OK\n");
    }
    
    private void initDesktopDatabase(StringBuilder initLog) throws Exception {
        initStep = "loading SQLite JDBC";
        initLog.append("Loading SQLite JDBC driver...\n");
        Class.forName("org.sqlite.JDBC");
        initLog.append("SQLite JDBC driver loaded OK\n");

        initStep = "building JDBC URL";
        String jdbcUrl = buildJdbcUrl();
        initLog.append("JDBC URL: ").append(jdbcUrl).append("\n");
        
        initStep = "connecting to DB";
        initLog.append("Connecting...\n");
        connection = DriverManager.getConnection(jdbcUrl);
        
        if (connection == null || connection.isClosed()) {
            throw new SQLException("Failed to establish database connection");
        }
        initStep = "connected OK";
        initLog.append("Connected OK\n");

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
        initStep = "completed (JDBC)";
    }


    // ==================== 数据操作方法 ====================
    
    public void batchInsertNotes(List<NoteData> notes) throws Exception {
        ensureInitialized();
        if (notes.isEmpty()) return;

        if (isAndroid) {
            batchInsertNotesAndroid(notes);
        } else {
            batchInsertNotesJdbc(notes);
        }
    }
    
    private void batchInsertNotesAndroid(List<NoteData> notes) throws Exception {
        java.lang.reflect.Method execSQL = androidDbClass.getMethod("execSQL", String.class);
        
        for (NoteData note : notes) {
            String sql = String.format(
                "INSERT OR REPLACE INTO notes_cache (id, content, channel, created_at, encrypted_content) VALUES (%d, '%s', '%s', '%s', '%s')",
                note.id,
                escapeSql(note.content),
                escapeSql(note.channel),
                escapeSql(note.createdAt),
                escapeSql(note.encryptedContent)
            );
            execSQL.invoke(androidDatabase, sql);
        }
    }
    
    private void batchInsertNotesJdbc(List<NoteData> notes) throws SQLException {
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

    public void insertNote(NoteData note) throws Exception {
        ensureInitialized();
        
        if (isAndroid) {
            java.lang.reflect.Method execSQL = androidDbClass.getMethod("execSQL", String.class);
            String sql = String.format(
                "INSERT OR REPLACE INTO notes_cache (id, content, channel, created_at, encrypted_content) VALUES (%d, '%s', '%s', '%s', '%s')",
                note.id,
                escapeSql(note.content),
                escapeSql(note.channel),
                escapeSql(note.createdAt),
                escapeSql(note.encryptedContent)
            );
            execSQL.invoke(androidDatabase, sql);
        } else {
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
    }

    public List<NoteData> searchNotes(String query) {
        ensureInitialized();
        List<NoteData> results = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) {
            return results;
        }

        if (isAndroid) {
            return searchNotesAndroid(query);
        } else {
            return searchNotesJdbc(query);
        }
    }
    
    private List<NoteData> searchNotesAndroid(String query) {
        List<NoteData> results = new ArrayList<>();
        try {
            java.lang.reflect.Method rawQuery = androidDbClass.getMethod("rawQuery", String.class, String[].class);
            String sql = "SELECT id, content, channel, created_at FROM notes_cache WHERE content LIKE '%" + escapeSql(query) + "%' ORDER BY created_at DESC LIMIT 100";
            Object cursor = rawQuery.invoke(androidDatabase, sql, null);
            results = cursorToNoteList(cursor);
        } catch (Exception e) {
            // Search failed
        }
        return results;
    }
    
    private List<NoteData> searchNotesJdbc(String query) {
        List<NoteData> results = new ArrayList<>();
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
        
        if (isAndroid) {
            return getNotesForReviewAndroid(days);
        } else {
            return getNotesForReviewJdbc(days);
        }
    }
    
    private List<NoteData> getNotesForReviewAndroid(int days) {
        List<NoteData> results = new ArrayList<>();
        try {
            java.lang.reflect.Method rawQuery = androidDbClass.getMethod("rawQuery", String.class, String[].class);
            String sql = "SELECT id, content, channel, created_at FROM notes_cache WHERE created_at >= datetime('now', '-" + days + " days') ORDER BY created_at DESC LIMIT 100";
            Object cursor = rawQuery.invoke(androidDatabase, sql, null);
            results = cursorToNoteList(cursor);
        } catch (Exception e) {
            // Review failed
        }
        return results;
    }
    
    private List<NoteData> getNotesForReviewJdbc(int days) {
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
        
        if (isAndroid) {
            return getAllNotesAndroid();
        } else {
            return getAllNotesJdbc();
        }
    }
    
    private List<NoteData> getAllNotesAndroid() {
        List<NoteData> results = new ArrayList<>();
        try {
            java.lang.reflect.Method rawQuery = androidDbClass.getMethod("rawQuery", String.class, String[].class);
            String sql = "SELECT id, content, channel, created_at FROM notes_cache ORDER BY created_at DESC";
            Object cursor = rawQuery.invoke(androidDatabase, sql, null);
            results = cursorToNoteList(cursor);
        } catch (Exception e) {
            // getAllNotes failed
        }
        return results;
    }
    
    private List<NoteData> getAllNotesJdbc() {
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


    public void updateLastSyncId(long lastSyncId) throws Exception {
        ensureInitialized();
        
        if (isAndroid) {
            java.lang.reflect.Method execSQL = androidDbClass.getMethod("execSQL", String.class);
            String sql = "UPDATE sync_state SET last_sync_id = " + lastSyncId + ", last_sync_time = datetime('now') WHERE id = 1";
            execSQL.invoke(androidDatabase, sql);
        } else {
            String sql = "UPDATE sync_state SET last_sync_id = ?, last_sync_time = datetime('now') WHERE id = 1";
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setLong(1, lastSyncId);
                pstmt.executeUpdate();
            }
        }
    }

    public long getLastSyncId() {
        ensureInitialized();
        
        if (isAndroid) {
            return getLastSyncIdAndroid();
        } else {
            return getLastSyncIdJdbc();
        }
    }
    
    private long getLastSyncIdAndroid() {
        try {
            java.lang.reflect.Method rawQuery = androidDbClass.getMethod("rawQuery", String.class, String[].class);
            String sql = "SELECT last_sync_id FROM sync_state WHERE id = 1";
            Object cursor = rawQuery.invoke(androidDatabase, sql, null);
            
            Class<?> cursorClass = Class.forName("android.database.Cursor");
            java.lang.reflect.Method moveToFirst = cursorClass.getMethod("moveToFirst");
            java.lang.reflect.Method getLong = cursorClass.getMethod("getLong", int.class);
            java.lang.reflect.Method close = cursorClass.getMethod("close");
            
            if ((Boolean) moveToFirst.invoke(cursor)) {
                long result = (Long) getLong.invoke(cursor, 0);
                close.invoke(cursor);
                return result;
            }
            close.invoke(cursor);
        } catch (Exception e) {
            // Get last sync ID failed
        }
        return -1;
    }
    
    private long getLastSyncIdJdbc() {
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
        
        if (isAndroid) {
            return getLastSyncTimeAndroid();
        } else {
            return getLastSyncTimeJdbc();
        }
    }
    
    private String getLastSyncTimeAndroid() {
        try {
            java.lang.reflect.Method rawQuery = androidDbClass.getMethod("rawQuery", String.class, String[].class);
            String sql = "SELECT last_sync_time FROM sync_state WHERE id = 1";
            Object cursor = rawQuery.invoke(androidDatabase, sql, null);
            
            Class<?> cursorClass = Class.forName("android.database.Cursor");
            java.lang.reflect.Method moveToFirst = cursorClass.getMethod("moveToFirst");
            java.lang.reflect.Method getString = cursorClass.getMethod("getString", int.class);
            java.lang.reflect.Method close = cursorClass.getMethod("close");
            
            if ((Boolean) moveToFirst.invoke(cursor)) {
                String result = (String) getString.invoke(cursor, 0);
                close.invoke(cursor);
                return result;
            }
            close.invoke(cursor);
        } catch (Exception e) {
            // Get last sync time failed
        }
        return null;
    }
    
    private String getLastSyncTimeJdbc() {
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
        
        if (isAndroid) {
            return getLocalNoteCountAndroid();
        } else {
            return getLocalNoteCountJdbc();
        }
    }
    
    private int getLocalNoteCountAndroid() {
        try {
            java.lang.reflect.Method rawQuery = androidDbClass.getMethod("rawQuery", String.class, String[].class);
            String sql = "SELECT COUNT(*) FROM notes_cache";
            Object cursor = rawQuery.invoke(androidDatabase, sql, null);
            
            Class<?> cursorClass = Class.forName("android.database.Cursor");
            java.lang.reflect.Method moveToFirst = cursorClass.getMethod("moveToFirst");
            java.lang.reflect.Method getInt = cursorClass.getMethod("getInt", int.class);
            java.lang.reflect.Method close = cursorClass.getMethod("close");
            
            if ((Boolean) moveToFirst.invoke(cursor)) {
                int result = (Integer) getInt.invoke(cursor, 0);
                close.invoke(cursor);
                return result;
            }
            close.invoke(cursor);
        } catch (Exception e) {
            // Get note count failed
        }
        return 0;
    }
    
    private int getLocalNoteCountJdbc() {
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
            if (isAndroid && androidDatabase != null) {
                java.lang.reflect.Method close = androidDbClass.getMethod("close");
                close.invoke(androidDatabase);
            } else if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (Exception e) {
            // Failed to close database
        }
    }

    public void resetSyncState() {
        ensureInitialized();
        
        try {
            if (isAndroid) {
                java.lang.reflect.Method execSQL = androidDbClass.getMethod("execSQL", String.class);
                execSQL.invoke(androidDatabase, "UPDATE sync_state SET last_sync_id = -1, last_sync_time = NULL WHERE id = 1");
            } else {
                try (Statement stmt = connection.createStatement()) {
                    stmt.executeUpdate("UPDATE sync_state SET last_sync_id = -1, last_sync_time = NULL WHERE id = 1");
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to reset sync state", e);
        }
    }

    public void clearAllData() {
        ensureInitialized();
        
        try {
            if (isAndroid) {
                java.lang.reflect.Method execSQL = androidDbClass.getMethod("execSQL", String.class);
                execSQL.invoke(androidDatabase, "DELETE FROM notes_cache");
                execSQL.invoke(androidDatabase, "UPDATE sync_state SET last_sync_id = -1, last_sync_time = NULL WHERE id = 1");
            } else {
                try (Statement stmt = connection.createStatement()) {
                    stmt.executeUpdate("DELETE FROM notes_cache");
                    stmt.executeUpdate("UPDATE sync_state SET last_sync_id = -1, last_sync_time = NULL WHERE id = 1");
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to clear cache data", e);
        }
    }
    
    // ==================== 辅助方法 ====================
    
    private String escapeSql(String value) {
        if (value == null) return "";
        return value.replace("'", "''");
    }
    
    private List<NoteData> cursorToNoteList(Object cursor) throws Exception {
        List<NoteData> results = new ArrayList<>();
        
        Class<?> cursorClass = Class.forName("android.database.Cursor");
        java.lang.reflect.Method moveToNext = cursorClass.getMethod("moveToNext");
        java.lang.reflect.Method getLong = cursorClass.getMethod("getLong", int.class);
        java.lang.reflect.Method getString = cursorClass.getMethod("getString", int.class);
        java.lang.reflect.Method close = cursorClass.getMethod("close");
        
        while ((Boolean) moveToNext.invoke(cursor)) {
            results.add(new NoteData(
                (Long) getLong.invoke(cursor, 0),
                (String) getString.invoke(cursor, 1),
                (String) getString.invoke(cursor, 2),
                (String) getString.invoke(cursor, 3),
                null
            ));
        }
        close.invoke(cursor);
        
        return results;
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
