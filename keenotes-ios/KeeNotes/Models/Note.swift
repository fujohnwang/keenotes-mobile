import Foundation
import GRDB

/// Note entity for local storage
struct Note: Codable, FetchableRecord, PersistableRecord, Identifiable {
    var id: Int64
    var content: String
    var createdAt: String
    
    static let databaseTableName = "notes"
    
    enum Columns {
        static let id = Column(CodingKeys.id)
        static let content = Column(CodingKeys.content)
        static let createdAt = Column(CodingKeys.createdAt)
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
