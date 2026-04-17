import Foundation
import SwiftUI

/// Service for managing app settings.
/// Sensitive credentials (token, encryptionPassword) are stored in Keychain.
/// Non-sensitive preferences remain in UserDefaults.
class SettingsService: ObservableObject {
    private let defaults = UserDefaults.standard
    private let keychain = KeychainService.shared
    
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
    
    @Published var endpointUrl: String {
        didSet { defaults.set(endpointUrl, forKey: Keys.endpointUrl) }
    }
    
    /// Tracks whether the last Keychain write succeeded.
    /// SettingsView should check this after calling saveSettings().
    @Published private(set) var lastSaveError: String?
    
    /// When true, didSet skips Keychain writes (saveSettings handles persistence itself).
    private var suppressKeychainWrite = false
    
    @Published var token: String {
        didSet {
            guard !suppressKeychainWrite else { return }
            if !keychain.save(token, forAccount: Keys.token) {
                print("[SettingsService] WARNING: Failed to save token to Keychain")
            }
        }
    }
    
    @Published var encryptionPassword: String {
        didSet {
            guard !suppressKeychainWrite else { return }
            if !keychain.save(encryptionPassword, forAccount: Keys.encryptionPassword) {
                print("[SettingsService] WARNING: Failed to save encryption password to Keychain")
            }
        }
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
    
    init() {
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
        
        // Migrate from UserDefaults to Keychain if needed
        migrateToKeychainIfNeeded()
        
        // Load sensitive credentials from Keychain
        self.token = keychain.load(account: Keys.token) ?? ""
        self.encryptionPassword = keychain.load(account: Keys.encryptionPassword) ?? ""
    }
    
    /// One-time migration: move token & encryptionPassword from UserDefaults to Keychain.
    /// Idempotent — safe to call multiple times. Only imports old values when Keychain is empty.
    /// If Keychain already has values (e.g. reinstall preserving Keychain), just cleans up UserDefaults.
    private func migrateToKeychainIfNeeded() {
        guard !defaults.bool(forKey: Keys.keychainMigrated) else { return }
        
        print("[SettingsService] Starting Keychain migration...")
        var allSucceeded = true
        
        // Migrate token
        if let oldToken = defaults.string(forKey: Keys.token), !oldToken.isEmpty {
            if keychain.load(account: Keys.token) != nil {
                // Keychain already has a value — keep it, just clean up UserDefaults
                defaults.removeObject(forKey: Keys.token)
                print("[SettingsService] Token already in Keychain, cleaned UserDefaults")
            } else if keychain.save(oldToken, forAccount: Keys.token) {
                defaults.removeObject(forKey: Keys.token)
                print("[SettingsService] Token migrated to Keychain")
            } else {
                print("[SettingsService] WARNING: Failed to migrate token — keeping in UserDefaults")
                allSucceeded = false
            }
        }
        
        // Migrate encryptionPassword
        if let oldPassword = defaults.string(forKey: Keys.encryptionPassword), !oldPassword.isEmpty {
            if keychain.load(account: Keys.encryptionPassword) != nil {
                defaults.removeObject(forKey: Keys.encryptionPassword)
                print("[SettingsService] Encryption password already in Keychain, cleaned UserDefaults")
            } else if keychain.save(oldPassword, forAccount: Keys.encryptionPassword) {
                defaults.removeObject(forKey: Keys.encryptionPassword)
                print("[SettingsService] Encryption password migrated to Keychain")
            } else {
                print("[SettingsService] WARNING: Failed to migrate encryption password — keeping in UserDefaults")
                allSucceeded = false
            }
        }
        
        if allSucceeded {
            defaults.set(true, forKey: Keys.keychainMigrated)
            print("[SettingsService] Keychain migration completed")
        }
    }
    
    var isConfigured: Bool {
        !endpointUrl.isEmpty && !token.isEmpty && !encryptionPassword.isEmpty
    }
    
    var isEncryptionEnabled: Bool {
        !encryptionPassword.isEmpty
    }
    
    /// Save all connection settings atomically.
    /// Writes Keychain first; only updates memory/UserDefaults on full success.
    /// On failure, nothing changes — caller should check `lastSaveError`.
    func saveSettings(endpoint: String, token: String, password: String) {
        lastSaveError = nil
        
        let oldToken = self.token
        
        // 1. Write token to Keychain
        guard keychain.save(token, forAccount: Keys.token) else {
            lastSaveError = "Failed to save token to Keychain"
            print("[SettingsService] WARNING: \(lastSaveError!)")
            return
        }
        
        // 2. Write password to Keychain
        guard keychain.save(password, forAccount: Keys.encryptionPassword) else {
            // Roll back token — if rollback also fails, report both
            if !keychain.save(oldToken, forAccount: Keys.token) {
                lastSaveError = "Failed to save encryption password to Keychain; token rollback also failed — Keychain may be inconsistent"
            } else {
                lastSaveError = "Failed to save encryption password to Keychain"
            }
            print("[SettingsService] WARNING: \(lastSaveError!)")
            return
        }
        
        // 3. Keychain succeeded — now update memory (suppress didSet Keychain writes)
        suppressKeychainWrite = true
        self.endpointUrl = endpoint.trimmingCharacters(in: .whitespacesAndNewlines)
        self.token = token
        self.encryptionPassword = password
        suppressKeychainWrite = false
    }
}
