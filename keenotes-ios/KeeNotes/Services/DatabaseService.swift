import Foundation
import GRDB

/// SQLite database service using GRDB
class DatabaseService: ObservableObject {
    var dbQueue: DatabaseQueue?
    
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
            
            // Create pending_notes table for offline cache
            try db.create(table: PendingNote.databaseTableName, ifNotExists: true) { t in
                t.autoIncrementedPrimaryKey("id")
                t.column("content", .text).notNull()
                t.column("channel", .text).notNull().defaults(to: "mobile-ios")
                t.column("createdAt", .text).notNull()
            }
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
        _ = try await dbQueue.write { db in
            try note.insert(db, onConflict: .replace)
        }
        // Refresh actual count from database
        await refreshNoteCount()
    }
    
    func insertNotes(_ notes: [Note]) async throws {
        guard let dbQueue = dbQueue else {
            throw DatabaseError.notInitialized
        }
        
        let insertedCount = try await dbQueue.write { db in
            var count = 0
            for note in notes {
                do {
                    // Use insert with onConflict to handle duplicates
                    try note.insert(db, onConflict: .replace)
                    count += 1
                } catch {
                    print("[DB] Failed to insert note \(note.id): \(error)")
                }
            }
            return count
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
        
        return try await dbQueue.read { db in
            try Note
                .filter(sql: "createdAt >= datetime('now', '-' || ? || ' days')", arguments: [days])
                .order(Note.Columns.createdAt.desc)
                .fetchAll(db)
        }
    }
    
    func getNotesByPeriodPaged(days: Int, limit: Int, offset: Int) async throws -> [Note] {
        guard let dbQueue = dbQueue else {
            throw DatabaseError.notInitialized
        }
        
        if days <= 0 {
            // "All" - paginated
            return try await dbQueue.read { db in
                try Note
                    .order(Note.Columns.createdAt.desc)
                    .limit(limit, offset: offset)
                    .fetchAll(db)
            }
        }
        
        return try await dbQueue.read { db in
            try Note
                .filter(sql: "createdAt >= datetime('now', '-' || ? || ' days')", arguments: [days])
                .order(Note.Columns.createdAt.desc)
                .limit(limit, offset: offset)
                .fetchAll(db)
        }
    }
    
    func getNotesCountByPeriod(days: Int) async throws -> Int {
        guard let dbQueue = dbQueue else {
            throw DatabaseError.notInitialized
        }
        
        if days <= 0 {
            // "All"
            return try await getNoteCount()
        }
        
        return try await dbQueue.read { db in
            try Note
                .filter(sql: "createdAt >= datetime('now', '-' || ? || ' days')", arguments: [days])
                .fetchCount(db)
        }
    }
    
    func getNotesOnThisDay() async throws -> [Note] {
        guard let dbQueue = dbQueue else {
            throw DatabaseError.notInitialized
        }
        // Use local calendar to get today's month-day, then convert to UTC range for each past year.
        // DB stores UTC strings; we query by UTC date ranges that correspond to "today in local time".
        let calendar = Calendar.current
        let today = calendar.dateComponents([.year, .month, .day], from: Date())
        guard let month = today.month, let day = today.day, let currentYear = today.year else {
            return []
        }

        let monthDay = String(format: "%02d-%02d", month, day)

        // Build UTC start/end for each past year's local "today"
        // Local midnight → UTC, local end-of-day → UTC
        var conditions: [String] = []
        var arguments: [DatabaseValueConvertible] = []

        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
        formatter.timeZone = TimeZone(abbreviation: "UTC")

        let localTZ = TimeZone.current
        // Earliest year in DB is unlikely before 2000; scan from 2000 to last year
        for year in 2000..<currentYear {
            var startComponents = DateComponents()
            startComponents.year = year
            startComponents.month = month
            startComponents.day = day
            startComponents.hour = 0
            startComponents.minute = 0
            startComponents.second = 0
            startComponents.timeZone = localTZ

            var endComponents = startComponents
            endComponents.hour = 23
            endComponents.minute = 59
            endComponents.second = 59

            guard let startLocal = Calendar.current.date(from: startComponents),
                  let endLocal = Calendar.current.date(from: endComponents) else { continue }

            let startUTC = formatter.string(from: startLocal)
            let endUTC = formatter.string(from: endLocal)

            conditions.append("(createdAt >= ? AND createdAt <= ?)")
            arguments.append(startUTC)
            arguments.append(endUTC)
        }

        guard !conditions.isEmpty else { return [] }

        let sql = conditions.joined(separator: " OR ")
        return try await dbQueue.read { db in
            try Note
                .filter(sql: sql, arguments: StatementArguments(arguments))
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
        _ = try await dbQueue.write { db in
            try Note.deleteAll(db)
        }
        await MainActor.run {
            self.noteCount = 0
        }
        print("[DB] All notes deleted")
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
        _ = try await dbQueue.write { db in
            try SyncState.deleteAll(db)
        }
    }
    
    // MARK: - Pending Notes (Offline Cache)
    
    @Published var pendingNoteCount: Int = 0
    
    func insertPendingNote(content: String, channel: String = "mobile-ios") async throws {
        guard let dbQueue = dbQueue else { throw DatabaseError.notInitialized }
        
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
        formatter.timeZone = TimeZone(abbreviation: "UTC")
        let now = formatter.string(from: Date())
        
        _ = try await dbQueue.write { db in
            var note = PendingNote(content: content, channel: channel, createdAt: now)
            try note.insert(db)
        }
        await refreshPendingNoteCount()
    }
    
    func getPendingNotes() async throws -> [PendingNote] {
        guard let dbQueue = dbQueue else { throw DatabaseError.notInitialized }
        return try await dbQueue.read { db in
            try PendingNote.order(PendingNote.Columns.createdAt.asc).fetchAll(db)
        }
    }
    
    func deletePendingNote(id: Int64) async throws {
        guard let dbQueue = dbQueue else { throw DatabaseError.notInitialized }
        try await dbQueue.write { db in
            try db.execute(sql: "DELETE FROM pending_notes WHERE id = ?", arguments: [id])
        }
        await refreshPendingNoteCount()
    }
    
    func getPendingNoteCount() async throws -> Int {
        guard let dbQueue = dbQueue else { throw DatabaseError.notInitialized }
        return try await dbQueue.read { db in
            try PendingNote.fetchCount(db)
        }
    }
    
    func refreshPendingNoteCount() async {
        do {
            let count = try await getPendingNoteCount()
            await MainActor.run { self.pendingNoteCount = count }
        } catch {
            print("[DB] Failed to refresh pendingNoteCount: \(error)")
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
