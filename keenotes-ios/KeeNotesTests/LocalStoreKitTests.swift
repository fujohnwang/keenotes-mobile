import XCTest
import StoreKitTest
import StoreKit
@testable import KeeNotes

@MainActor
final class LocalStoreKitTests: XCTestCase {
    func testSystemStoreAnnualProductVerifiedUnfinishedAndDeliveryFinish() async throws {
        guard #available(iOS 15.4, *) else { throw XCTSkip("SKTestSession URL configuration requires iOS 15.4") }
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "LocalAnnual", withExtension: "storekit"))
        let diagnostics = ProcessInfo.processInfo.environment["KEENOTES_STOREKIT_DIAGNOSTICS"] == "1"
        let productID = diagnostics
            ? ProcessInfo.processInfo.environment["KEENOTES_STOREKIT_DIAGNOSTIC_PRODUCT_ID"] ?? "local.test.annual"
            : "local.test.annual"
        guard productID.hasPrefix("local.test.") else { return XCTFail("Diagnostics require a local.test. product") }
        let requireFreshTransaction = diagnostics && ProcessInfo.processInfo.environment["KEENOTES_STOREKIT_REQUIRE_FRESH_TRANSACTION"] == "1"
        let session: SKTestSession?
        if diagnostics {
            // The scheme supplies the catalog; even SKTestSession.init writes controller state.
            session = nil
            diagnostic("begin", ["mode": "observation_preserving_transactions", "testProductID": productID,
                                 "hostProductID": PurchaseConfiguration.load().productID,
                                 "uiFixtureArgumentPresent": ProcessInfo.processInfo.arguments.contains("--ui-test-fixture")])
        } else {
            session = try SKTestSession(contentsOf: url)
            session?.resetToDefaultState(); session?.clearTransactions(); session?.disableDialogs = true
        }
        defer { session?.clearTransactions(); session?.resetToDefaultState() }
        let store = SystemAppleStore()
        let products = try await store.products(id: productID)
        XCTAssertEqual(products.count, 1)
        let product = try XCTUnwrap(products.first)
        XCTAssertFalse(product.displayPrice.isEmpty)
        let purchaseStartedAt = Date().timeIntervalSince1970 * 1_000
        let outcome = try await store.purchase(id: productID)
        guard case .verified(let transaction) = outcome else { return XCTFail("Must receive locally verified StoreKit transaction") }
        XCTAssertEqual(transaction.environment, "Xcode")
        XCTAssertFalse(transaction.signedJWS.isEmpty)
        if diagnostics {
            let fields = transactionFields(transaction, includeJWSFields: true)
            diagnostic("purchase", fields.merging(["purchaseStartedAtMs": purchaseStartedAt]) { _, new in new })
            if requireFreshTransaction {
                // The control is meaningful only for a first purchase in the new local group.
                XCTAssertEqual(transaction.productID, productID)
                let jws = try XCTUnwrap(fields["jwsFields"] as? [String: Any])
                XCTAssertEqual(jws["productId"] as? String, productID)
                XCTAssertEqual(jws["transactionId"] as? String, transaction.id)
                XCTAssertEqual(jws["originalTransactionId"] as? String, transaction.id)
                let purchaseDate = try XCTUnwrap(jws["purchaseDate"] as? NSNumber).doubleValue
                XCTAssertGreaterThanOrEqual(purchaseDate, purchaseStartedAt - 1_000) // Allow clock/JSON rounding.
            }
        }
        let unfinished = await store.unfinished()
        if diagnostics { diagnostic("unfinished_before_assert", ["outcomes": unfinished.map(outcomeFields)]) }
        XCTAssertTrue(unfinished.contains { if case .verified(let tx) = $0 { return tx.id == transaction.id }; return false })
        if diagnostics {
            // Read only after the original assertion so the extra query cannot alter its result.
            var currentFields: [[String: Any]] = []
            for await verification in Transaction.currentEntitlements {
                switch verification {
                case .verified(let tx), .unverified(let tx, _):
                    guard tx.productID == productID else { continue }
                    var fields = outcomeFields(SystemAppleStore.outcome(verification))
                    fields["rawID"] = String(tx.id)
                    fields["rawOriginalID"] = String(tx.originalID)
                    fields["purchaseDateMs"] = tx.purchaseDate.timeIntervalSince1970 * 1_000
                    fields["expirationDateMs"] = tx.expirationDate.map { $0.timeIntervalSince1970 * 1_000 }
                    currentFields.append(fields)
                }
            }
            diagnostic("raw_current_after_assert", ["transactions": currentFields])
        }
        let credentials = FaultInjectingKeychain()
        let config = PurchaseConfiguration(productID: productID, productionURL: "", sandboxURL: "")
        let provisioner = ControlledProvisioner() // Explicit local dependency; never send Xcode JWS to Sandbox.
        let manager = StoreKitPurchaseService(configuration: config, store: store, provisioner: provisioner,
                                              credentialsStore: PurchasedCredentialsStore(storage: credentials))
        var finishCount = 0
        var managerOutcome = outcome
        if diagnostics {
            managerOutcome = .verified(StoreTransaction(id: transaction.id, productID: transaction.productID,
                environment: transaction.environment, signedJWS: transaction.signedJWS, finish: {
                    finishCount += 1
                    self.diagnostic("test_manager_finish_begin", ["id": transaction.id, "count": finishCount])
                    await transaction.finish()
                    self.diagnostic("test_manager_finish_end", ["id": transaction.id, "count": finishCount])
                }))
        }
        _ = await manager.handle(managerOutcome, intent: .userInitiated)
        let remaining = await store.unfinished()
        if diagnostics { diagnostic("unfinished_after_handle", ["outcomes": remaining.map(outcomeFields), "testManagerFinishCount": finishCount]) }
        XCTAssertFalse(remaining.contains { if case .verified(let tx) = $0 { return tx.id == transaction.id }; return false })
        let current = await store.currentEntitlements()
        XCTAssertFalse(current.isEmpty)
        XCTAssertNotNil(try credentials.read(account: PurchasedCredentialsStore.account))
        manager.stop()
        if diagnostics { diagnostic("end", ["testManagerFinishCount": finishCount, "isolatedSession": false]) }
    }

    private func outcomeFields(_ outcome: StoreOutcome) -> [String: Any] {
        switch outcome {
        case .verified(let transaction):
            return transactionFields(transaction).merging(["verification": "verified"]) { _, new in new }
        case .pending: return ["verification": "pending"]
        case .cancelled: return ["verification": "cancelled"]
        case .unverified: return ["verification": "unverified"]
        }
    }

    private func transactionFields(_ transaction: StoreTransaction, includeJWSFields: Bool = false) -> [String: Any] {
        var fields: [String: Any] = ["id": transaction.id, "productID": transaction.productID, "environment": transaction.environment]
        guard includeJWSFields, transaction.productID.hasPrefix("local.test."), transaction.environment == "Xcode" else { return fields }
        let parts = transaction.signedJWS.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 3 else { fields["jwsPayloadDecoded"] = false; return fields }
        var encoded = parts[1].replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        encoded += String(repeating: "=", count: (4 - encoded.count % 4) % 4)
        guard let data = Data(base64Encoded: encoded),
              let payload = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            fields["jwsPayloadDecoded"] = false; return fields
        }
        // Diagnostics only: StoreKit already verified the transaction. Never log JWS/signature/credentials.
        let keys = ["transactionId", "originalTransactionId", "productId", "environment", "purchaseDate", "expiresDate"]
        var safeFields: [String: Any] = [:]
        for key in keys where payload[key] is String || payload[key] is NSNumber { safeFields[key] = payload[key] }
        fields["jwsPayloadDecoded"] = true
        fields["jwsFields"] = safeFields
        fields["missingJWSFields"] = keys.filter { payload[$0] == nil }
        return fields
    }

    private func diagnostic(_ event: String, _ fields: [String: Any]) {
        var row = fields
        row["event"] = event; row["timestamp"] = Date().timeIntervalSince1970
        guard let data = try? JSONSerialization.data(withJSONObject: row, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8) else { return }
        print("[SKDIAG-unfinished] \(text)")
    }
}
