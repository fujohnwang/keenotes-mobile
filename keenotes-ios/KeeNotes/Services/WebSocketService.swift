import Foundation
import Combine
import UIKit

@MainActor
final class WebSocketService: NSObject, ObservableObject {
    enum ConnectionState { case disconnected, connecting, connected }
    enum SyncStatus { case idle, syncing, completed }
    @Published private(set) var connectionState: ConnectionState = .disconnected
    @Published private(set) var syncStatus: SyncStatus = .idle

    private let settingsService: SettingsService
    private let decoder = WebSocketMessageDecoder()
    private let databaseService: DatabaseService
    private var webSocketTask: URLSessionWebSocketTask?
    private var session: URLSession!
    private let clientId = UUID().uuidString
    private var epoch = UUID()
    private var lastSyncId: Int64 = -1
    private var cachedPassword: String?
    private var connectionTask: Task<Void, Never>?
    private var receiveTask: Task<Void, Never>?
    private var reconnectTask: Task<Void, Never>?

    init(settingsService: SettingsService, cryptoService: CryptoService, databaseService: DatabaseService) {
        self.settingsService = settingsService
        self.databaseService = databaseService
        super.init()
        let config = URLSessionConfiguration.default
        config.waitsForConnectivity = true
        session = URLSession(configuration: config, delegate: WebSocketDelegate(owner: self), delegateQueue: nil)
    }

    deinit { connectionTask?.cancel(); receiveTask?.cancel(); reconnectTask?.cancel(); session?.invalidateAndCancel() }

    func connect() {
        guard settingsService.access.isReady, settingsService.isConfigured,
              connectionState == .disconnected, webSocketTask == nil else { return }
        reconnectTask?.cancel()
        connectionState = .connecting
        let operation = epoch
        let configuration = settingsService.configuration
        cachedPassword = configuration.pin
        connectionTask = Task { [weak self] in
            guard let self else { return }
            do {
                let cursor = try await databaseService.getLastSyncId()
                guard operation == epoch, settingsService.access.isReady, !Task.isCancelled else { return }
                lastSyncId = cursor
                guard var components = URLComponents(string: configuration.endpoint) else { throw ConfigurationError.invalidInput }
                components.scheme = components.scheme == "https" ? "wss" : "ws"
                if components.path.isEmpty || components.path == "/" { components.path = "/ws" }
                else if !components.path.hasSuffix("/ws") { components.path += "/ws" }
                guard let url = components.url else { throw ConfigurationError.invalidInput }
                var request = URLRequest(url: url)
                request.setValue("Bearer \(configuration.token)", forHTTPHeaderField: "Authorization")
                request.setValue("\(components.scheme == "wss" ? "https" : "http")://\(components.host ?? "")", forHTTPHeaderField: "Origin")
                let socket = session.webSocketTask(with: request)
                webSocketTask = socket
                socket.resume()
            } catch {
                guard operation == epoch else { return }
                disconnect()
                scheduleReconnect()
            }
        }
    }

    func disconnect() {
        epoch = UUID()
        connectionTask?.cancel(); connectionTask = nil
        receiveTask?.cancel(); receiveTask = nil
        reconnectTask?.cancel(); reconnectTask = nil
        let old = webSocketTask
        webSocketTask = nil
        old?.cancel(with: .goingAway, reason: nil)
        connectionState = .disconnected
        resetState()
    }

    func resetState() {
        lastSyncId = -1
        cachedPassword = nil
        syncStatus = .idle
        UIApplication.shared.isIdleTimerDisabled = false
    }

    func markConnectionSuspect(reason: String) { disconnect(); scheduleReconnect() }

    private func receive(from socket: URLSessionWebSocketTask) {
        let operation = epoch
        receiveTask = Task { [weak self] in
            do {
                while !Task.isCancelled {
                    let message = try await socket.receive()
                    guard let self, operation == self.epoch, socket === self.webSocketTask else { return }
                    let data: Data
                    switch message {
                    case .string(let text): data = Data(text.utf8)
                    case .data(let bytes): data = bytes
                    @unknown default: continue
                    }
                    try await self.handleMessage(data, operation: operation)
                }
            } catch {
                guard let self, operation == self.epoch, socket === self.webSocketTask else { return }
                self.disconnect()
                self.scheduleReconnect()
            }
        }
    }

