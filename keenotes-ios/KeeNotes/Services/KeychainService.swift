import Foundation
import Security

/// Keychain wrapper for storing sensitive credentials (token, encryption password).
/// Uses kSecAttrAccessibleWhenUnlockedThisDeviceOnly for security without biometry prompts.
final class KeychainService: SecureStringStorage {
    
    static let shared = KeychainService()
    
    private let service: String
    
    init(service: String = Bundle.main.bundleIdentifier ?? "cn.keevol.keenotes") { self.service = service }
    
    enum StorageError: LocalizedError {
        case status(OSStatus), invalidData
        var errorDescription: String? { NSLocalizedString("Unable to read or save the Keychain. Your original credentials are preserved.", comment: "IAP and connection configuration") }
    }

    func read(account: String) throws -> String? {
        var query = baseQuery(account: account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw StorageError.status(status) }
        guard let data = result as? Data, let value = String(data: data, encoding: .utf8) else {
            throw StorageError.invalidData
        }
        return value
    }

    func write(_ value: String, account: String) throws {
        let data = Data(value.utf8)
        let status = SecItemUpdate(baseQuery(account: account) as CFDictionary,
                                   [kSecValueData as String: data] as CFDictionary)
        if status == errSecSuccess { return }
        guard status == errSecItemNotFound else { throw StorageError.status(status) }
        var query = baseQuery(account: account)
        query[kSecValueData as String] = data
        let added = SecItemAdd(query as CFDictionary, nil)
        guard added == errSecSuccess else { throw StorageError.status(added) }
    }

    // MARK: - Public API
    
    /// Save a string value to Keychain. Returns true on success.
    @discardableResult
    func save(_ value: String, forAccount account: String) -> Bool {
        guard let data = value.data(using: .utf8) else {
            print("[KeychainService] Failed to encode value for account: \(account)")
            return false
        }
        
        // Try update first; if item doesn't exist, add it
        let updateQuery = baseQuery(account: account)
        let updateAttributes: [String: Any] = [kSecValueData as String: data]
        
        let updateStatus = SecItemUpdate(updateQuery as CFDictionary, updateAttributes as CFDictionary)
        
        if updateStatus == errSecSuccess {
            return true
        }
        
        if updateStatus == errSecItemNotFound {
            var addQuery = baseQuery(account: account)
            addQuery[kSecValueData as String] = data
            let addStatus = SecItemAdd(addQuery as CFDictionary, nil)
            if addStatus != errSecSuccess {
                print("[KeychainService] Failed to add item for account: \(account), status: \(addStatus)")
                return false
            }
            return true
        }
        
        print("[KeychainService] Failed to update item for account: \(account), status: \(updateStatus)")
        return false
    }
    
    /// Load a string value from Keychain. Returns nil if not found or on error.
    func load(account: String) -> String? {
        var query = baseQuery(account: account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        
        guard status == errSecSuccess, let data = result as? Data else {
            if status != errSecItemNotFound {
                print("[KeychainService] Failed to load item for account: \(account), status: \(status)")
            }
            return nil
        }
        
        return String(data: data, encoding: .utf8)
    }
    
    /// Delete a value from Keychain. Returns true on success or if item didn't exist.
    @discardableResult
    func delete(account: String) -> Bool {
        let query = baseQuery(account: account)
        let status = SecItemDelete(query as CFDictionary)
        if status != errSecSuccess && status != errSecItemNotFound {
            print("[KeychainService] Failed to delete item for account: \(account), status: \(status)")
            return false
        }
        return true
    }
    
    // MARK: - Private
    
    private func baseQuery(account: String) -> [String: Any] {
        return [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        ]
    }
}
