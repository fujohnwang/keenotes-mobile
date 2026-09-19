import Foundation

struct ConnectionConfiguration: Codable, Equatable {
    var endpoint: String
    var token: String
    var pin: String

    init(endpoint: String, token: String, pin: String) {
        self.endpoint = endpoint.trimmingCharacters(in: .whitespacesAndNewlines)
        self.token = token
        self.pin = pin
    }

    var isComplete: Bool { !endpoint.isEmpty && !token.isEmpty && !pin.isEmpty }
    var tokenSuffix: String { String(token.suffix(4)) }
    func validate() throws {
        guard isComplete, let url = URL(string: endpoint),
              ["https", "http"].contains(url.scheme?.lowercased() ?? ""),
              url.host != nil else { throw ConfigurationError.invalidInput }
    }
}

enum CredentialSource: String, Codable { case general, iap
    var title: String { self == .iap ? "IAP" : NSLocalizedString("General", comment: "IAP and connection configuration") }
}

struct CredentialHistoryEntry: Codable, Identifiable, Equatable {
    let id: UUID
    let configuration: ConnectionConfiguration
    var source: CredentialSource
    var lastUsed: Date
}

struct ConfigurationTransition: Codable {
    let id: UUID
    let target: ConnectionConfiguration
    let source: CredentialSource
}

struct CredentialsEnvelope: Codable {
    var version = 1
    var current: ConnectionConfiguration?
    var history: [CredentialHistoryEntry] = []
    var transition: ConfigurationTransition?

    mutating func record(_ configuration: ConnectionConfiguration, source: CredentialSource) {
        guard configuration.isComplete else { return }
        if let index = history.firstIndex(where: { $0.configuration == configuration }) {
            history[index].lastUsed = Date()
            if source == .iap { history[index].source = .iap }
        } else {
            history.append(CredentialHistoryEntry(id: UUID(), configuration: configuration, source: source, lastUsed: Date()))
        }
        history.sort { $0.lastUsed > $1.lastUsed }
    }
}

protocol SecureStringStorage {
    func read(account: String) throws -> String?
    func write(_ value: String, account: String) throws
}

@MainActor
final class CredentialsStore {
    nonisolated static let account = "connection_credentials_v1"
    private let storage: SecureStringStorage
    private let defaults: UserDefaults
    private(set) var envelope: CredentialsEnvelope?

    init(storage: SecureStringStorage = KeychainService.shared, defaults: UserDefaults = .standard) {
        self.storage = storage
        self.defaults = defaults
    }

    /// A missing item permits migration; a read/decoding failure never does.
    func load() throws {
        if let value = try storage.read(account: Self.account) {
            let decoded = try JSONDecoder().decode(CredentialsEnvelope.self, from: Data(value.utf8))
            guard decoded.version == 1 else { throw ConfigurationError.storageVersion }
            envelope = decoded
            return
        }
        let legacy = try legacyConfiguration()
        var initial = CredentialsEnvelope(current: legacy)
        if let legacy { initial.record(legacy, source: .general) }
        try persist(initial)
        // Old secure keys are retained as recovery input; after migration they are never read.
        defaults.removeObject(forKey: "token")
        defaults.removeObject(forKey: "encryption_password")
    }

    private func legacyConfiguration() throws -> ConnectionConfiguration? {
        let token = try storage.read(account: "token") ?? defaults.string(forKey: "token") ?? ""
        let pin = try storage.read(account: "encryption_password") ?? defaults.string(forKey: "encryption_password") ?? ""
        let endpoint = defaults.string(forKey: "endpoint_url") ?? "https://kns.afoo.me"
        let configuration = ConnectionConfiguration(endpoint: endpoint, token: token, pin: pin)
        // Preserve partial legacy input too, but do not create an activatable history entry.
        return configuration
    }

    func persist(_ value: CredentialsEnvelope) throws {
        let data = try JSONEncoder().encode(value)
        try storage.write(String(decoding: data, as: UTF8.self), account: Self.account)
        envelope = value
    }

    func delete(id: UUID) throws {
        guard var value = envelope, value.transition == nil else { throw ConfigurationError.recoveryRequired }
        value.history.removeAll { $0.id == id }
        try persist(value)
    }
}

enum ConfigurationError: LocalizedError, Equatable {
    case invalidInput, busy, pendingNotes, recoveryRequired, storageVersion, staleOperation
    var errorDescription: String? {
        switch self {
        case .invalidInput: return NSLocalizedString("Enter a valid HTTP(S) endpoint, token, and encryption password.", comment: "IAP and connection configuration")
        case .busy: return NSLocalizedString("The configuration is being saved. Please wait.", comment: "IAP and connection configuration")
        case .pendingNotes: return NSLocalizedString("Send your pending notes before changing the endpoint, token, or encryption password.", comment: "IAP and connection configuration")
        case .recoveryRequired: return NSLocalizedString("The local configuration has not been recovered. Retry recovery before connecting.", comment: "IAP and connection configuration")
        case .storageVersion: return NSLocalizedString("This credential storage version cannot be read. Your original data is preserved.", comment: "IAP and connection configuration")
        case .staleOperation: return NSLocalizedString("The connection configuration has changed. Please try again.", comment: "IAP and connection configuration")
        }
    }
}
