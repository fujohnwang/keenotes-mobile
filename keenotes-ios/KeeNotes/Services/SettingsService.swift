import Foundation
import SwiftUI

/// Service for managing app settings.
/// Sensitive credentials (token, encryptionPassword) are stored in Keychain.
/// Non-sensitive preferences remain in UserDefaults.
@MainActor
class SettingsService: ObservableObject {
    private let defaults: UserDefaults
    let credentialsStore: CredentialsStore
    let access = ConfigurationAccess()
    @Published private(set) var history: [CredentialHistoryEntry] = []
    @Published private(set) var configurationError: String?
    
    private enum Keys {
        static let endpointUrl = "endpoint_url"
        static let token = "token"
        static let encryptionPassword = "encryption_password"
        static let reviewDays = "review_days"
        static let copyToClipboardOnPost = "copy_to_clipboard_on_post"
        static let showOverviewCard = "show_overview_card"
        static let firstNoteDate = "first_note_date"
        static let autoFocusInputOnLaunch = "auto_focus_input_on_launch"
        static let autoStartDictation = "auto_start_dictation"
        static let confettiOnPostSuccess = "confetti_on_post_success"
        static let hiddenMessage = "hidden_message"
        static let showSyncChannelStatus = "show_sync_channel_status"
        static let compactDateFormat = "compact_date_format"
        static let showOnThisDayInYearsPast = "show_on_this_day_in_years_past"
        static let keychainMigrated = "keychain_migrated"
    }
    
    @Published private(set) var endpointUrl: String
    @Published private(set) var token: String
    @Published private(set) var encryptionPassword: String

    var configuration: ConnectionConfiguration {
        ConnectionConfiguration(endpoint: endpointUrl, token: token, pin: encryptionPassword)
    }

    func reloadCredentials() throws {
        do {
            try credentialsStore.load()
            publishCredentials()
            configurationError = nil
        } catch {
            configurationError = error.localizedDescription
            throw error
        }
    }

    func publishCredentials() {
        if let current = credentialsStore.envelope?.current {
            endpointUrl = current.endpoint
            token = current.token
            encryptionPassword = current.pin
        }
        history = credentialsStore.envelope?.history ?? []
    }

    func deleteHistory(id: UUID) throws {
        try credentialsStore.delete(id: id)
        publishCredentials()
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
    
    @Published var autoStartDictation: Bool {
        didSet {
            defaults.set(autoStartDictation, forKey: Keys.autoStartDictation)
        }
    }
    
    @Published var confettiOnPostSuccess: Bool {
        didSet {
            defaults.set(confettiOnPostSuccess, forKey: Keys.confettiOnPostSuccess)
        }
    }
    
    @Published var hiddenMessage: String {
        didSet {
            defaults.set(hiddenMessage, forKey: Keys.hiddenMessage)
        }
    }
    
    @Published var showSyncChannelStatus: Bool {
        didSet {
            defaults.set(showSyncChannelStatus, forKey: Keys.showSyncChannelStatus)
        }
    }

    @Published var compactDateFormat: Bool {
        didSet {
            defaults.set(compactDateFormat, forKey: Keys.compactDateFormat)
        }
    }

    @Published var showOnThisDayInYearsPast: Bool {
        didSet {
            defaults.set(showOnThisDayInYearsPast, forKey: Keys.showOnThisDayInYearsPast)
        }
    }
    
    @Published var firstNoteDate: String? {
        didSet {
            print("[SettingsService] firstNoteDate didSet: old=\(oldValue ?? "nil"), new=\(firstNoteDate ?? "nil")")
            if let value = firstNoteDate {
                defaults.set(value, forKey: Keys.firstNoteDate)
            } else {
                defaults.removeObject(forKey: Keys.firstNoteDate)
            }
        }
    }
    
    init(defaults: UserDefaults = .standard, storage: SecureStringStorage = KeychainService.shared) {
        self.defaults = defaults
        self.credentialsStore = CredentialsStore(storage: storage, defaults: defaults)
        // Initialize non-sensitive settings from UserDefaults
        self.endpointUrl = defaults.string(forKey: Keys.endpointUrl) ?? "https://kns.afoo.me"
        
        let savedReviewDays = defaults.integer(forKey: Keys.reviewDays)
        self.reviewDays = savedReviewDays == 0 ? 7 : savedReviewDays
        
        self.copyToClipboardOnPost = defaults.bool(forKey: Keys.copyToClipboardOnPost)
        self.showOverviewCard = defaults.object(forKey: Keys.showOverviewCard) == nil ? true : defaults.bool(forKey: Keys.showOverviewCard)
        self.autoFocusInputOnLaunch = defaults.object(forKey: Keys.autoFocusInputOnLaunch) == nil ? false : defaults.bool(forKey: Keys.autoFocusInputOnLaunch)
        self.autoStartDictation = defaults.object(forKey: Keys.autoStartDictation) == nil ? false : defaults.bool(forKey: Keys.autoStartDictation)
        self.confettiOnPostSuccess = defaults.object(forKey: Keys.confettiOnPostSuccess) == nil ? true : defaults.bool(forKey: Keys.confettiOnPostSuccess)
        self.hiddenMessage = defaults.string(forKey: Keys.hiddenMessage) ?? ""
        self.showSyncChannelStatus = defaults.object(forKey: Keys.showSyncChannelStatus) == nil ? false : defaults.bool(forKey: Keys.showSyncChannelStatus)
        self.compactDateFormat = defaults.object(forKey: Keys.compactDateFormat) == nil ? true : defaults.bool(forKey: Keys.compactDateFormat)
        self.showOnThisDayInYearsPast = defaults.object(forKey: Keys.showOnThisDayInYearsPast) == nil ? true : defaults.bool(forKey: Keys.showOnThisDayInYearsPast)
        self.firstNoteDate = defaults.string(forKey: Keys.firstNoteDate)
        
        // Initialize sensitive fields as empty first (required before calling methods)
        self.token = ""
        self.encryptionPassword = ""
        
        try? reloadCredentials()
    }

    var isConfigured: Bool {
        !endpointUrl.isEmpty && !token.isEmpty && !encryptionPassword.isEmpty
    }
    
    var isEncryptionEnabled: Bool {
        !encryptionPassword.isEmpty
    }
    
}
