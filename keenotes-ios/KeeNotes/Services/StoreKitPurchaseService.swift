import Foundation
import StoreKit
import CryptoKit

struct AnnualProduct: Identifiable, Equatable {
    let id: String
    let name: String
    let displayPrice: String
}

struct StoreTransaction {
    let id: String
    let productID: String
    let environment: String
    let signedJWS: String
    let finish: () async -> Void
    // Revocations can update an existing transaction ID. Include the signed revision.
    var key: String { environment + ":" + id + ":" + SHA256.hash(data: Data(signedJWS.utf8)).map { String(format: "%02x", $0) }.joined() }
}

enum StoreOutcome { case verified(StoreTransaction), pending, cancelled, unverified }

@MainActor
protocol AppleStoreProviding: AnyObject {
    func products(id: String) async throws -> [AnnualProduct]
    func purchase(id: String) async throws -> StoreOutcome
    func sync() async throws
    func currentEntitlements() async -> [StoreOutcome]
    func unfinished() async -> [StoreOutcome]
    func updates() -> AsyncStream<StoreOutcome>
}

@MainActor
final class StoreKitPurchaseService: ObservableObject {
    @Published private(set) var product: AnnualProduct?
    @Published private(set) var isBusy = false
    @Published private(set) var message = ""
    @Published private(set) var purchased: PurchasedCredentials?
    let configuration: PurchaseConfiguration
    let credentialsStore: PurchasedCredentialsStore
    private let store: AppleStoreProviding
    private let provisioner: AppleProvisioning
    private var isRunning = false
    private var listener: Task<Void, Never>?
    private var refreshTask: Task<Void, Never>?
    private var retryTasks: [String: Task<Void, Never>] = [:]
    private var attempts: [String: Int] = [:]
    private var inFlight: [String: (id: UUID, task: Task<PurchasedCredentials?, Error>)] = [:]
    private var pending: [String: StoreTransaction] = [:]
    private var finished: Set<String> = []
    private var stoppedAutomatic: Set<String> = []
    private let sleep: (UInt64) async throws -> Void

    convenience init(configuration: PurchaseConfiguration = .load()) {
        self.init(configuration: configuration, store: SystemAppleStore(),
                  provisioner: AppleProvisioningClient(configuration: configuration), credentialsStore: PurchasedCredentialsStore())
    }
    init(configuration: PurchaseConfiguration, store: AppleStoreProviding, provisioner: AppleProvisioning,
         credentialsStore: PurchasedCredentialsStore, sleep: @escaping (UInt64) async throws -> Void = { try await Task.sleep(nanoseconds: $0) }) {
        self.configuration = configuration; self.store = store; self.provisioner = provisioner
        self.credentialsStore = credentialsStore; self.sleep = sleep
        do { try credentialsStore.load(); purchased = credentialsStore.records.last?.credentials }
        catch { message = error.localizedDescription }
    }
    deinit { listener?.cancel(); refreshTask?.cancel(); retryTasks.values.forEach { $0.cancel() } }

