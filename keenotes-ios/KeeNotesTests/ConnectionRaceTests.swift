import XCTest
import GRDB
@testable import KeeNotes

@MainActor
final class ConnectionRaceTests: XCTestCase {
    private func eventually(_ condition: () async throws -> Bool) async throws {
        for _ in 0..<600 {
            if try await condition() { return }
            try await Task.sleep(nanoseconds: 25_000_000)
        }
        XCTFail("Local fixture did not reach expected state")
    }

    func testRealWebSocketWriteInFlightIsDrainedAndLateSocketCannotAffectNewSpace() async throws {
        let storage = FaultInjectingKeychain()
        let defaults = UserDefaults(suiteName: UUID().uuidString)!
        let settings = SettingsService(defaults: defaults, storage: storage)
        let database = DatabaseService(); try database.initialize(path: ":memory:")
        let crypto = CryptoService(passwordProvider: { settings.encryptionPassword })
        let socket = WebSocketService(settingsService: settings, cryptoService: crypto, databaseService: database)
        let coordinator = ConnectionConfigurationCoordinator(settings: settings, database: database,
                                                              disconnect: { socket.disconnect() }, connect: { socket.connect() })
        try await coordinator.recover()
        let a = ConnectionConfiguration(endpoint: "http://127.0.0.1:18444/a", token: "a", pin: "PIN-A")
        let b = ConnectionConfiguration(endpoint: "http://127.0.0.1:18444/b", token: "b", pin: "PIN-B")
        try await coordinator.apply(a, source: .general)
        try await eventually { socket.syncStatus == .completed }
        let initialNotes = try await database.getAllNotes()
        XCTAssertEqual(initialNotes.map(\.id), [1])
        let releaseDB = DispatchSemaphore(value: 0)
        let dbEntered = expectation(description: "SQLite write queue occupied")
        let queue = database.dbQueue!
        let blocker = Task.detached {
            try await queue.write { _ in dbEntered.fulfill(); releaseDB.wait() }
        }
        await fulfillment(of: [dbEntered], timeout: 5)
        defer { releaseDB.signal(); socket.disconnect() }
        _ = try await URLSession.shared.data(from: URL(string: "http://127.0.0.1:18444/release-a")!)
        try await eventually { socket.syncStatus == .syncing }
        let change = Task { try await coordinator.apply(b, source: .general) }
        try await eventually { coordinator.isApplying }
        XCTAssertFalse(settings.access.isReady)
        releaseDB.signal()
        try await blocker.value
        try await change.value
        try await eventually { socket.syncStatus == .completed }
        let notes = try await database.getAllNotes()
        XCTAssertEqual(notes.map(\.id), [2])
        XCTAssertEqual(notes.first?.content, "fixture account b")
        let cursor = try await database.getLastSyncId()
        XCTAssertEqual(cursor, 2)
        XCTAssertEqual(settings.configuration, b)
        XCTAssertEqual(socket.connectionState, .connected)
    }

    func testHTTPCompletionAndPendingCleanupFinishBeforeSwitchCanPublishNewCredentials() async throws {
        let storage = FaultInjectingKeychain()
        let settings = SettingsService(defaults: UserDefaults(suiteName: UUID().uuidString)!, storage: storage)
        let db = DatabaseService(); try db.initialize(path: ":memory:")
        let crypto = CryptoService(passwordProvider: { settings.encryptionPassword })
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [ProvisionURLProtocol.self]
        let api = ApiService(settingsService: settings, cryptoService: crypto, session: URLSession(configuration: config))
        let socket = WebSocketService(settingsService: settings, cryptoService: crypto, databaseService: db)
        let pending = PendingNoteService(databaseService: db, apiService: api, webSocketService: socket, access: settings.access)
        let coordinator = ConnectionConfigurationCoordinator(settings: settings, database: db, disconnect: {}, connect: {})
        try await coordinator.recover()
        let a = ConnectionConfiguration(endpoint: "https://a.example.invalid", token: "token-a", pin: "PIN")
        let b = ConnectionConfiguration(endpoint: "https://b.example.invalid", token: "token-b", pin: "PIN")
        try await coordinator.apply(a, source: .general)
        let entered = expectation(description: "old HTTP request in flight")
        let releaseHTTP = DispatchSemaphore(value: 0)
        var requestToken: String?
        ProvisionURLProtocol.handler = { request in
            requestToken = request.value(forHTTPHeaderField: "Authorization")
            entered.fulfill(); releaseHTTP.wait()
            return (200, [:], Data("{\"id\":123}".utf8))
        }
        defer { releaseHTTP.signal(); ProvisionURLProtocol.handler = nil }
        let lease = try settings.access.begin()
        let prepared = try api.prepareNote(content: "old space note")
        let send = Task {
            defer { settings.access.end(lease) }
            return try await pending.deliver(prepared, online: true)
        }
        await fulfillment(of: [entered], timeout: 5)
        let change = Task { try await coordinator.apply(b, source: .general) }
        try await eventually { coordinator.isApplying }
        XCTAssertEqual(settings.configuration, a)
        releaseHTTP.signal()
        let result = try await send.value
        XCTAssertEqual(result, .sent)
        try await change.value
        XCTAssertEqual(settings.configuration, b)
        XCTAssertEqual(requestToken, "Bearer token-a")
        let count = try await db.getPendingNoteCount()
        XCTAssertEqual(count, 0)
        let stale = await api.postPreparedNote(prepared)
        XCTAssertFalse(stale.success)
    }
    func testRealV2EncryptedWebSocketBatchKeepsMainActorResponsive() async throws {
        let encrypted = try await Task.detached {
            try CryptoService(passwordProvider: { "fixture-PIN" }).encrypt("real V2 encrypted fixture")
        }.value
        var request = URLRequest(url: URL(string: "http://127.0.0.1:18444/encrypted")!)
        request.httpMethod = "POST"
        request.httpBody = try JSONSerialization.data(withJSONObject: ["content": encrypted])
        _ = try await URLSession.shared.data(for: request)
        let settings = SettingsService(defaults: UserDefaults(suiteName: UUID().uuidString)!, storage: FaultInjectingKeychain())
        let db = DatabaseService(); try db.initialize(path: ":memory:")
        let socket = WebSocketService(settingsService: settings, cryptoService: CryptoService(passwordProvider: { nil }), databaseService: db)
        let coordinator = ConnectionConfigurationCoordinator(settings: settings, database: db, disconnect: { socket.disconnect() }, connect: { socket.connect() })
        try await coordinator.recover()
        var heartbeats = 0
        var maxGap: TimeInterval = 0
        let heartbeat = Task { @MainActor in
            var previous = Date()
            while !Task.isCancelled {
                do { try await Task.sleep(nanoseconds: 20_000_000) } catch { return }
                maxGap = max(maxGap, Date().timeIntervalSince(previous)); previous = Date()
                heartbeats += 1
            }
        }
        defer { heartbeat.cancel(); socket.disconnect() }
        try await coordinator.apply(ConnectionConfiguration(endpoint: "http://127.0.0.1:18444/encrypted", token: "fixture", pin: "fixture-PIN"), source: .general)
        try await eventually { socket.syncStatus == .completed }
        let notes = try await db.getAllNotes()
        XCTAssertEqual(notes.count, 12)
        XCTAssertTrue(notes.allSatisfy { $0.content == "real V2 encrypted fixture" })
        XCTAssertGreaterThan(heartbeats, 10, "Argon2 batch must yield MainActor to input/navigation")
        XCTAssertLessThan(maxGap, 0.6, "MainActor must not execute the serial 64 MB Argon2 batch")
    }

}
