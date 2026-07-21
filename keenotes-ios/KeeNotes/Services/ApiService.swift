import Foundation

struct PreparedNote {
    let content: String
    let encryptedContent: String
    let channel: String
    let createdAt: String
    let requestId: String
}

enum ApiServiceError: LocalizedError {
    case settingsNotConfigured
    case encryptionPasswordNotSet
    case invalidEndpointUrl

    var errorDescription: String? {
        switch self {
        case .settingsNotConfigured:
            return "Please configure server settings first"
        case .encryptionPasswordNotSet:
            return "PIN code not set"
        case .invalidEndpointUrl:
            return "Invalid endpoint URL"
        }
    }
}

/// REST API service for posting notes
class ApiService {
    private let settingsService: SettingsService
    private let cryptoService: CryptoService

    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 30
        // Trust all certificates for development (similar to Android)
        return URLSession(configuration: config, delegate: TrustAllDelegate(), delegateQueue: nil)
    }()

    init(settingsService: SettingsService, cryptoService: CryptoService) {
        self.settingsService = settingsService
        self.cryptoService = cryptoService
    }

    struct PostResult {
        let success: Bool
        let message: String
        let noteId: Int64?
        let echoContent: String?
        let networkError: Bool

        init(success: Bool, message: String, noteId: Int64?, echoContent: String?, networkError: Bool = false) {
            self.success = success
            self.message = message
            self.noteId = noteId
            self.echoContent = echoContent
            self.networkError = networkError
        }
    }

    /// Post a new note to the server
    func postNote(content: String) async -> PostResult {
        do {
            let preparedNote = try prepareNote(content: content)
            return await postPreparedNote(preparedNote)
        } catch {
            return PostResult(
                success: false,
                message: error.localizedDescription,
                noteId: nil,
                echoContent: nil
            )
        }
    }

    func prepareNote(content: String, channel: String = "mobile-ios") throws -> PreparedNote {
        guard !settingsService.endpointUrl.isEmpty, !settingsService.token.isEmpty else {
            throw ApiServiceError.settingsNotConfigured
        }

        guard cryptoService.isEncryptionEnabled else {
            throw ApiServiceError.encryptionPasswordNotSet
        }

        let encrypted = try cryptoService.encrypt(content)
        let ts = currentUtcTimestamp()

        return PreparedNote(
            content: content,
            encryptedContent: encrypted,
            channel: channel,
            createdAt: ts,
            requestId: UUID().uuidString
        )
    }

    func postPreparedNote(_ note: PreparedNote) async -> PostResult {
        guard !settingsService.endpointUrl.isEmpty, !settingsService.token.isEmpty else {
            return PostResult(success: false, message: "Please configure server settings first", noteId: nil, echoContent: nil)
        }

        do {
            // Build JSON body - match JavaFX format exactly
            let body: [String: Any] = [
                "channel": note.channel,
                "text": note.encryptedContent,
                "ts": note.createdAt,
                "encrypted": true,
                "request_id": note.requestId
            ]

            guard let url = URL(string: settingsService.endpointUrl) else {
                return PostResult(success: false, message: "Invalid endpoint URL", noteId: nil, echoContent: nil)
            }

            var request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.setValue("Bearer \(settingsService.token)", forHTTPHeaderField: "Authorization")
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: body)

            let (data, response) = try await session.data(for: request)

            guard let httpResponse = response as? HTTPURLResponse else {
                return PostResult(success: false, message: "Invalid response", noteId: nil, echoContent: nil)
            }

            if httpResponse.statusCode >= 200 && httpResponse.statusCode < 300 {
                let noteId = parseNoteId(from: data)
                return PostResult(
                    success: true,
                    message: "Note saved successfully",
                    noteId: noteId,
                    echoContent: note.content
                )
            } else {
                return PostResult(
                    success: false,
                    message: "Server error: \(httpResponse.statusCode)",
                    noteId: nil,
                    echoContent: nil
                )
            }
        } catch {
            return PostResult(
                success: false,
                message: "Network error: \(error.localizedDescription)",
                noteId: nil,
                echoContent: nil,
                networkError: true
            )
        }
    }

    private func parseNoteId(from data: Data) -> Int64? {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        if let id = json["id"] as? Int64 {
            return id > 0 ? id : nil
        }
        if let id = json["id"] as? NSNumber {
            return id.int64Value > 0 ? id.int64Value : nil
        }
        return nil
    }

    private func currentUtcTimestamp() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
        formatter.timeZone = TimeZone(identifier: "UTC")
        return formatter.string(from: Date())
    }
}

/// URLSession delegate that trusts all certificates (for development)
class TrustAllDelegate: NSObject, URLSessionDelegate {
    func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        if challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
           let serverTrust = challenge.protectionSpace.serverTrust {
            completionHandler(.useCredential, URLCredential(trust: serverTrust))
        } else {
            completionHandler(.performDefaultHandling, nil)
        }
    }
}