    func start() {
        guard listener == nil else { return }
        isRunning = true
        let updates = store.updates()
        listener = Task { [weak self] in
            for await outcome in updates {
                guard !Task.isCancelled, let self else { return }
                _ = await self.handle(outcome, intent: .automatic)
            }
        }
        refresh()
    }
    func stop() {
        isRunning = false
        listener?.cancel(); listener = nil
        refreshTask?.cancel(); refreshTask = nil
        retryTasks.values.forEach { $0.cancel() }; retryTasks.removeAll()
    }
    @discardableResult
    func refresh() -> Task<Void, Never>? {
        guard refreshTask == nil else { return refreshTask }
        refreshTask = Task { [weak self] in
            guard let self else { return }
            defer { refreshTask = nil }
            do { try credentialsStore.load(); purchased = credentialsStore.records.last?.credentials }
            catch { if !isBusy { message = error.localizedDescription } }
            let unfinished = await store.unfinished()
            let current = await store.currentEntitlements()
            var seen: Set<String> = []
            for outcome in unfinished + current {
                guard !Task.isCancelled else { return }
                if case .verified(let transaction) = outcome, !seen.insert(transaction.key).inserted { continue }
                _ = await handle(outcome, intent: .automatic)
            }
        }
        return refreshTask
    }
    func loadProducts() async {
        guard !isBusy else { return }
        isBusy = true; defer { isBusy = false }
        do {
            guard !configuration.productID.isEmpty else { throw PurchaseFailure.configuration(NSLocalizedString("Annual subscriptions are not open for purchase yet. Existing subscribers can restore purchases or retry delivery.", comment: "IAP and connection configuration")) }
            product = try await store.products(id: configuration.productID).first
            guard product != nil else { throw PurchaseFailure.unavailable }
        } catch { message = error.localizedDescription }
    }
    func purchase() async -> PurchasedCredentials? {
        guard !isBusy, let product else { return nil }
        isBusy = true; message = NSLocalizedString("Purchasing through the App Store…", comment: "IAP and connection configuration"); defer { isBusy = false }
        do { return await handle(try await store.purchase(id: product.id), intent: .userInitiated) }
        catch { message = error.localizedDescription; return nil }
    }
    func restore() async -> PurchasedCredentials? {
        guard !isBusy else { return nil }
        isBusy = true; message = NSLocalizedString("Restoring purchases…", comment: "IAP and connection configuration"); defer { isBusy = false }
        do {
            // This is the only AppStore.sync call path; foreground refresh never prompts.
            try await store.sync()
            let current = await store.currentEntitlements()
            let unfinished = await store.unfinished()
            var restored: PurchasedCredentials?
            var seen: Set<String> = []
            for outcome in current + unfinished {
                if case .verified(let transaction) = outcome {
                    guard transaction.productID == configuration.productID, seen.insert(transaction.key).inserted else { continue }
                }
                if let material = await handle(outcome, intent: .userInitiated) { restored = material }
            }
            if current.isEmpty && unfinished.isEmpty { message = NSLocalizedString("There are no valid purchases to restore. Your existing configuration and history are preserved.", comment: "IAP and connection configuration") }
            return restored
        } catch { message = error.localizedDescription; return nil }
    }
    func retryDelivery() async -> PurchasedCredentials? {
        guard !isBusy else { return nil }
        isBusy = true; defer { isBusy = false }
        var material: PurchasedCredentials?
        if pending.isEmpty {
            let current = await store.currentEntitlements()
            let unfinished = await store.unfinished()
            for outcome in current + unfinished {
                if let value = await handle(outcome, intent: .userInitiated) { material = value }
            }
        } else {
            for transaction in Array(pending.values) {
                if let value = await handle(.verified(transaction), intent: .userInitiated) { material = value }
            }
        }
        return material
    }

    @discardableResult
    func handle(_ outcome: StoreOutcome, intent: ProvisionIntent) async -> PurchasedCredentials? {
        switch outcome {
        case .pending: publish(NSLocalizedString("Purchase is awaiting approval. Delivery will continue after approval.", comment: "IAP and connection configuration"), intent: intent); return nil
        case .cancelled: publish(NSLocalizedString("Purchase cancelled.", comment: "IAP and connection configuration"), intent: intent); return nil
        case .unverified: publish(PurchaseFailure.unverified.localizedDescription, intent: intent); return nil
        case .verified(let transaction):
            guard !configuration.productID.isEmpty, transaction.productID == configuration.productID else { return nil }
            let key = transaction.key
            if intent == .automatic && stoppedAutomatic.contains(key) { return nil }
            if intent == .userInitiated { stoppedAutomatic.remove(key); retryTasks.removeValue(forKey: key)?.cancel() }
            pending[key] = transaction
            do {
                let material: PurchasedCredentials?
                if let existing = inFlight[key] {
                    do { material = try await existing.task.value }
                    catch let error as PurchaseFailure {
                        // Explicit recovery may re-enable a privacy-disabled credential after
                        // an already-running automatic request has been rejected.
                        if intent == .userInitiated, case .server("ACCESS_DISABLED", 403, _, _) = error {
                            inFlight.removeValue(forKey: key)
                            material = try await deliver(transaction, intent: intent)
                        } else { throw error }
                    }
                } else { material = try await deliver(transaction, intent: intent) }
                pending.removeValue(forKey: key)
                attempts.removeValue(forKey: key)
                retryTasks.removeValue(forKey: key)?.cancel()
                publish(material == nil ? NSLocalizedString("No active subscription; your existing configuration and history are preserved.", comment: "IAP and connection configuration") : NSLocalizedString("Credentials delivered. Save the configuration after filling them. Service activation may take a moment.", comment: "IAP and connection configuration"), intent: intent)
                return material
            } catch {
                publish(error.localizedDescription, intent: intent)
                let retryable = (error as? PurchaseFailure)?.retryable ?? true
                if retryable { scheduleRetry(transaction, error: error) }
                else { stoppedAutomatic.insert(key) }
                return nil
            }
        }
    }

