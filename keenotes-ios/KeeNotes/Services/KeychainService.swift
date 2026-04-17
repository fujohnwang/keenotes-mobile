import Foundation
import Security

/// Keychain wrapper for storing sensitive credentials (token, encryption password).
/// Uses kSecAttrAccessibleWhenUnlockedThisDeviceOnly for security without biometry prompts.
final class KeychainService {
    
    static let shared = KeychainService()
    
    private let service = "cn.keevol.keenotes"
    
    private init() {}
    
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
