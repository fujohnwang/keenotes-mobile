import Foundation

enum ProvisionIntent: String, Encodable { case automatic, userInitiated = "user_initiated" }

struct PurchaseConfiguration {
    var productID: String
    var productionURL: String
    var sandboxURL: String
    var termsURL: URL?
    var privacyURL: URL?

    static func load(bundle: Bundle = .main) -> Self {
        func value(_ key: String) -> String {
            let text = (bundle.object(forInfoDictionaryKey: key) as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            return text.contains("$(") ? "" : text
        }
        return Self(productID: value("IAPAnnualProductID"), productionURL: value("IAPProductionProvisionURL"),
                    sandboxURL: value("IAPSandboxProvisionURL"), termsURL: URL(string: value("IAPTermsURL")),
                    privacyURL: URL(string: value("IAPPrivacyURL")))
    }

    func provisionURL(environment: String) throws -> URL {
        let raw: String
        switch environment {
        case "Production": raw = productionURL
        case "Sandbox": raw = sandboxURL
        default: throw PurchaseFailure.configuration(NSLocalizedString("Remote delivery is unavailable for this transaction environment. Local StoreKit tests require a separate test dependency.", comment: "IAP and connection configuration"))
        }
        guard let url = URL(string: raw), Self.isHTTPS(url) else {
            throw PurchaseFailure.configuration(NSLocalizedString("The subscription service is not configured. Please retry delivery later.", comment: "IAP and connection configuration"))
        }
        guard productionURL != sandboxURL else { throw PurchaseFailure.configuration(NSLocalizedString("Configure separate test and production subscription services.", comment: "IAP and connection configuration")) }
        return url
    }

    static func isHTTPS(_ url: URL) -> Bool {
        url.scheme?.lowercased() == "https" && url.host?.isEmpty == false && url.user == nil && url.password == nil && url.fragment == nil
    }
}

struct PurchasedCredentials: Codable, Equatable {
    let endpoint: String
    let token: String
    let entitlementStatus: String
    let expiresAt: Int64
    let provisioningSeconds: Int

    enum CodingKeys: String, CodingKey {
        case endpoint, token
        case entitlementStatus = "entitlement_status"
        case expiresAt = "expires_at"
        case provisioningSeconds = "provisioning_seconds"
    }

