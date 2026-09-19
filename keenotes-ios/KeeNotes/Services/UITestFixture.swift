#if DEBUG
import Foundation
import UIKit

/// Explicit launch-only fixture. Uses a fresh Keychain namespace, UserDefaults suite,
/// temporary SQLite file and in-process delivery dependency; never real app data/services.
@MainActor
struct UITestFixture {
    let settings: SettingsService
    let database: DatabaseService
    let purchase: StoreKitPurchaseService
    static func make(mode: String) throws -> Self {
        let delayedDelivery = mode == "delayed-delivery"
        let namespace = "cn.keevol.keenotes.ui-fixture.\(UUID().uuidString)"
        let storage = KeychainService(service: namespace)
        let defaults = UserDefaults(suiteName: namespace)!
        let settings = SettingsService(defaults: defaults, storage: storage)
        let a = ConnectionConfiguration(endpoint: "https://general.example.invalid", token: "fixture-general-ABCD", pin: "Fixture-PIN-A")
        let b = ConnectionConfiguration(endpoint: "https://subscription.example.invalid", token: "fixture-iap-EFGH", pin: "Fixture-PIN-B")
        if mode != "blank" {
            var envelope = CredentialsEnvelope(current: a)
            envelope.record(a, source: .general); envelope.record(b, source: .iap)
            try settings.credentialsStore.persist(envelope); settings.publishCredentials()
        }
        let cache = PurchasedCredentialsStore(storage: storage)
        if mode != "blank" && !delayedDelivery {
            try cache.save(PurchasedRecord(transactionID: "fixture", productID: "local.test.annual", environment: "Xcode", credentials: UIProvisioner.material))
        }
        let config = PurchaseConfiguration(productID: "local.test.annual", productionURL: "", sandboxURL: "")
        let manager = StoreKitPurchaseService(configuration: config, store: UIAppleStore(),
                                             provisioner: UIProvisioner(waitForForeground: delayedDelivery), credentialsStore: cache)
        return Self(settings: settings, database: DatabaseService(path: NSTemporaryDirectory() + namespace + ".sqlite"), purchase: manager)
    }
}

@MainActor
private final class UIAppleStore: AppleStoreProviding {
    func products(id: String) async throws -> [AnnualProduct] { [AnnualProduct(id: id, name: NSLocalizedString("Local annual subscription (fixture)", comment: "IAP and connection configuration"), displayPrice: "$1.00")] }
    func purchase(id: String) async throws -> StoreOutcome {
        .verified(StoreTransaction(id: "ui-fixture", productID: id, environment: "Xcode", signedJWS: "ui-fixture-only", finish: {}))
    }
    func sync() async throws {}
    func currentEntitlements() async -> [StoreOutcome] { [] }
    func unfinished() async -> [StoreOutcome] { [] }
    func updates() -> AsyncStream<StoreOutcome> { AsyncStream { _ in } }
}
private struct UIProvisioner: AppleProvisioning {
    var waitForForeground = false
    static var material: PurchasedCredentials {
        PurchasedCredentials(endpoint: "https://subscription.example.invalid", token: "fixture-iap-EFGH", entitlementStatus: "active",
                             expiresAt: Int64(Date().timeIntervalSince1970) + 86400, provisioningSeconds: 0)
    }
    func provision(_ transaction: StoreTransaction, intent: ProvisionIntent) async throws -> PurchasedCredentials {
        if waitForForeground {
            // The UI test dismisses the sheet and edits its draft before sending the
            // app through Home/activate. No timer or test-only IPC controls delivery.
            for await _ in NotificationCenter.default.notifications(named: UIApplication.willEnterForegroundNotification) {
                break
            }
            try Task.checkCancellation()
        }
        return Self.material
    }
}
#endif
