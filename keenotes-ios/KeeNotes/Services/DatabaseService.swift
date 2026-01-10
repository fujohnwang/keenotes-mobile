import Foundation
import GRDB

/// SQLite database service using GRDB
class DatabaseService: ObservableObject {
    private var dbQueue: DatabaseQueue?
    
    @Published var noteCount: Int = 0
    
    var isInitialized: Bool {
        dbQueue != nil
    }
    
    func initialize() throws {
        let fileManager = FileManager.default
        let appSupport = try fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let dbPath = appSupport.appendingPathComponent("keenotes.db")
        
        dbQueue = try DatabaseQueue(path: dbPath.path)
        
        try dbQueue?.write { db in
            // Create notes table
            try db.create(table: Note.databaseTableName, ifNotExists: true) { t in
                t.column("id", .integer).primaryKey()
                t.column("content", .text).notNull()
                t.column("channel", .text).notNull().defaults(to: "default")
                t.column("createdAt", .text).notNull()
                t.column("syncedAt", .integer).notNull().defaults(to: 0)
            }
            
            // Migrate existing tables - add channel column if it doesn't exist
            if try db.tableExists(Note.databaseTableName) {
                let columns = try db.columns(in: Note.databaseTableName)
                let hasChannel = columns.contains { $0.name == "channel" }
                
                if !hasChannel {
                    print("[DB] Migrating notes table: adding channel column")
                    try db.alter(table: Note.databaseTableName) { t in
                        t.add(column: "channel", .text).notNull().defaults(to: "default")
                    }
                }
            }
            
            // Create sync_state table
            try db.create(table: SyncState.databaseTableName, ifNotExists: true) { t in
                t.column("id", .integer).primaryKey()
                t.column("lastSyncId", .integer).notNull()
                t.column("lastSyncTime", .text)
            }
            
            // Create index on createdAt for faster sorting/querying
            try db.create(index: "idx_notes_createdAt", on: Note.databaseTableName, columns: ["createdAt"], ifNotExists: true)
        }
        
        // Update note count
        Task { @MainActor in
            self.noteCount = (try? await getNoteCount()) ?? 0
        }
    }
    
    // MARK: - Notes
    
    func insertNote(_ note: Note) async throws {
        guard let dbQueue = dbQueue else {
            throw DatabaseError.notInitialized
        }
        try await dbQueue.write { db in
            try note.insert(db, onConflict: .replace)
        }
        // Refresh actual count from database
        await refreshNoteCount()
    }
    
    func insertNotes(_ notes: [Note]) async throws {
        guard let dbQueue = dbQueue else {
            throw DatabaseError.notInitialized
        }
        
        var insertedCount = 0
        try await dbQueue.write { db in
            for note in notes {
                do {
                    // Use insert with onConflict to handle duplicates
                    try note.insert(db, onConflict: .replace)
                    insertedCount += 1
                } catch {
                    print("[DB] Failed to insert note \(note.id): \(error)")
                }
            }
        }
        
        print("[DB] Successfully inserted \(insertedCount) of \(notes.count) notes")
        
        // Refresh actual count from database
        await refreshNoteCount()
    }
    
    func getAllNotes() async throws -> [Note] {
        guard let dbQueue = dbQueue else {
            throw DatabaseError.notInitialized
        }
        return try await dbQueue.read { db in
            try Note.order(Note.Columns.createdAt.desc).fetchAll(db)
        }
    }
    
    func getRecentNotes(limit: Int = 20) async throws -> [Note] {
        guard let dbQueue = dbQueue else {
            throw DatabaseError.notInitialized
        }
        return try await dbQueue.read { db in
            try Note.order(Note.Columns.createdAt.desc).limit(limit).fetchAll(db)
        }
    }
    
    func getNotesByPeriod(days: Int) async throws -> [Note] {
        guard let dbQueue = dbQueue else {
            throw DatabaseError.notInitialized
        }
        
        if days <= 0 {
            // "All" - return all notes
            return try await getAllNotes()
        }
        
        // Calculate cutoff date
        let calendar = Calendar.current
        let cutoffDate = calendar.date(byAdding: .day, value: -days, to: Date())!
        let formatter = ISO8601DateFormatter()
        let cutoffString = formatter.string(from: cutoffDate)
        
        return try await dbQueue.read { db in
            try Note
                .filter(Note.Columns.createdAt >= cutoffString)
                .order(Note.Columns.createdAt.desc)
                .fetchAll(db)
        }
    }
    
    func searchNotes(query: String) async throws -> [Note] {
        guard let dbQueue = dbQueue else {
            throw DatabaseError.notInitialized
        }
        return try await dbQueue.read { db in
            try Note
                .filter(Note.Columns.content.like("%\(query)%"))
                .order(Note.Columns.createdAt.desc)
                .fetchAll(db)
        }
    }
    
    func getNoteCount() async throws -> Int {
        guard let dbQueue = dbQueue else {
            throw DatabaseError.notInitialized
        }
        return try await dbQueue.read { db in
            try Note.fetchCount(db)
        }
    }
    
    func refreshNoteCount() async {
        do {
            let count = try await getNoteCount()
            await MainActor.run {
                self.noteCount = count
            }
            print("[DB] Refreshed noteCount: \(count)")
        } catch {
            print("[DB] Failed to refresh noteCount: \(error)")
        }
    }
    
    func deleteAllNotes() async throws {
        guard let dbQueue = dbQueue else {
            throw DatabaseError.notInitialized
        }
        try await dbQueue.write { db in
            try Note.deleteAll(db)
        }
        await MainActor.run {
            self.noteCount = 0
        }
    }
    
    // MARK: - Sync State
    
    func getSyncState() async throws -> SyncState? {
        guard let dbQueue = dbQueue else {
            throw DatabaseError.notInitialized
        }
        return try await dbQueue.read { db in
            try SyncState.fetchOne(db, key: SyncState.singletonId)
        }
    }
    
    func getLastSyncId() async throws -> Int64 {
        let state = try await getSyncState()
        return state?.lastSyncId ?? -1
    }
    
    func updateSyncState(lastSyncId: Int64) async throws {
        guard let dbQueue = dbQueue else {
            throw DatabaseError.notInitialized
        }
        let now = ISO8601DateFormatter().string(from: Date())
        let state = SyncState(lastSyncId: lastSyncId, lastSyncTime: now)
        try await dbQueue.write { db in
            try state.save(db)
        }
    }
    
    func clearSyncState() async throws {
        guard let dbQueue = dbQueue else {
            throw DatabaseError.notInitialized
        }
        try await dbQueue.write { db in
            try SyncState.deleteAll(db)
        }
    }
    
    // MARK: - Clear All
    
    func clearAllData() async throws {
        guard let dbQueue = dbQueue else {
            throw DatabaseError.notInitialized
        }
        try await dbQueue.write { db in
            try Note.deleteAll(db)
            try SyncState.deleteAll(db)
        }
        await MainActor.run {
            self.noteCount = 0
        }
        print("[DB] All data cleared")
    }
}

enum DatabaseError: Error, LocalizedError {
    case notInitialized
    
    var errorDescription: String? {
        switch self {
        case .notInitialized:
            return "Database not initialized"
        }
    }
}