    func validate(now: Date = Date()) throws {
        guard let url = URL(string: endpoint), PurchaseConfiguration.isHTTPS(url),
              !token.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              ["active", "grace"].contains(entitlementStatus),
              expiresAt > Int64(now.timeIntervalSince1970),
              expiresAt < 100_000_000_000, (0...60).contains(provisioningSeconds) else {
            throw PurchaseFailure.invalidResponse
        }
    }
}

struct PurchasedRecord: Codable, Equatable {
    let transactionID: String
    let productID: String
    let environment: String
    let credentials: PurchasedCredentials
}

@MainActor
final class PurchasedCredentialsStore {
    nonisolated static let account = "apple_purchased_credentials_v1"
    private let storage: SecureStringStorage
    private(set) var records: [PurchasedRecord] = []
    init(storage: SecureStringStorage = KeychainService.shared) { self.storage = storage }
    func load() throws {
        if let raw = try storage.read(account: Self.account) {
            records = try JSONDecoder().decode([PurchasedRecord].self, from: Data(raw.utf8))
        } else { records = [] }
    }
    func save(_ record: PurchasedRecord) throws {
        // Re-read before every mutation so a previous read failure cannot erase old purchases.
        try load()
        var values = records
        values.removeAll { $0.environment == record.environment && $0.credentials.endpoint == record.credentials.endpoint && $0.credentials.token == record.credentials.token }
        values.append(record)
        try storage.write(String(decoding: JSONEncoder().encode(values), as: UTF8.self), account: Self.account)
        records = values
    }
    func source(for configuration: ConnectionConfiguration) -> CredentialSource {
        records.contains { $0.credentials.endpoint == configuration.endpoint && $0.credentials.token == configuration.token } ? .iap : .general
    }
}

enum PurchaseFailure: LocalizedError {
    case configuration(String), invalidResponse, unverified, unavailable
    case server(code: String, status: Int, retryable: Bool, delay: Int?)
    var retryable: Bool {
        switch self {
        case .server(_, _, let retryable, _): return retryable
        case .configuration, .unverified, .unavailable: return false
        case .invalidResponse: return true
        }
    }
    var errorDescription: String? {
        switch self {
        case .configuration(let message): return message
        case .invalidResponse: return NSLocalizedString("The service did not return valid credentials. Your transaction is preserved. Please retry delivery.", comment: "IAP and connection configuration")
        case .unverified: return NSLocalizedString("Unable to verify the Apple transaction. Service has not been delivered. Please restore purchases to try again.", comment: "IAP and connection configuration")
        case .unavailable: return NSLocalizedString("The annual subscription product is unavailable. Please try again later.", comment: "IAP and connection configuration")
        case .server(let code, _, _, _):
            if code == "ENTITLEMENT_INACTIVE" { return NSLocalizedString("There is no active subscription. Your existing configuration and history are preserved.", comment: "IAP and connection configuration") }
            if code == "ACCESS_DISABLED" { return NSLocalizedString("These subscription credentials are disabled. Restore purchases to request them again.", comment: "IAP and connection configuration") }
            return String(format: NSLocalizedString("Purchased, but service delivery is pending (%@). Retry delivery without paying again.", comment: "IAP and connection configuration"), code)
        }
    }
}

protocol AppleProvisioning {
    func provision(_ transaction: StoreTransaction, intent: ProvisionIntent) async throws -> PurchasedCredentials
}

/// Deliberately separate from user-editable sync settings and TrustAllDelegate.
/// Refuse redirects: signed transactions must only go to the configured URL.
final class AppleProvisioningClient: AppleProvisioning {
    private let configuration: PurchaseConfiguration
    private let session: URLSession
    init(configuration: PurchaseConfiguration, sessionConfiguration: URLSessionConfiguration = .ephemeral) {
        self.configuration = configuration
        sessionConfiguration.urlCache = nil
        sessionConfiguration.httpCookieStorage = nil
        sessionConfiguration.requestCachePolicy = .reloadIgnoringLocalCacheData
        session = URLSession(configuration: sessionConfiguration, delegate: RejectProvisionRedirects(), delegateQueue: nil)
    }
    deinit { session.invalidateAndCancel() }
    func provision(_ transaction: StoreTransaction, intent: ProvisionIntent) async throws -> PurchasedCredentials {
        let url = try configuration.provisionURL(environment: transaction.environment)
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 30)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("no-store", forHTTPHeaderField: "Cache-Control")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["signed_transaction": transaction.signedJWS,
                                                                 "environment": transaction.environment, "intent": intent.rawValue])
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, http.url == url else { throw PurchaseFailure.invalidResponse }
        guard http.statusCode == 200 else {
            struct ServerError: Decodable { let code: String; let retryable: Bool; let retry_after_seconds: Int? }
            let error = try? JSONDecoder().decode(ServerError.self, from: data)
            // Use stable codes only; do not display arbitrary server text containing secrets.
            let knownCodes: Set<String> = ["INVALID_REQUEST", "INVALID_STOREKIT_JWS", "UNSUPPORTED_ENVIRONMENT", "PRODUCT_NOT_ALLOWED", "ENTITLEMENT_INACTIVE", "ACCESS_DISABLED", "STOREKIT_NOT_CONFIGURED", "STOREKIT_UPSTREAM_UNAVAILABLE", "PROVISIONING_PENDING", "STOREKIT_INTERNAL_ERROR"]
            let code = error.flatMap { knownCodes.contains($0.code) ? $0.code : nil } ?? "HTTP_\(http.statusCode)"
            throw PurchaseFailure.server(code: code, status: http.statusCode,
                                         retryable: http.statusCode >= 500 || error?.retryable == true,
                                         delay: error?.retry_after_seconds ?? http.value(forHTTPHeaderField: "Retry-After").flatMap(Int.init))
        }
        guard let material = try? JSONDecoder().decode(PurchasedCredentials.self, from: data) else { throw PurchaseFailure.invalidResponse }
        try material.validate()
        return material
    }
}

private final class RejectProvisionRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}
