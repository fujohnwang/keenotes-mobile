import Foundation

/// Every send owns a lease until HTTP completion AND any fallback write complete.
/// A switch stops admissions and drains existing leases before examining pending data.
@MainActor
final class ConfigurationAccess {
    private(set) var isReady = false
    private(set) var generation = UUID()
    private var leases: Set<UUID> = []
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func begin() throws -> UUID {
        guard isReady else { throw ConfigurationError.recoveryRequired }
        let lease = UUID()
        leases.insert(lease)
        return lease
    }
    func end(_ lease: UUID) {
        leases.remove(lease)
        if leases.isEmpty {
            let pending = waiters
            waiters.removeAll()
            pending.forEach { $0.resume() }
        }
    }
    func pauseAndDrain() async {
        isReady = false
        if !leases.isEmpty { await withCheckedContinuation { waiters.append($0) } }
    }
    func resume(changed: Bool) {
        if changed { generation = UUID() }
        isReady = true
    }
}

@MainActor
final class ConnectionConfigurationCoordinator: ObservableObject {
    @Published private(set) var isApplying = false
    @Published private(set) var errorMessage: String?
    private let settings: SettingsService
    private let database: DatabaseService
    private let disconnect: () -> Void
    private let connect: () -> Void
    private let didChange: () -> Void

    init(settings: SettingsService, database: DatabaseService,
         disconnect: @escaping () -> Void, connect: @escaping () -> Void, didChange: @escaping () -> Void = {}) {
        self.settings = settings
        self.database = database
        self.disconnect = disconnect
        self.connect = connect
        self.didChange = didChange
    }

    func recover() async throws {
        guard !isApplying else { throw ConfigurationError.busy }
        isApplying = true
        defer { isApplying = false }
        disconnect()
        await settings.access.pauseAndDrain()
        do {
            try settings.reloadCredentials()
            if let transition = settings.credentialsStore.envelope?.transition {
                try await complete(transition)
            }
            settings.access.resume(changed: true)
            didChange()
            errorMessage = nil
        } catch {
            errorMessage = String(format: NSLocalizedString("Local configuration recovery failed: %@", comment: "IAP and connection configuration"), error.localizedDescription)
            throw error
        }
    }

    func apply(_ target: ConnectionConfiguration, source: CredentialSource) async throws {
        try target.validate()
        guard !isApplying else { throw ConfigurationError.busy }
        guard settings.access.isReady,
              var value = settings.credentialsStore.envelope,
              value.transition == nil else { throw ConfigurationError.recoveryRequired }
        isApplying = true
        defer { isApplying = false }
        let changed = value.current != target
        disconnect()
        await settings.access.pauseAndDrain()
        do {
            if changed {
                let count = try await database.getPendingNoteCount()
                if count > 0 { throw ConfigurationError.pendingNotes }
            }
            // Deletions are allowed while old sends drain; do not resurrect them from
            // the snapshot taken before those awaits. A journal then blocks deletions.
            guard let latest = settings.credentialsStore.envelope, latest.transition == nil else { throw ConfigurationError.recoveryRequired }
            value = latest
            if changed {
                let transition = ConfigurationTransition(id: UUID(), target: target, source: source)
                value.transition = transition
                try settings.credentialsStore.persist(value)
                try await complete(transition)
            } else {
                value.record(target, source: source)
                try settings.credentialsStore.persist(value)
                settings.publishCredentials()
            }
            settings.access.resume(changed: changed)
            if changed { didChange() }
            errorMessage = nil
            connect()
        } catch {
            // Once a journal exists, stay paused until recovery completes. Never reuse old
            // memory against a database that might already belong to the target account.
            if settings.credentialsStore.envelope?.transition == nil {
                settings.access.resume(changed: false)
                connect()
            }
            errorMessage = String(format: NSLocalizedString("Local save did not complete: %@", comment: "IAP and connection configuration"), error.localizedDescription)
            throw error
        }
    }

    private func complete(_ transition: ConfigurationTransition) async throws {
        guard var value = settings.credentialsStore.envelope else { throw ConfigurationError.recoveryRequired }
        let old = value.current
        let replaceNotes = old?.endpoint != transition.target.endpoint || old?.token != transition.target.token
        do {
            try await database.applyConfigurationTransition(id: transition.id, replaceNotes: replaceNotes)
        } catch {
            throw NSError(domain: "Configuration", code: 1, userInfo: [NSLocalizedDescriptionKey: NSLocalizedString("Unable to switch the cache. Your credential record is preserved. Retry recovery.", comment: "IAP and connection configuration")])
        }
        value.current = transition.target
        value.record(transition.target, source: transition.source)
        value.transition = nil
        try settings.credentialsStore.persist(value)
        if replaceNotes { settings.firstNoteDate = nil }
        settings.publishCredentials()
    }
}
