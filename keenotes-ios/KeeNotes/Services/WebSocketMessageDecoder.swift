import Foundation

/// Serial executor for JSON and Argon2/AES work. Never runs on MainActor and never
/// reads live settings: each message uses the password snapshot of its socket.
actor WebSocketMessageDecoder {
    enum Message {
        case batch([Note])
        case complete(cursor: Int64, total: Int)
        case realtime(Note)
        case ping
        case ignored
    }
    private let crypto = CryptoService(passwordProvider: { nil })

    func decode(_ data: Data, password: String?) throws -> Message {
        try Task.checkCancellation()
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any], let type = json["type"] as? String else { return .ignored }
        switch type {
        case "sync_batch":
            var notes: [Note] = []
            for payload in json["notes"] as? [[String: Any]] ?? [] {
                // A switch can stop a large batch between Argon2 operations. Never run
                // 50 concurrent 64 MB derivations or let cancelled work write to SQLite.
                try Task.checkCancellation()
                if let note = parse(payload, password: password) { notes.append(note) }
            }
            return .batch(notes)
        case "sync_complete": return .complete(cursor: json["last_sync_id"] as? Int64 ?? -1, total: json["total_synced"] as? Int ?? 0)
        case "realtime_update":
            if let payload = json["note"] as? [String: Any], let note = parse(payload, password: password) { return .realtime(note) }
            return .ignored
        case "ping": return .ping
        default: return .ignored
        }
    }
    private func parse(_ json: [String: Any], password: String?) -> Note? {
        guard let id = json["id"] as? Int64, let encrypted = json["content"] as? String else { return nil }
        let content = password.flatMap { try? crypto.decryptWithPassword(encrypted, password: $0) } ?? encrypted
        return Note(id: id, content: content, channel: json["channel"] as? String ?? "default",
                    createdAt: json["created_at"] as? String ?? json["createdAt"] as? String ?? "")
    }
}