    private func deliver(_ transaction: StoreTransaction, intent: ProvisionIntent) async throws -> PurchasedCredentials? {
        let key = transaction.key
        let task = Task<PurchasedCredentials?, Error> { [self] in
            do {
                let material = try await provisioner.provision(transaction, intent: intent)
                try material.validate()
                try credentialsStore.save(PurchasedRecord(transactionID: transaction.id, productID: transaction.productID,
                                                         environment: transaction.environment, credentials: material))
                purchased = material
                if finished.insert(key).inserted { await transaction.finish() }
                return material
            } catch let error as PurchaseFailure {
                if case .server("ENTITLEMENT_INACTIVE", 403, false, _) = error {
                    if finished.insert(key).inserted { await transaction.finish() }
                    return nil
                }
                throw error
            }
        }
        let operation = UUID()
        inFlight[key] = (operation, task)
        defer { if inFlight[key]?.id == operation { inFlight.removeValue(forKey: key) } }
        return try await task.value
    }
    private func publish(_ text: String, intent: ProvisionIntent) {
        if intent == .userInitiated || !isBusy { message = text }
    }
    private func scheduleRetry(_ transaction: StoreTransaction, error: Error) {
        let key = transaction.key
        guard isRunning, retryTasks[key] == nil else { return }
        let attempt = (attempts[key] ?? 0) + 1
        attempts[key] = attempt
        var seconds = min(300, Int(pow(2, Double(min(attempt, 8)))))
        if let failure = error as? PurchaseFailure, case .server(_, _, _, let delay) = failure, let delay { seconds = max(seconds, min(3600, delay)) }
        let sleep = self.sleep
        retryTasks[key] = Task { [weak self] in
            do { try await sleep(UInt64(seconds) * 1_000_000_000) } catch { return }
            guard !Task.isCancelled, let self else { return }
            self.retryTasks.removeValue(forKey: key)
            // User intent is deliberately NOT retained by automatic retry.
            _ = await self.handle(.verified(transaction), intent: .automatic)
        }
    }
}

@MainActor
final class SystemAppleStore: AppleStoreProviding {
    private var loaded: [String: Product] = [:]
    func products(id: String) async throws -> [AnnualProduct] {
        let products = try await Product.products(for: [id]).filter {
            $0.type == .autoRenewable && $0.subscription?.subscriptionPeriod.unit == .year && $0.subscription?.subscriptionPeriod.value == 1
        }
        loaded = Dictionary(uniqueKeysWithValues: products.map { ($0.id, $0) })
        return products.map { AnnualProduct(id: $0.id, name: $0.displayName, displayPrice: $0.displayPrice) }
    }
    func purchase(id: String) async throws -> StoreOutcome {
        guard let product = loaded[id] else { throw PurchaseFailure.unavailable }
        switch try await product.purchase() {
        case .success(let verification): return Self.outcome(verification)
        case .pending: return .pending
        case .userCancelled: return .cancelled
        @unknown default: return .pending
        }
    }
    func sync() async throws { try await AppStore.sync() }
    func currentEntitlements() async -> [StoreOutcome] {
        var result: [StoreOutcome] = []
        for await verification in Transaction.currentEntitlements { result.append(Self.outcome(verification)) }
        return result
    }
    func unfinished() async -> [StoreOutcome] {
        var result: [StoreOutcome] = []
        for await verification in Transaction.unfinished { result.append(Self.outcome(verification)) }
        return result
    }
    func updates() -> AsyncStream<StoreOutcome> {
        AsyncStream { continuation in
            let task = Task {
                for await verification in Transaction.updates {
                    guard !Task.isCancelled else { break }
                    continuation.yield(Self.outcome(verification))
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
    static func outcome(_ verification: VerificationResult<Transaction>) -> StoreOutcome {
        guard case .verified(let transaction) = verification else { return .unverified }
        let environment: String
        if #available(iOS 16.0, *) { environment = transaction.environment.rawValue }
        else { environment = transaction.environmentStringRepresentation }
        return .verified(StoreTransaction(id: String(transaction.id), productID: transaction.productID,
                                          environment: environment, signedJWS: verification.jwsRepresentation,
                                          finish: { await transaction.finish() }))
    }
}
