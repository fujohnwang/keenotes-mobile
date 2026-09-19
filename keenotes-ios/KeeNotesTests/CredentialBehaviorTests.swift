import XCTest
import GRDB
@testable import KeeNotes

/// Fail only at the Keychain boundary; successful operations use Security.framework.
final class FaultInjectingKeychain: SecureStringStorage {
    let keychain = KeychainService(service: "cn.keevol.keenotes.tests.\(UUID().uuidString)")
    var writes = 0
    var failWrite: Int?
    var failReads = false
    func read(account: String) throws -> String? {
        if failReads { throw KeychainService.StorageError.status(-25308) }
        return try keychain.read(account: account)
    }
    func write(_ value: String, account: String) throws {
        writes += 1
        if failWrite == writes { throw KeychainService.StorageError.status(-25308) }
        try keychain.write(value, account: account)
    }
    deinit {
        for key in [CredentialsStore.account, PurchasedCredentialsStore.account, "token", "encryption_password"] { keychain.delete(account: key) }
    }
}

@MainActor
final class CredentialBehaviorTests: XCTestCase {
    private var storage: FaultInjectingKeychain!
    private var defaults: UserDefaults!
    private var suite: String!
    private var path: String!
    private let a = ConnectionConfiguration(endpoint: "https://general.example.invalid", token: "general-token-ABCD", pin: "PIN-A")
    private let b = ConnectionConfiguration(endpoint: "https://iap.example.invalid", token: "iap-token-ABCD", pin: "PIN-B")

