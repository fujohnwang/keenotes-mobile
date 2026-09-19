import XCTest
import GRDB
@testable import KeeNotes

final class ProvisionURLProtocol: URLProtocol {
    static var handler: ((URLRequest) throws -> (Int, [String: String], Data))?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        do {
            let (status, headers, data) = try Self.handler!(request)
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)!
            if let location = headers["Location"], let url = URL(string: location) {
                client?.urlProtocol(self, wasRedirectedTo: URLRequest(url: url), redirectResponse: response)
                client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
                client?.urlProtocolDidFinishLoading(self)
            } else {
                client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
                client?.urlProtocol(self, didLoad: data)
                client?.urlProtocolDidFinishLoading(self)
            }
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() { }
}

@MainActor
final class ProvisioningBoundaryTests: XCTestCase {
    private let production = "https://production.example.invalid/iap/apple/provision"
    private let sandbox = "https://sandbox.example.invalid/iap/apple/provision"
    private func configuration() -> PurchaseConfiguration {
        PurchaseConfiguration(productID: "local.test.annual", productionURL: production, sandboxURL: sandbox)
    }
    private func sessionConfiguration() -> URLSessionConfiguration {
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [ProvisionURLProtocol.self]; return config
    }
    private func transaction(environment: String = "Sandbox") -> StoreTransaction {
        StoreTransaction(id: "42", productID: "local.test.annual", environment: environment, signedJWS: "local-fixture-JWS", finish: {})
    }
    override func tearDown() { ProvisionURLProtocol.handler = nil; super.tearDown() }

    func testRoutesVerifiedEnvironmentToFixedURLsAndSendsNoPINOrBearer() async throws {
        var requests: [URLRequest] = []
        ProvisionURLProtocol.handler = { request in
            requests.append(request)
            return (200, ["Cache-Control": "no-store"], try JSONEncoder().encode(PurchaseBehaviorTests.material()))
        }
        let client = AppleProvisioningClient(configuration: configuration(), sessionConfiguration: sessionConfiguration())
        for environment in ["Sandbox", "Production"] {
            _ = try await client.provision(transaction(environment: environment), intent: .automatic)
        }
        XCTAssertEqual(requests.map { $0.url!.absoluteString }, [sandbox, production])
        for request in requests {
            XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
            XCTAssertEqual(request.value(forHTTPHeaderField: "Cache-Control"), "no-store")
            // URLProtocol may expose body as a stream; inspect it without printing secrets.
            let body: Data
            if let data = request.httpBody { body = data }
            else {
                let stream = request.httpBodyStream!; stream.open(); defer { stream.close() }
                var bytes = [UInt8](repeating: 0, count: 4096)
                let count = stream.read(&bytes, maxLength: bytes.count); body = Data(bytes.prefix(max(0, count)))
            }
            let json = try JSONSerialization.jsonObject(with: body) as! [String: String]
            XCTAssertEqual(Set(json.keys), Set(["signed_transaction", "environment", "intent"]))
            XCTAssertEqual(json["intent"], "automatic")
        }
    }

    func testRejectsLocalStoreKitHTTPAndMalformedSuccessWithoutDelivery() async throws {
        let client = AppleProvisioningClient(configuration: configuration(), sessionConfiguration: sessionConfiguration())
        ProvisionURLProtocol.handler = { _ in XCTFail("No request allowed"); return (500, [:], Data()) }
        do { _ = try await client.provision(transaction(environment: "Xcode"), intent: .automatic); XCTFail() } catch {}
        var config = configuration(); config.sandboxURL = "http://sandbox.example.invalid/iap/apple/provision"
        let insecure = AppleProvisioningClient(configuration: config, sessionConfiguration: sessionConfiguration())
        do { _ = try await insecure.provision(transaction(), intent: .automatic); XCTFail() } catch {}
        let material = PurchaseBehaviorTests.material()
        for payload: [String: Any] in [
            ["endpoint": material.endpoint, "token": "", "entitlement_status": "active", "expires_at": material.expiresAt, "provisioning_seconds": 0],
            ["endpoint": "http://bad.example.invalid", "token": "t", "entitlement_status": "active", "expires_at": material.expiresAt, "provisioning_seconds": 0],
            ["endpoint": material.endpoint, "token": "t", "entitlement_status": "expired", "expires_at": material.expiresAt, "provisioning_seconds": 0],
            ["endpoint": material.endpoint, "token": "t", "entitlement_status": "active", "expires_at": 1, "provisioning_seconds": 0],
            ["endpoint": material.endpoint, "token": "t", "entitlement_status": "active", "expires_at": material.expiresAt * 1000, "provisioning_seconds": 61]
        ] {
            let data = try JSONSerialization.data(withJSONObject: payload)
            ProvisionURLProtocol.handler = { _ in (200, [:], data) }
            do { _ = try await client.provision(transaction(), intent: .userInitiated); XCTFail("invalid response") } catch {}
        }
    }

