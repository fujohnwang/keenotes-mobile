import Foundation
import Combine
import UIKit

/// WebSocket service for real-time sync
/// Matches JavaFX/Android WebSocketClientService logic
class WebSocketService: NSObject, ObservableObject {
    
    enum ConnectionState {
        case disconnected
        case connecting
        case connected
    }
    
    enum SyncStatus {
        case idle
        case syncing
        case completed
    }
    
    @Published var connectionState: ConnectionState = .disconnected
    @Published var syncStatus: SyncStatus = .idle
    
    private let settingsService: SettingsService
    private let cryptoService: CryptoService
    private let databaseService: DatabaseService
    
    private var webSocketTask: URLSessionWebSocketTask?
    private var session: URLSession!
    private let clientId = UUID().uuidString
    
    private var lastSyncId: Int64 = -1
    private var cachedPassword: String?
    
    // Batch sync progress tracking
    private var expectedBatches = 0
    private var receivedBatches = 0
    
    private var isConnecting = false
    private var reconnectTask: Task<Void, Never>?
    
    init(settingsService: SettingsService, cryptoService: CryptoService, databaseService: DatabaseService) {
        self.settingsService = settingsService
        self.cryptoService = cryptoService
        self.databaseService = databaseService
        super.init()
        
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 5
        config.timeoutIntervalForResource = 5
        self.session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }
    
    func connect() {
        guard connectionState != .connected && !isConnecting else {
            print("[WS] Already connected or connecting, skipping")
            return
        }
        
        guard !settingsService.endpointUrl.isEmpty, !settingsService.token.isEmpty else {
            print("[WS] Not configured, skipping connection")
            return
        }
        
        isConnecting = true
        
        Task {
            // Cache encryption password before connection
            cachedPassword = settingsService.encryptionPassword.isEmpty ? nil : settingsService.encryptionPassword
            print("[WS] Cached encryption password: \(cachedPassword != nil ? "yes" : "no")")
            
            // Load last sync ID
            lastSyncId = (try? await databaseService.getLastSyncId()) ?? -1
            print("[WS] Loaded lastSyncId: \(lastSyncId)")
            
            // Build WebSocket URL
            guard let wsUrl = buildWebSocketUrl() else {
                print("[WS] Failed to build WebSocket URL")
                isConnecting = false
                return
            }
            
            print("[WS] Connecting to: \(wsUrl)")
            await MainActor.run { connectionState = .connecting }
            
            var request = URLRequest(url: wsUrl)
            request.setValue("Bearer \(settingsService.token)", forHTTPHeaderField: "Authorization")
            
            // Build origin header
            if let components = URLComponents(string: settingsService.endpointUrl) {
                let scheme = components.scheme == "wss" || components.scheme == "https" ? "https" : "http"
                let origin = "\(scheme)://\(components.host ?? "")"
                request.setValue(origin, forHTTPHeaderField: "Origin")
            }
            
            webSocketTask = session.webSocketTask(with: request)
            webSocketTask?.resume()
            
            isConnecting = false
            await MainActor.run { connectionState = .connected }
            
            // Send handshake
            sendHandshake()
            
            // Start receiving messages
            receiveMessage()
        }
    }
    
    func disconnect() {
        reconnectTask?.cancel()
        webSocketTask?.cancel(with: .goingAway, reason: nil)
        webSocketTask = nil
        Task { @MainActor in
            connectionState = .disconnected
            syncStatus = .idle
            UIApplication.shared.isIdleTimerDisabled = false
        }
    }
    
    func resetState() {
        lastSyncId = -1
        cachedPassword = nil
        expectedBatches = 0
        receivedBatches = 0
        Task { @MainActor in
            syncStatus = .idle
        }
    }
    
    private func buildWebSocketUrl() -> URL? {
        guard var components = URLComponents(string: settingsService.endpointUrl) else {
            return nil
        }
        
        // Convert scheme to ws/wss
        if components.scheme == "https" {
            components.scheme = "wss"
        } else if components.scheme == "http" {
            components.scheme = "ws"
        }
        
        // Append /ws if needed
        var path = components.path
        if path.isEmpty || path == "/" {
            path = "/ws"
        } else if !path.hasSuffix("/ws") {
            path = "\(path)/ws"
        }
        components.path = path
        
        return components.url
    }
    
