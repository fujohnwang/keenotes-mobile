import XCTest
import GRDB
import Combine
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
            return try await pending.deliver(prepared)
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

/// Hold HTTP responses without blocking URLSession so a retry can overlap the first send.
private final class NoteDeliveryURLProtocol: URLProtocol {
    @MainActor static var handler: ((NoteDeliveryURLProtocol) -> Void)?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Task { @MainActor in Self.handler?(self) }
    }
    override func stopLoading() {}
    func succeed(status: Int = 200) {
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data("{\"id\":123}".utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    func fail(_ code: URLError.Code) {
        client?.urlProtocol(self, didFailWithError: URLError(code))
    }
}

@MainActor
final class PendingNoteDeliveryTests: XCTestCase {
    @MainActor private struct Fixture {
        let settings: SettingsService
        let db: DatabaseService
        let api: ApiService
        let socket: WebSocketService
        let pending: PendingNoteService

        func note(_ content: String = "synthetic note") -> PreparedNote {
            PreparedNote(content: content, encryptedContent: "fixture-ciphertext", channel: "mobile-ios",
                         createdAt: "2026-09-23 00:00:00", requestId: UUID().uuidString,
                         configurationGeneration: settings.access.generation)
        }
    }

    private func fixture(path: String = ":memory:") async throws -> Fixture {
        let suite = "cn.keevol.keenotes.delivery-tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        let settings = SettingsService(defaults: defaults, storage: FaultInjectingKeychain())
        let db = DatabaseService(); try db.initialize(path: path)
        let coordinator = ConnectionConfigurationCoordinator(settings: settings, database: db, disconnect: {}, connect: {})
        try await coordinator.recover()
        try await coordinator.apply(ConnectionConfiguration(endpoint: "https://fixture.example.invalid", token: "fixture", pin: "PIN"), source: .general)
        let crypto = CryptoService(passwordProvider: { settings.encryptionPassword })
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [NoteDeliveryURLProtocol.self]
        let session = URLSession(configuration: config)
        let api = ApiService(settingsService: settings, cryptoService: crypto, session: session)
        let socket = WebSocketService(settingsService: settings, cryptoService: crypto, databaseService: db)
        let pending = PendingNoteService(databaseService: db, apiService: api, webSocketService: socket, access: settings.access)
        addTeardownBlock {
            await MainActor.run {
                pending.stopRetryScheduler()
                socket.disconnect()
                NoteDeliveryURLProtocol.handler = nil
                session.invalidateAndCancel()
                UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite)
            }
        }
        return Fixture(settings: settings, db: db, api: api, socket: socket, pending: pending)
    }

    func testSuccessfulSendIsDurableWhileHTTPIsPendingButNeverShowsOutbox() async throws {
        let f = try await fixture()
        let entered = expectation(description: "HTTP started")
        var held: NoteDeliveryURLProtocol?
        NoteDeliveryURLProtocol.handler = { held = $0; entered.fulfill() }
        var counts: [Int] = []
        let observation = f.pending.$queuedNotes.sink { counts.append($0.count) }
        defer { observation.cancel() }
        let lease = try f.settings.access.begin(); defer { f.settings.access.end(lease) }
        let note = f.note()
        let send = Task { try await f.pending.deliver(note) }
        await fulfillment(of: [entered], timeout: 3)
        let durable = try await f.db.getPendingNotes()
        XCTAssertEqual(durable.map(\.requestId), [note.requestId], "Persist before making the HTTP request")
        held?.succeed()
        let result = try await send.value
        XCTAssertEqual(result, .sent)
        XCTAssertTrue(counts.allSatisfy { $0 == 0 }, "Normal sending must not appear in outbox: \(counts)")
    }

    func testHTTPIsAttemptedEvenWhenWebSocketIsDisconnected() async throws {
        let f = try await fixture()
        var requests = 0
        NoteDeliveryURLProtocol.handler = { requests += 1; $0.succeed() }
        let lease = try f.settings.access.begin(); defer { f.settings.access.end(lease) }
        let result = try await f.pending.deliver(f.note())
        XCTAssertEqual(result, .sent)
        XCTAssertEqual(requests, 1)
    }

    func testRetryDoesNotPostTheNoteWhoseFirstHTTPIsStillInFlight() async throws {
        let f = try await fixture()
        let entered = expectation(description: "first HTTP started")
        let duplicate = expectation(description: "duplicate HTTP"); duplicate.isInverted = true
        var requests = 0
        var held: NoteDeliveryURLProtocol?
        NoteDeliveryURLProtocol.handler = {
            requests += 1
            if requests == 1 { held = $0; entered.fulfill() }
            else { duplicate.fulfill(); $0.succeed() }
        }
        let lease = try f.settings.access.begin(); defer { f.settings.access.end(lease) }
        let note = f.note()
        let send = Task { try await f.pending.deliver(note) }
        await fulfillment(of: [entered], timeout: 3)
        f.pending.retryPendingNotes()
        await fulfillment(of: [duplicate], timeout: 0.3)
        held?.succeed()
        let result = try await send.value
        XCTAssertEqual(result, .sent)
        XCTAssertEqual(requests, 1)
    }

    func testOfflineNoteAppearsInOutboxAndRetryKeepsItsRequestID() async throws {
        let f = try await fixture()
        NoteDeliveryURLProtocol.handler = { $0.fail(.notConnectedToInternet) }
        let lease = try f.settings.access.begin(); defer { f.settings.access.end(lease) }
        let note = f.note()
        let result = try await f.pending.deliver(note)
        XCTAssertEqual(result, .queuedOffline)
        XCTAssertEqual(f.pending.queuedNotes.map(\.requestId), [note.requestId])
        XCTAssertEqual(f.pending.queuedNotes.first?.encryptedContent, note.encryptedContent)

        let retried = expectation(description: "retry uses durable request")
        let cleared = expectation(description: "outbox cleared after HTTP success")
        let observation = f.pending.$queuedNotes.dropFirst().filter { $0.isEmpty }.sink { _ in cleared.fulfill() }
        defer { observation.cancel() }
        NoteDeliveryURLProtocol.handler = { request in
            let stream = request.request.httpBodyStream!
            stream.open(); defer { stream.close() }
            var bytes = [UInt8](repeating: 0, count: 4096)
            let count = stream.read(&bytes, maxLength: bytes.count)
            let json = try? JSONSerialization.jsonObject(with: Data(bytes.prefix(max(0, count)))) as? [String: Any]
            XCTAssertEqual(json?["request_id"] as? String, note.requestId)
            XCTAssertEqual(json?["text"] as? String, note.encryptedContent)
            retried.fulfill()
            request.succeed()
        }
        f.pending.retryPendingNotes()
        await fulfillment(of: [retried, cleared], timeout: 3)
        let remaining = try await f.db.getPendingNoteCount()
        XCTAssertEqual(remaining, 0)
    }

    func testTimeoutAndServerFailureAreQueuedWithoutClaimingDeviceIsOffline() async throws {
        let f = try await fixture()
        let lease = try f.settings.access.begin(); defer { f.settings.access.end(lease) }
        NoteDeliveryURLProtocol.handler = { $0.fail(.timedOut) }
        let timedOut = try await f.pending.deliver(f.note("timeout"))
        XCTAssertEqual(timedOut, .queued)
        NoteDeliveryURLProtocol.handler = { $0.succeed(status: 503) }
        let rejected = try await f.pending.deliver(f.note("server failure"))
        XCTAssertEqual(rejected, .queued)
        XCTAssertEqual(f.pending.queuedNotes.count, 2)
    }

    func testNormalSendDoesNotHideOrIncreaseExistingOutbox() async throws {
        let f = try await fixture()
        let old = f.note("previous failure")
        try await f.db.insertPendingNote(old)
        var snapshots: [[String?]] = []
        let observation = f.pending.$queuedNotes.sink { snapshots.append($0.map(\.requestId)) }
        defer { observation.cancel() }
        NoteDeliveryURLProtocol.handler = { $0.succeed() }
        let lease = try f.settings.access.begin(); defer { f.settings.access.end(lease) }
        let result = try await f.pending.deliver(f.note("new send"))
        XCTAssertEqual(result, .sent)
        XCTAssertTrue(snapshots.allSatisfy { $0 == [old.requestId] })
    }

    func testInterruptedSendIsRecoveredAsQueuedAfterDatabaseReopen() async throws {
        let path = NSTemporaryDirectory() + UUID().uuidString + ".sqlite"
        defer {
            for suffix in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + suffix) }
        }
        let f = try await fixture(path: path)
        defer { try? f.db.dbQueue?.close() }
        let entered = expectation(description: "HTTP in flight")
        var held: NoteDeliveryURLProtocol?
        NoteDeliveryURLProtocol.handler = { held = $0; entered.fulfill() }
        let lease = try f.settings.access.begin(); defer { f.settings.access.end(lease) }
        let note = f.note()
        let send = Task { try await f.pending.deliver(note) }
        await fulfillment(of: [entered], timeout: 3)
        XCTAssertTrue(f.pending.queuedNotes.isEmpty)

        // A fresh process has no active sends. Its database snapshot must expose the backup.
        let reopened = DatabaseService(); try reopened.initialize(path: path)
        defer { try? reopened.dbQueue?.close() }
        let recovered = PendingNoteService(databaseService: reopened, apiService: f.api, webSocketService: f.socket, access: f.settings.access)
        await reopened.refreshPendingNotes()
        XCTAssertEqual(recovered.queuedNotes.map(\.requestId), [note.requestId])
        XCTAssertEqual(recovered.queuedNotes.first?.encryptedContent, note.encryptedContent)
        held?.succeed()
        _ = try await send.value
    }

    func testOverlappingFirstAttemptsOnlyExposeTheFailedNote() async throws {
        let f = try await fixture()
        let entered = expectation(description: "both HTTP requests started"); entered.expectedFulfillmentCount = 2
        var held: [NoteDeliveryURLProtocol] = []
        NoteDeliveryURLProtocol.handler = { held.append($0); entered.fulfill() }
        let lease = try f.settings.access.begin(); defer { f.settings.access.end(lease) }
        let first = Task { try await f.pending.deliver(f.note("first")) }
        let second = Task { try await f.pending.deliver(f.note("second")) }
        await fulfillment(of: [entered], timeout: 3)
        XCTAssertTrue(f.pending.queuedNotes.isEmpty)
        let durable = try await f.db.getPendingNoteCount()
        XCTAssertEqual(durable, 2)
        held.first?.fail(.notConnectedToInternet)
        held.last?.succeed()
        let results = try await [first.value, second.value]
        XCTAssertEqual(results.filter { $0 == .sent }.count, 1)
        XCTAssertEqual(results.filter { $0 == .queuedOffline }.count, 1)
        XCTAssertEqual(f.pending.queuedNotes.count, 1)
        let remaining = try await f.db.getPendingNotes()
        XCTAssertEqual(f.pending.queuedNotes.map(\.requestId), remaining.map(\.requestId))
    }

    func testFailedLocalBackupDoesNotAttemptHTTPOrLoseTheError() async throws {
        let f = try await fixture()
        try await f.db.dbQueue!.write { try $0.execute(sql: "CREATE TRIGGER refuse_pending_insert BEFORE INSERT ON pending_notes BEGIN SELECT RAISE(ABORT, 'injected'); END") }
        NoteDeliveryURLProtocol.handler = { _ in XCTFail("HTTP must wait for durable backup") }
        let lease = try f.settings.access.begin(); defer { f.settings.access.end(lease) }
        do { _ = try await f.pending.deliver(f.note()); XCTFail("Caller must restore the draft") }
        catch { XCTAssertTrue(f.pending.queuedNotes.isEmpty) }
    }
}