    /// Await processing in receive order, without blocking a URLSession queue or the UI.
    /// The lease also covers the SQLite work after suspension points.
    private func handleMessage(_ data: Data, operation: UUID) async throws {
        guard operation == epoch, let lease = try? settingsService.access.begin() else { return }
        defer { settingsService.access.end(lease) }
        let password = cachedPassword
        let message = try await decoder.decode(data, password: password)
        guard operation == epoch, !Task.isCancelled else { return }
        switch message {
        case .batch(let notes):
            syncStatus = .syncing
            UIApplication.shared.isIdleTimerDisabled = true
            try await databaseService.insertNotes(notes)
            guard operation == epoch else { return }
            if let maxID = notes.map(\.id).max(), maxID > lastSyncId {
                try await databaseService.updateSyncState(lastSyncId: maxID)
                guard operation == epoch else { return }
                lastSyncId = maxID
            }
        case .complete(let cursor, let total):
            if cursor > 0, total > 0 {
                try await databaseService.updateSyncState(lastSyncId: cursor)
                guard operation == epoch else { return }
                lastSyncId = cursor
            }
            guard operation == epoch else { return }
            syncStatus = .completed
            UIApplication.shared.isIdleTimerDisabled = false
        case .realtime(let note):
            do {
                try await databaseService.insertNote(note)
                guard operation == epoch else { return }
                if note.id > lastSyncId {
                    try await databaseService.updateSyncState(lastSyncId: note.id)
                    guard operation == epoch else { return }
                    lastSyncId = note.id
                }
            }
        case .ping: webSocketTask?.send(.string("{\"type\":\"pong\"}")) { _ in }
        default: break
        }
    }

    private func scheduleReconnect() {
        guard settingsService.access.isReady else { return }
        reconnectTask?.cancel()
        let operation = epoch
        reconnectTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            guard !Task.isCancelled, let self, self.epoch == operation,
                  UIApplication.shared.applicationState == .active else { return }
            self.connect()
        }
    }
}

extension WebSocketService: URLSessionWebSocketDelegate {
    nonisolated func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) {
        Task { @MainActor [weak self] in
            guard let self, webSocketTask === self.webSocketTask, settingsService.access.isReady else { return }
            self.connectionState = .connected
            let body: [String: Any] = ["type": "handshake", "client_id": self.clientId, "last_sync_id": self.lastSyncId]
            if let data = try? JSONSerialization.data(withJSONObject: body) {
                webSocketTask.send(.string(String(decoding: data, as: UTF8.self))) { _ in }
            }
            self.receive(from: webSocketTask)
        }
    }
    nonisolated func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask,
                               didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        Task { @MainActor [weak self] in
            guard let self, webSocketTask === self.webSocketTask else { return }
            self.disconnect()
            self.scheduleReconnect()
        }
    }
    nonisolated func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                               completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        // Preserve existing self-hosted endpoint compatibility. IAP uses a separate,
        // standard TLS-verifying URLSession and never uses this delegate.
        if challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
           let trust = challenge.protectionSpace.serverTrust { completionHandler(.useCredential, URLCredential(trust: trust)) }
        else { completionHandler(.performDefaultHandling, nil) }
    }
}

// URLSession retains its delegate. Keep the delegate's link back to the owner weak.
private final class WebSocketDelegate: NSObject, URLSessionWebSocketDelegate {
    weak var owner: WebSocketService?
    init(owner: WebSocketService) { self.owner = owner }
    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) {
        owner?.urlSession(session, webSocketTask: webSocketTask, didOpenWithProtocol: `protocol`)
    }
    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        owner?.urlSession(session, webSocketTask: webSocketTask, didCloseWith: closeCode, reason: reason)
    }
    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        if let owner { owner.urlSession(session, didReceive: challenge, completionHandler: completionHandler) }
        else { completionHandler(.performDefaultHandling, nil) }
    }
}