    private func sendHandshake() {
        let handshake: [String: Any] = [
            "type": "handshake",
            "client_id": clientId,
            "last_sync_id": lastSyncId
        ]
        
        guard let data = try? JSONSerialization.data(withJSONObject: handshake),
              let message = String(data: data, encoding: .utf8) else {
            return
        }
        
        print("[WS] Sending handshake: \(message)")
        webSocketTask?.send(.string(message)) { error in
            if let error = error {
                print("[WS] Failed to send handshake: \(error)")
            } else {
                print("[WS] Handshake sent successfully")
            }
        }
    }
    
    private func receiveMessage() {
        webSocketTask?.receive { [weak self] result in
            guard let self = self else { return }
            
            switch result {
            case .success(let message):
                switch message {
                case .string(let text):
                    self.handleMessage(text)
                case .data(let data):
                    if let text = String(data: data, encoding: .utf8) {
                        self.handleMessage(text)
                    }
                @unknown default:
                    break
                }
                // Continue receiving
                self.receiveMessage()
                
            case .failure(let error):
                print("[WS] Receive error: \(error)")
                Task { @MainActor in
                    self.connectionState = .disconnected
                }
                self.scheduleReconnect()
            }
        }
    }
    
    private func handleMessage(_ text: String) {
        print("[WS] Received: \(text.prefix(200))...")
        
        guard let data = text.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = json["type"] as? String else {
            return
        }
        
        // Process messages synchronously to maintain order
        // Use DispatchQueue.main.sync to block until processing completes
        let semaphore = DispatchSemaphore(value: 0)
        
        Task {
            defer { semaphore.signal() }
            
            switch type {
            case "sync_batch":
                await self.handleSyncBatch(json)
            case "sync_complete":
                await self.handleSyncComplete(json)
            case "realtime_update":
                await self.handleRealtimeUpdate(json)
            case "ping":
                self.sendPong()
            case "pong":
                print("[WS] Received pong")
            case "error":
                let errorMsg = json["message"] as? String ?? "Unknown error"
                print("[WS] Server error: \(errorMsg)")
            case "new_note_ack":
                let id = json["id"] as? Int64 ?? -1
                print("[WS] Server acknowledged new note with id=\(id)")
            default:
                print("[WS] Unknown message type: \(type)")
            }
        }
        
        // Wait for processing to complete before returning
        semaphore.wait()
    }
    
    private func handleSyncBatch(_ json: [String: Any]) async {
        let batchId = json["batch_id"] as? Int ?? 0
        let totalBatches = json["total_batches"] as? Int ?? 1
        
        print("[WS] handleSyncBatch: batch \(batchId) of \(totalBatches)")
        
        await MainActor.run {
            syncStatus = .syncing
            UIApplication.shared.isIdleTimerDisabled = true
        }
        
        if expectedBatches == 0 {
            expectedBatches = totalBatches
            print("[WS] Starting new sync, expecting \(totalBatches) batches")
        }
        
        guard let notesArray = json["notes"] as? [[String: Any]] else {
            print("[WS] No notes array in sync_batch")
            return
        }
        
        print("[WS] Processing \(notesArray.count) notes in batch")
        
        var batchNotes: [Note] = []
        var maxNoteId: Int64 = -1
        
        for noteJson in notesArray {
            if let note = parseNote(noteJson) {
                batchNotes.append(note)
                if note.id > maxNoteId {
                    maxNoteId = note.id
                }
            }
        }
        
        // 立即写入 DB（增量持久化）
        if !batchNotes.isEmpty {
            do {
                try await databaseService.insertNotes(batchNotes)
                print("[WS] Batch \(batchId): inserted \(batchNotes.count) notes to DB")
            } catch {
                print("[WS] Failed to insert batch \(batchId): \(error)")
            }
        }
        
        // 更新 last_sync_id 为该 batch 中最大的 note ID（断点续传）
        if maxNoteId > 0 {
            do {
                try await databaseService.updateSyncState(lastSyncId: maxNoteId)
                lastSyncId = maxNoteId
                print("[WS] Batch \(batchId): updated lastSyncId to \(maxNoteId)")
            } catch {
                print("[WS] Failed to update lastSyncId after batch \(batchId): \(error)")
            }
        }
        
        receivedBatches += 1
        print("[WS] Batch \(batchId)/\(totalBatches) complete: success=\(batchNotes.count)")
    }
    