    func testRedirectIsRejectedAndClientHasNoSessionRetainCycle() async {
        var urls: [URL] = []
        ProvisionURLProtocol.handler = { request in
            urls.append(request.url!)
            return (302, ["Location": "https://attacker.example.invalid/capture"], Data())
        }
        var client: AppleProvisioningClient? = AppleProvisioningClient(configuration: configuration(), sessionConfiguration: sessionConfiguration())
        weak let weakClient = client
        do { _ = try await client!.provision(transaction(), intent: .automatic); XCTFail("redirect must fail") }
        catch let error as PurchaseFailure {
            guard case .server(_, 302, _, _) = error else { return XCTFail("Must reject the original HTTP redirect") }
        } catch { XCTFail("Expected redirect rejection, not timeout: \(error)") }
        XCTAssertEqual(urls.map(\.absoluteString), [sandbox])
        client = nil
        XCTAssertNil(weakClient)
    }

    func testHTTPSUsesSystemTrust() async throws {
        // Optional local TLS fixture started by the validation script; never contacts production.
        let port = ProcessInfo.processInfo.environment["KEENOTES_TLS_FIXTURE_PORT"] ?? "18443"
        var config = configuration(); config.sandboxURL = "https://127.0.0.1:\(port)/iap/apple/provision"
        let client = AppleProvisioningClient(configuration: config)
        do { _ = try await client.provision(transaction(), intent: .automatic); XCTFail("self-signed TLS must be refused") }
        catch let error as URLError {
            XCTAssertTrue([.serverCertificateUntrusted, .serverCertificateHasUnknownRoot, .serverCertificateHasBadDate, .secureConnectionFailed].contains(error.code))
        }
    }

    func testSuccessfulHTTPWithPendingCleanupFailureKeepsSameRequestWithoutResendDraft() async throws {
        let storage = FaultInjectingKeychain()
        let defaults = UserDefaults(suiteName: UUID().uuidString)!
        let settings = SettingsService(defaults: defaults, storage: storage)
        let db = DatabaseService(); try db.initialize(path: ":memory:")
        let coordinator = ConnectionConfigurationCoordinator(settings: settings, database: db, disconnect: {}, connect: {})
        try await coordinator.recover()
        try await coordinator.apply(ConnectionConfiguration(endpoint: "https://manual.example.invalid", token: "manual-token", pin: "PIN"), source: .general)
        let crypto = CryptoService(passwordProvider: { settings.encryptionPassword })
        let api = ApiService(settingsService: settings, cryptoService: crypto, session: URLSession(configuration: sessionConfiguration()))
        let socket = WebSocketService(settingsService: settings, cryptoService: crypto, databaseService: db)
        let pending = PendingNoteService(databaseService: db, apiService: api, webSocketService: socket, access: settings.access)
        ProvisionURLProtocol.handler = { _ in (200, [:], Data("{\"id\":123}".utf8)) }
        try await db.dbQueue!.write { try $0.execute(sql: "CREATE TRIGGER refuse_pending_delete BEFORE DELETE ON pending_notes BEGIN SELECT RAISE(ABORT, 'injected'); END") }
        let lease = try settings.access.begin(); defer { settings.access.end(lease) }
        let prepared = try api.prepareNote(content: "already sent")
        let result = try await pending.deliver(prepared, online: true)
        XCTAssertEqual(result, .sentAwaitingCleanup, "This result must not take the unpersisted-error/draft-restoration path")
        let queued = try await db.getPendingNotes()
        XCTAssertEqual(queued.count, 1)
        XCTAssertEqual(queued.first?.requestId, prepared.requestId)
    }
}
