import Foundation

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
    
    private let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return formatter
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
    }
    
    /// Post a new note to the server
    func postNote(content: String) async -> PostResult {
        guard !settingsService.endpointUrl.isEmpty, !settingsService.token.isEmpty else {
            return PostResult(success: false, message: "Please configure server settings first", noteId: nil, echoContent: nil)
        }
        
        guard cryptoService.isEncryptionEnabled else {
            return PostResult(success: false, message: "PIN code not set", noteId: nil, echoContent: nil)
        }
        
        do {
            // Encrypt content
            let encrypted = try cryptoService.encrypt(content)
            let ts = dateFormatter.string(from: Date())
            
            // Build JSON body - match JavaFX format exactly
            let body: [String: Any] = [
                "channel": "mobile-ios",
                "text": encrypted,
                "ts": ts,
                "encrypted": true
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
                    echoContent: content
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
                echoContent: nil
            )
        }
    }
    
    private func parseNoteId(from data: Data) -> Int64? {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let id = json["id"] as? Int64 else {
            return nil
        }
        return id > 0 ? id : nil
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
