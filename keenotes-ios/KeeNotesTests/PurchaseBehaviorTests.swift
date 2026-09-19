import XCTest
@testable import KeeNotes

@MainActor
final class FixtureStore: AppleStoreProviding {
    var product = AnnualProduct(id: "local.test.annual", name: "Local annual fixture", displayPrice: "$1.00")
    var result: StoreOutcome = .cancelled
    var current: [StoreOutcome] = []
    var unfinishedTransactions: [StoreOutcome] = []
    var syncCount = 0
    var listenerCount = 0
    var currentCount = 0
    var unfinishedCount = 0
    var continuation: AsyncStream<StoreOutcome>.Continuation?
    func products(id: String) async throws -> [AnnualProduct] { [product] }
    func purchase(id: String) async throws -> StoreOutcome { result }
    func sync() async throws { syncCount += 1 }
    func currentEntitlements() async -> [StoreOutcome] { currentCount += 1; return current }
    func unfinished() async -> [StoreOutcome] { unfinishedCount += 1; return unfinishedTransactions }
    func updates() -> AsyncStream<StoreOutcome> {
        listenerCount += 1
        return AsyncStream { continuation = $0 }
    }
}

@MainActor
final class ControlledProvisioner: AppleProvisioning {
    var calls: [ProvisionIntent] = []
    var result: Result<PurchasedCredentials, Error> = .success(PurchaseBehaviorTests.material())
    var blocked = false
    var continuation: CheckedContinuation<Void, Never>?
    func provision(_ transaction: StoreTransaction, intent: ProvisionIntent) async throws -> PurchasedCredentials {
        calls.append(intent)
        if blocked { await withCheckedContinuation { continuation = $0 } }
        return try result.get()
    }
    func release() { blocked = false; continuation?.resume(); continuation = nil }
}

@MainActor
final class PurchaseBehaviorTests: XCTestCase {
    static func material() -> PurchasedCredentials {
        PurchasedCredentials(endpoint: "https://sandbox.example.invalid", token: "fixed-token-ABCD", entitlementStatus: "active",
                             expiresAt: Int64(Date().timeIntervalSince1970) + 86_400, provisioningSeconds: 2)
    }
    private func configuration() -> PurchaseConfiguration {
        PurchaseConfiguration(productID: "local.test.annual", productionURL: "https://production.example.invalid/iap/apple/provision",
                              sandboxURL: "https://sandbox.example.invalid/iap/apple/provision")
    }
    private func transaction(id: String = "42", finish: @escaping () async -> Void) -> StoreTransaction {
        StoreTransaction(id: id, productID: "local.test.annual", environment: "Sandbox", signedJWS: "fixture-signed-\(id)", finish: finish)
    }
    private func service(_ store: FixtureStore, _ provisioner: ControlledProvisioner, _ storage: FaultInjectingKeychain,
                         sleep: @escaping (UInt64) async throws -> Void = { try await Task.sleep(nanoseconds: $0) }) -> StoreKitPurchaseService {
        StoreKitPurchaseService(configuration: configuration(), store: store, provisioner: provisioner,
                                credentialsStore: PurchasedCredentialsStore(storage: storage), sleep: sleep)
    }
    private func eventually(_ condition: @escaping () -> Bool, file: StaticString = #filePath, line: UInt = #line) async {
        for _ in 0..<1000 { if condition() { return }; await Task.yield() }
        XCTFail("condition not reached", file: file, line: line)
    }

    func testKeychainFailureDoesNotFinishAndExplicitRetryPersistsBeforeFinish() async throws {
        let storage = FaultInjectingKeychain(); let store = FixtureStore(); let provisioner = ControlledProvisioner()
        let manager = service(store, provisioner, storage)
        var finishes = 0
        let tx = transaction {
            XCTAssertNotNil(try? storage.read(account: PurchasedCredentialsStore.account))
            finishes += 1
        }
        storage.failWrite = 1
        let failed = await manager.handle(.verified(tx), intent: .userInitiated)
        XCTAssertNil(failed); XCTAssertEqual(finishes, 0)
        XCTAssertNil(try storage.read(account: PurchasedCredentialsStore.account))
        storage.failWrite = nil
        let restored = await manager.retryDelivery()
        XCTAssertEqual(restored?.token, Self.material().token); XCTAssertEqual(finishes, 1)
        let reloaded = PurchasedCredentialsStore(storage: storage); try reloaded.load()
        XCTAssertEqual(reloaded.records.first?.credentials.token, "fixed-token-ABCD")
        XCTAssertNil(try storage.read(account: CredentialsStore.account), "IAP delivery must not activate a configuration or create history")
        manager.stop()
    }

    func testConcurrentUpdatesAndPurchaseCoalesceOneProvisionAndOneFinish() async {
        let storage = FaultInjectingKeychain(); let store = FixtureStore(); let provisioner = ControlledProvisioner()
        provisioner.blocked = true
        let manager = service(store, provisioner, storage)
        var finishes = 0
        let tx = transaction { finishes += 1 }
        let first = Task { await manager.handle(.verified(tx), intent: .automatic) }
        await eventually { provisioner.calls.count == 1 }
        let second = Task { await manager.handle(.verified(tx), intent: .userInitiated) }
        for _ in 0..<20 { await Task.yield() }
        XCTAssertEqual(provisioner.calls.count, 1); XCTAssertEqual(finishes, 0)
        provisioner.release()
        _ = await first.value; _ = await second.value
        XCTAssertEqual(provisioner.calls.count, 1); XCTAssertEqual(finishes, 1)
        manager.stop()
    }