    private func handleSyncComplete(_ json: [String: Any]) async {
        let totalSynced = json["total_synced"] as? Int ?? 0
        let newLastSyncId = json["last_sync_id"] as? Int64 ?? -1
        
        print("[WS] handleSyncComplete: totalSynced=\(totalSynced), newLastSyncId=\(newLastSyncId)")
        
        do {
            // 以服务器返回的 last_sync_id 为准做最终更新
            if totalSynced > 0 && newLastSyncId > 0 {
                try await databaseService.updateSyncState(lastSyncId: newLastSyncId)
                lastSyncId = newLastSyncId
                print("[WS] Updated lastSyncId to: \(newLastSyncId)")
            }
        } catch {
            print("[WS] Failed to update lastSyncId on sync complete: \(error)")
        }
        
        expectedBatches = 0
        receivedBatches = 0
        
        await MainActor.run {
            syncStatus = .completed
            UIApplication.shared.isIdleTimerDisabled = false
        }
        print("[WS] Sync complete: \(totalSynced) notes processed")
    }
    
    private func handleRealtimeUpdate(_ json: [String: Any]) async {
        guard let noteJson = json["note"] as? [String: Any],
              let note = parseNote(noteJson) else {
            return
        }
        
        do {
            try await databaseService.insertNote(note)
            
            if note.id > lastSyncId {
                lastSyncId = note.id
                try await databaseService.updateSyncState(lastSyncId: note.id)
                print("[WS] Updated lastSyncId to \(note.id) after realtime update")
            }
            
            print("[WS] Realtime update: note \(note.id)")
        } catch {
            print("[WS] Failed to save realtime note: \(error)")
        }
    }
    
    private func parseNote(_ json: [String: Any]) -> Note? {
        guard let id = json["id"] as? Int64,
              let encryptedContent = json["content"] as? String else {
            return nil
        }
        
        let createdAt = json["created_at"] as? String ?? json["createdAt"] as? String ?? ""
        let channel = json["channel"] as? String ?? "default"
        
        let content: String
        if let password = cachedPassword {
            do {
                content = try cryptoService.decryptWithPassword(encryptedContent, password: password)
                print("[WS] Decrypted note \(id) successfully")
            } catch {
                print("[WS] Failed to decrypt note \(id): \(error), storing encrypted content")
                content = encryptedContent
            }
        } else {
            print("[WS] No encryption password, using raw content for note \(id)")
            content = encryptedContent
        }
        
        return Note(id: id, content: content, channel: channel, createdAt: createdAt)
    }
    
    private func sendPong() {
        let pong = "{\"type\":\"pong\"}"
        webSocketTask?.send(.string(pong)) { error in
            if let error = error {
                print("[WS] Failed to send pong: \(error)")
            }
        }
    }
    
    private func scheduleReconnect() {
        reconnectTask?.cancel()
        reconnectTask = Task {
            try? await Task.sleep(nanoseconds: 5_000_000_000)  // 5 seconds
            guard !Task.isCancelled else { return }
            
            await MainActor.run {
                if self.connectionState == .disconnected {
                    print("[WS] Attempting reconnect...")
                    self.connect()
                }
            }
        }
    }
}

// MARK: - URLSessionDelegate
extension WebSocketService: URLSessionDelegate {
    func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        // Trust all certificates for development
        if challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
           let serverTrust = challenge.protectionSpace.serverTrust {
            completionHandler(.useCredential, URLCredential(trust: serverTrust))
        } else {
            completionHandler(.performDefaultHandling, nil)
        }
    }
}
