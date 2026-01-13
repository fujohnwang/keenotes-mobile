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
        static let showOverviewCard = "show_overview_card"
        static let firstNoteDate = "first_note_date"
        static let autoFocusInputOnLaunch = "auto_focus_input_on_launch"
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
        didSet { 
            defaults.set(copyToClipboardOnPost, forKey: Keys.copyToClipboardOnPost)
        }
    }
    
    @Published var showOverviewCard: Bool {
        didSet {
            defaults.set(showOverviewCard, forKey: Keys.showOverviewCard)
        }
    }
    
    @Published var autoFocusInputOnLaunch: Bool {
        didSet {
            defaults.set(autoFocusInputOnLaunch, forKey: Keys.autoFocusInputOnLaunch)
        }
    }
    
    var firstNoteDate: String? {
        get { defaults.string(forKey: Keys.firstNoteDate) }
        set { 
            if let value = newValue {
                defaults.set(value, forKey: Keys.firstNoteDate)
            } else {
                defaults.removeObject(forKey: Keys.firstNoteDate)
            }
        }
    }
    
    init() {
        // Initialize all @Published properties first
        self.endpointUrl = defaults.string(forKey: Keys.endpointUrl) ?? ""
        self.token = defaults.string(forKey: Keys.token) ?? ""
        self.encryptionPassword = defaults.string(forKey: Keys.encryptionPassword) ?? ""
        
        let savedReviewDays = defaults.integer(forKey: Keys.reviewDays)
        self.reviewDays = savedReviewDays == 0 ? 7 : savedReviewDays
        
        self.copyToClipboardOnPost = defaults.bool(forKey: Keys.copyToClipboardOnPost)
        self.showOverviewCard = defaults.object(forKey: Keys.showOverviewCard) == nil ? true : defaults.bool(forKey: Keys.showOverviewCard)
        self.autoFocusInputOnLaunch = defaults.object(forKey: Keys.autoFocusInputOnLaunch) == nil ? true : defaults.bool(forKey: Keys.autoFocusInputOnLaunch)
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
