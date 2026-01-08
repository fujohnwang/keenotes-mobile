import Foundation
import SwiftUI

/// Service for managing app settings using UserDefaults
class SettingsService: ObservableObject {
    private let defaults = UserDefaults.standard
    
    private enum Keys {
        static let endpointUrl = "endpoint_url"
        static let token = "token"
        static let encryptionPassword = "encryption_password"
        static let reviewDays = "review_days"
        static let copyToClipboardOnPost = "copy_to_clipboard_on_post"
    }
    
    @Published var endpointUrl: String {
        didSet { defaults.set(endpointUrl, forKey: Keys.endpointUrl) }
    }
    
    @Published var token: String {
        didSet { defaults.set(token, forKey: Keys.token) }
    }
    
    @Published var encryptionPassword: String {
        didSet { defaults.set(encryptionPassword, forKey: Keys.encryptionPassword) }
    }
    
    @Published var reviewDays: Int {
        didSet { defaults.set(reviewDays, forKey: Keys.reviewDays) }
    }
    
    @Published var copyToClipboardOnPost: Bool {
        didSet { defaults.set(copyToClipboardOnPost, forKey: Keys.copyToClipboardOnPost) }
    }
    
    init() {
        self.endpointUrl = defaults.string(forKey: Keys.endpointUrl) ?? ""
        self.token = defaults.string(forKey: Keys.token) ?? ""
        self.encryptionPassword = defaults.string(forKey: Keys.encryptionPassword) ?? ""
        self.reviewDays = defaults.integer(forKey: Keys.reviewDays)
        if self.reviewDays == 0 {
            self.reviewDays = 7
        }
        self.copyToClipboardOnPost = defaults.bool(forKey: Keys.copyToClipboardOnPost)
    }
    
    var isConfigured: Bool {
        !endpointUrl.isEmpty && !token.isEmpty
    }
    
    var isEncryptionEnabled: Bool {
        !encryptionPassword.isEmpty
    }
    
    func saveSettings(endpoint: String, token: String, password: String) {
        self.endpointUrl = endpoint.trimmingCharacters(in: .whitespacesAndNewlines)
        self.token = token
        self.encryptionPassword = password
    }
}