    func testStartupUsesUnfinishedAndCurrentAndOnlyExplicitRestoreSyncs() async {
        let storage = FaultInjectingKeychain(); let store = FixtureStore(); let provisioner = ControlledProvisioner()
        var finishes = 0
        let tx = transaction { finishes += 1 }
        store.current = [.verified(tx)]; store.unfinishedTransactions = [.verified(tx)]
        let manager = service(store, provisioner, storage)
        let draft = SettingsDraft(); draft.replace(ConnectionConfiguration(endpoint: "https://general.example.invalid", token: "manual", pin: "PIN"))
        let initial = draft.configuration
        manager.start(); manager.start()
        await manager.refresh()?.value
        await eventually { finishes == 1 }
        XCTAssertEqual(store.listenerCount, 1); XCTAssertEqual(store.syncCount, 0)
        XCTAssertGreaterThan(store.currentCount, 0); XCTAssertGreaterThan(store.unfinishedCount, 0)
        XCTAssertEqual(provisioner.calls, [.automatic])
        XCTAssertEqual(draft.configuration, initial)
        _ = await manager.restore()
        XCTAssertEqual(store.syncCount, 1)
        XCTAssertEqual(provisioner.calls.last, .userInitiated)
        XCTAssertEqual(manager.purchased?.token, "fixed-token-ABCD")
        manager.stop()
    }

    func testAutomaticRetryDoesNotRetainUserIntentAndStopPreventsNewRetries() async {
        let storage = FaultInjectingKeychain(); let store = FixtureStore(); let provisioner = ControlledProvisioner()
        var sleepContinuation: CheckedContinuation<Void, Error>?
        var sleeps = 0
        let manager = service(store, provisioner, storage) { _ in
            sleeps += 1
            try await withCheckedThrowingContinuation { sleepContinuation = $0 }
        }
        manager.start()
        provisioner.result = .failure(PurchaseFailure.server(code: "PROVISIONING_PENDING", status: 503, retryable: true, delay: 1))
        let tx = transaction { }
        _ = await manager.handle(.verified(tx), intent: .userInitiated)
        await eventually { sleeps == 1 }
        provisioner.result = .success(Self.material())
        sleepContinuation?.resume(); sleepContinuation = nil
        await eventually { provisioner.calls.count == 2 }
        XCTAssertEqual(provisioner.calls, [.userInitiated, .automatic])
        manager.stop()
        provisioner.blocked = true
        let inFlight = Task { await manager.handle(.verified(self.transaction(id: "43") {}), intent: .userInitiated) }
        await eventually { provisioner.calls.count == 3 }
        provisioner.result = .failure(PurchaseFailure.server(code: "STOREKIT_INTERNAL_ERROR", status: 500, retryable: true, delay: nil))
        provisioner.release(); _ = await inFlight.value
        for _ in 0..<20 { await Task.yield() }
        XCTAssertEqual(sleeps, 1, "No retry may be scheduled after stop, including by in-flight delivery")
    }

    func testAccessDisabledIsNotExpiryAndOnlyExplicitRestoreCanRetry() async {
        let storage = FaultInjectingKeychain(); let store = FixtureStore(); let provisioner = ControlledProvisioner()
        let manager = service(store, provisioner, storage)
        var finishes = 0
        let tx = transaction { finishes += 1 }
        provisioner.result = .failure(PurchaseFailure.server(code: "ACCESS_DISABLED", status: 403, retryable: false, delay: nil))
        _ = await manager.handle(.verified(tx), intent: .automatic)
        _ = await manager.handle(.verified(tx), intent: .automatic)
        XCTAssertEqual(finishes, 0); XCTAssertEqual(provisioner.calls.count, 1)
        provisioner.result = .success(Self.material())
        let material = await manager.retryDelivery()
        XCTAssertNotNil(material); XCTAssertEqual(finishes, 1)
        XCTAssertEqual(provisioner.calls, [.automatic, .userInitiated])
        manager.stop()
    }

    func testUnverifiedCancelledPendingAndTerminalEntitlementDoNotGrantCredentials() async throws {
        let storage = FaultInjectingKeychain(); let store = FixtureStore(); let provisioner = ControlledProvisioner()
        let manager = service(store, provisioner, storage)
        for outcome: StoreOutcome in [.unverified, .cancelled, .pending] { _ = await manager.handle(outcome, intent: .userInitiated) }
        XCTAssertTrue(provisioner.calls.isEmpty)
        var finishes = 0
        provisioner.result = .failure(PurchaseFailure.server(code: "ENTITLEMENT_INACTIVE", status: 403, retryable: false, delay: nil))
        _ = await manager.handle(.verified(transaction { finishes += 1 }), intent: .automatic)
        XCTAssertEqual(finishes, 1); XCTAssertNil(manager.purchased)
        XCTAssertNil(try storage.read(account: CredentialsStore.account))
        manager.stop()
    }

    func testPurchasedProvenanceMatchesFullEndpointAndTokenAndKeepsDifferentPIN() throws {
        let storage = FaultInjectingKeychain(); let cache = PurchasedCredentialsStore(storage: storage)
        let material = Self.material()
        try cache.save(PurchasedRecord(transactionID: "42", productID: "local.test.annual", environment: "Sandbox", credentials: material))
        for pin in ["PIN-A", "PIN-B"] {
            XCTAssertEqual(cache.source(for: ConnectionConfiguration(endpoint: material.endpoint, token: material.token, pin: pin)), .iap)
        }
        XCTAssertEqual(cache.source(for: ConnectionConfiguration(endpoint: material.endpoint, token: "other-ABCD", pin: "PIN")), .general)
    }
}
