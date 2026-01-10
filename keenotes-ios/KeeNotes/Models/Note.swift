import Foundation
import GRDB

/// Note entity for local storage
struct Note: Codable, FetchableRecord, PersistableRecord, Identifiable {
    var id: Int64
    var content: String
    var channel: String  // Source channel (e.g., mobile-ios, desktop-mac)
    var createdAt: String
    var syncedAt: Int64  // Timestamp when synced to local DB
    
    static let databaseTableName = "notes"
    
    enum Columns {
        static let id = Column(CodingKeys.id)
        static let content = Column(CodingKeys.content)
        static let channel = Column(CodingKeys.channel)
        static let createdAt = Column(CodingKeys.createdAt)
        static let syncedAt = Column(CodingKeys.syncedAt)
    }
    
    // Custom initializer for creating notes from sync
    init(id: Int64, content: String, channel: String = "default", createdAt: String, syncedAt: Int64? = nil) {
        self.id = id
        self.content = content
        self.channel = channel
        self.createdAt = createdAt
        self.syncedAt = syncedAt ?? Int64(Date().timeIntervalSince1970 * 1000)
    }
}

/// Sync state tracking
struct SyncState: Codable, FetchableRecord, PersistableRecord {
    static let databaseTableName = "sync_state"
    static let singletonId = 1
    
    var id: Int = singletonId
    var lastSyncId: Int64
    var lastSyncTime: String?
    
    enum Columns {
        static let id = Column(CodingKeys.id)
        static let lastSyncId = Column(CodingKeys.lastSyncId)
        static let lastSyncTime = Column(CodingKeys.lastSyncTime)
    }
}