    override func setUp() {
        super.setUp()
        storage = FaultInjectingKeychain()
        suite = "cn.keevol.keenotes.tests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)!
        path = NSTemporaryDirectory() + UUID().uuidString + ".sqlite"
    }
    override func tearDown() {
        defaults.removePersistentDomain(forName: suite)
        for suffix in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + suffix) }
        storage = nil
        super.tearDown()
    }
    private func legacy(_ configuration: ConnectionConfiguration) throws {
        defaults.set(configuration.endpoint, forKey: "endpoint_url")
        try storage.write(configuration.token, account: "token")
        try storage.write(configuration.pin, account: "encryption_password")
    }
    private func services() throws -> (SettingsService, DatabaseService, ConnectionConfigurationCoordinator) {
        let settings = SettingsService(defaults: defaults, storage: storage)
        let db = DatabaseService(); try db.initialize(path: path)
        let coordinator = ConnectionConfigurationCoordinator(settings: settings, database: db, disconnect: {}, connect: {})
        return (settings, db, coordinator)
    }

    func testMigrationCompleteTupleDedupAndDeletedCurrentStaysDeletedAfterRestart() async throws {
        try legacy(a)
        let (settings, db, coordinator) = try services()
        try await coordinator.recover()
        XCTAssertEqual(settings.history.map(\.configuration), [a])
        try await coordinator.apply(a, source: .general)
        XCTAssertEqual(settings.history.count, 1)
        var anotherPin = a; anotherPin.pin = "PIN-other"
        try await coordinator.apply(anotherPin, source: .general)
        XCTAssertEqual(settings.history.count, 2)
        try settings.deleteHistory(id: settings.history.first!.id)
        XCTAssertEqual(settings.configuration, anotherPin)
        let restarted = SettingsService(defaults: defaults, storage: storage)
        XCTAssertEqual(restarted.configuration, anotherPin)
        XCTAssertEqual(restarted.history.map(\.configuration), [a])
        let value1 = try await db.getNoteCount()
        XCTAssertEqual(value1, 0)
    }

    func testReadOrMigrationWriteFailureNeverOverwritesLegacyOrHistory() throws {
        try legacy(a)
        storage.failWrite = storage.writes + 1
        let failed = SettingsService(defaults: defaults, storage: storage)
        XCTAssertNotNil(failed.configurationError)
        XCTAssertNil(try storage.read(account: CredentialsStore.account))
        XCTAssertEqual(try storage.read(account: "token"), a.token)
        storage.failWrite = nil
        try failed.reloadCredentials()
        let original = try storage.read(account: CredentialsStore.account)
        storage.failReads = true
        XCTAssertThrowsError(try failed.reloadCredentials())
        XCTAssertEqual(failed.configuration, a)
        storage.failReads = false
        XCTAssertEqual(try storage.read(account: CredentialsStore.account), original)
        // A malformed item must also remain intact rather than be treated as missing.
        try storage.write("not-json", account: CredentialsStore.account)
        XCTAssertThrowsError(try failed.reloadCredentials())
        XCTAssertEqual(try storage.read(account: CredentialsStore.account), "not-json")
    }

    func testPendingLegacyAndEncryptedBlockEveryTupleChangeButNotSameTuple() async throws {
        try legacy(a)
        let (settings, db, coordinator) = try services()
        try await coordinator.recover()
        try await db.insertPendingNote(content: "legacy note")
        try await db.insertPendingNote(PreparedNote(content: "encrypted note", encryptedContent: "cipher", channel: "mobile-ios", createdAt: "2026-01-01 00:00:00", requestId: "request"))
        for target in [b, ConnectionConfiguration(endpoint: a.endpoint, token: a.token, pin: "changed")] {
            do { try await coordinator.apply(target, source: .iap); XCTFail("must block") }
            catch { XCTAssertEqual(error as? ConfigurationError, .pendingNotes) }
            XCTAssertEqual(settings.configuration, a)
            XCTAssertTrue(settings.access.isReady)
        }
        try await coordinator.apply(a, source: .general)
        let value2 = try await db.getPendingNoteCount()
        XCTAssertEqual(value2, 2)
        XCTAssertEqual(settings.history.count, 1)
    }

    func testSwitchDrainsInFlightSaveThenChecksPending() async throws {
        try legacy(a)
        let (settings, db, coordinator) = try services()
        try await coordinator.recover()
        let lease = try settings.access.begin()
        let change = Task { try await coordinator.apply(b, source: .iap) }
        while !coordinator.isApplying { await Task.yield() }
        XCTAssertFalse(settings.access.isReady)
        XCTAssertThrowsError(try settings.access.begin())
        try await db.insertPendingNote(content: "old account pending")
        settings.access.end(lease)
        do { try await change.value; XCTFail("must block after draining") } catch {}
        XCTAssertEqual(settings.configuration, a)
        let value3 = try await db.getPendingNoteCount()
        XCTAssertEqual(value3, 1)
    }

    func testSQLiteFailureLeavesRecoverableJournalAndNoActiveNewCredentials() async throws {
        try legacy(a)
        let (settings, db, coordinator) = try services()
        try await coordinator.recover()
        try await db.insertNote(Note(id: 1, content: "account A", channel: "test", createdAt: "2026-01-01"))
        try await db.updateSyncState(lastSyncId: 123)
        try await db.dbQueue!.write { db in
            try db.execute(sql: "CREATE TRIGGER refuse_delete BEFORE DELETE ON notes BEGIN SELECT RAISE(ABORT, 'injected'); END")
        }
        do { try await coordinator.apply(b, source: .iap); XCTFail("must fail") } catch {}
        XCTAssertFalse(settings.access.isReady)
        XCTAssertEqual(settings.configuration, a)
        let value4 = try await db.getLastSyncId()
        XCTAssertEqual(value4, 123)
        XCTAssertNotNil(settings.credentialsStore.envelope?.transition)
        try await db.dbQueue!.write { try $0.execute(sql: "DROP TRIGGER refuse_delete") }
        let restart = SettingsService(defaults: defaults, storage: storage)
        let recovery = ConnectionConfigurationCoordinator(settings: restart, database: db, disconnect: {}, connect: {})
        try await recovery.recover()
        XCTAssertEqual(restart.configuration, b)
        XCTAssertTrue(restart.access.isReady)
        let value5 = try await db.getNoteCount()
        XCTAssertEqual(value5, 0)
        let value6 = try await db.getLastSyncId()
        XCTAssertEqual(value6, -1)
        XCTAssertEqual(Set(restart.history.map { $0.configuration.token }), Set([a.token, b.token]))
    }

    func testExitAfterSQLiteCommitBeforeFinalKeychainWriteReplaysWithoutClearingTwice() async throws {
        try legacy(a)
        let (settings, db, coordinator) = try services()
        try await coordinator.recover()
        storage.failWrite = storage.writes + 2 // Journal succeeds; final current/history write fails.
        do { try await coordinator.apply(b, source: .iap); XCTFail("must fail") } catch {}
        XCTAssertFalse(settings.access.isReady)
        XCTAssertEqual(settings.configuration, a)
        XCTAssertNotNil(settings.credentialsStore.envelope?.transition)
        // Detect a second cache reset during journal replay.
        try await db.insertNote(Note(id: 999, content: "after committed reset", channel: "test", createdAt: "2026-01-01"))
        storage.failWrite = nil
        let restart = SettingsService(defaults: defaults, storage: storage)
        let recovery = ConnectionConfigurationCoordinator(settings: restart, database: db, disconnect: {}, connect: {})
        try await recovery.recover()
        XCTAssertEqual(restart.configuration, b)
        let value7 = try await db.getNoteCount()
        XCTAssertEqual(value7, 1)
        XCTAssertNil(restart.credentialsStore.envelope?.transition)
    }

    func testFailedFirstJournalWriteRetainsOldConfigurationAndCache() async throws {
        try legacy(a)
        let (settings, db, coordinator) = try services()
        try await coordinator.recover()
        try await db.updateSyncState(lastSyncId: 42)
        storage.failWrite = storage.writes + 1
        do { try await coordinator.apply(b, source: .iap); XCTFail("must fail") } catch {}
        XCTAssertEqual(settings.configuration, a)
        let value8 = try await db.getLastSyncId()
        XCTAssertEqual(value8, 42)
        XCTAssertTrue(settings.access.isReady)
        XCTAssertNil(settings.credentialsStore.envelope?.transition)
    }

    func testDraftFillPreservesPINAndRejectsLateResultEvenIfUserEditsBack() {
        let draft = SettingsDraft(); draft.replace(a)
        let revision = draft.revision
        draft.token = "edited"; draft.token = a.token
        XCTAssertFalse(draft.fill(endpoint: b.endpoint, token: b.token, ifRevision: revision))
        XCTAssertEqual(draft.configuration, a)
        XCTAssertTrue(draft.fill(endpoint: b.endpoint, token: b.token, ifRevision: draft.revision))
        XCTAssertEqual(draft.pin, a.pin)
        XCTAssertEqual(draft.confirmPin, a.pin)
    }
    func testDeletingHistoryWhileSwitchDrainsDoesNotResurrectDeletedEntry() async throws {
        try legacy(a)
        let (settings, _, coordinator) = try services()
        try await coordinator.recover()
        try await coordinator.apply(b, source: .iap)
        try await coordinator.apply(a, source: .general)
        let bID = settings.history.first { $0.configuration == b }!.id
        let lease = try settings.access.begin()
        var c = a; c.pin = "third-PIN"
        let change = Task { try await coordinator.apply(c, source: .general) }
        while !coordinator.isApplying { await Task.yield() }
        try settings.deleteHistory(id: bID)
        settings.access.end(lease)
        try await change.value
        XCTAssertFalse(settings.history.contains { $0.id == bID })
        let restart = SettingsService(defaults: defaults, storage: storage)
        XCTAssertFalse(restart.history.contains { $0.id == bID })
    }

    func testAccountSwitchClearsOnThisDayAndRejectsLateQueryResult() async throws {
        try legacy(a)
        let (settings, db, _) = try services()
        let state = AppState(settings: settings, database: db)
        try await state.configurationCoordinator.recover()
        let oldNote = Note(id: 1, content: "account A private note", channel: "test", createdAt: "2020-09-14")
        try await db.insertNote(oldNote)
        state.onThisDayNotes = [oldNote]
        let revision = state.configurationRevision
        var continuation: CheckedContinuation<Void, Never>?
        let load = Task {
            await state.loadOnThisDayNotes {
                let old = try await db.getAllNotes()
                await withCheckedContinuation { continuation = $0 }
                return old
            }
        }
        while continuation == nil { await Task.yield() }
        try await state.configurationCoordinator.apply(b, source: .iap)
        XCTAssertTrue(state.onThisDayNotes.isEmpty)
        XCTAssertNotEqual(state.configurationRevision, revision)
        continuation?.resume()
        await load.value
        XCTAssertTrue(state.onThisDayNotes.isEmpty, "A late account A query cannot publish after B activation")
        state.webSocketService.disconnect()
    }

}
