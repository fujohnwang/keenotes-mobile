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
                t.column("createdAt", .text).notNull()
            }
            
            // Create sync_state table
            try db.create(table: SyncState.databaseTableName, ifNotExists: true) { t in
                t.column("id", .integer).primaryKey()
                t.column("lastSyncId", .integer).notNull()
                t.column("lastSyncTime", .text)
            }
            
            // Create index for faster queries
            try db.create(index: "idx_notes_createdAt", on: Note.databaseTableName, columns: ["createdAt"], ifNotExists: true)
        }
        
        // Update note count
        Task { @MainActor in
            self.noteCount = (try? await getNoteCount()) ?? 0
        }
    }
    
    // MARK: - Notes
    
    func insertNote(_ note: Note) async throws {
        try await dbQueue?.write { db in
            try note.save(db)
        }
        await MainActor.run {
            self.noteCount += 1
        }
    }
    
    func insertNotes(_ notes: [Note]) async throws {
        try await dbQueue?.write { db in
            for note in notes {
                try note.save(db)
            }
        }
        await MainActor.run {
            self.noteCount += notes.count
        }
    }
    
    func getAllNotes() async throws -> [Note] {
        try await dbQueue?.read { db in
            try Note.order(Note.Columns.createdAt.desc).fetchAll(db)
        } ?? []
    }
    
    func getRecentNotes(limit: Int = 20) async throws -> [Note] {
        try await dbQueue?.read { db in
            try Note.order(Note.Columns.createdAt.desc).limit(limit).fetchAll(db)
        } ?? []
    }
    
    func searchNotes(query: String) async throws -> [Note] {
        try await dbQueue?.read { db in
            try Note
                .filter(Note.Columns.content.like("%\(query)%"))
                .order(Note.Columns.createdAt.desc)
                .fetchAll(db)
        } ?? []
    }
    
    func getNoteCount() async throws -> Int {
        try await dbQueue?.read { db in
            try Note.fetchCount(db)
        } ?? 0
    }
    
    func deleteAllNotes() async throws {
        try await dbQueue?.write { db in
            try Note.deleteAll(db)
        }
        await MainActor.run {
            self.noteCount = 0
        }
    }
    
    // MARK: - Sync State
    
    func getSyncState() async throws -> SyncState? {
        try await dbQueue?.read { db in
            try SyncState.fetchOne(db, key: SyncState.singletonId)
        }
    }
    
    func getLastSyncId() async throws -> Int64 {
        let state = try await getSyncState()
        return state?.lastSyncId ?? -1
    }
    
    func updateSyncState(lastSyncId: Int64) async throws {
        let now = ISO8601DateFormatter().string(from: Date())
        let state = SyncState(lastSyncId: lastSyncId, lastSyncTime: now)
        try await dbQueue?.write { db in
            try state.save(db)
        }
    }
    
    func clearSyncState() async throws {
        try await dbQueue?.write { db in
            try SyncState.deleteAll(db)
        }
    }
    
    // MARK: - Clear All
    
    func clearAllData() async throws {
        try await dbQueue?.write { db in
            try Note.deleteAll(db)
            try SyncState.deleteAll(db)
        }
        await MainActor.run {
            self.noteCount = 0
        }
    }
}
