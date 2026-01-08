import Foundation
import CryptoKit
import CommonCrypto

/// Crypto service using Argon2id + HKDF-SHA256 + AES-256-GCM
/// Compatible with JavaFX and Android versions
class CryptoService {
    
    // Constants matching Java/Kotlin implementation
    private let gcmTagLength = 16  // 128 bits = 16 bytes
    private let gcmIvLength = 12
    private let saltLength = 16
    private let keyLength = 32  // 256 bits
    private let timestampLength = 8
    
    // Argon2 parameters
    private let argon2Iterations: UInt32 = 3
    private let argon2MemoryKB: UInt32 = 65536  // 64MB
    private let argon2Parallelism: UInt32 = 1
    
    // HKDF info
    private let hkdfInfo = "KeeNotes-E2E-Encryption-v2".data(using: .utf8)!
    
    private let passwordProvider: () -> String?
    
    init(passwordProvider: @escaping () -> String?) {
        self.passwordProvider = passwordProvider
    }
    
    var isEncryptionEnabled: Bool {
        guard let password = passwordProvider() else { return false }
        return !password.isEmpty
    }
    
    /// Encrypt plaintext using Argon2+HKDF+AES-GCM
    /// Format: Base64(version[1] + salt[16] + iv[12] + timestamp[8] + ciphertext + tag[16])
    func encrypt(_ plaintext: String) throws -> String {
        guard let password = passwordProvider(), !password.isEmpty else {
            throw CryptoError.passwordNotSet
        }
        
        // Generate random salt and IV
        var salt = Data(count: saltLength)
        var iv = Data(count: gcmIvLength)
        _ = salt.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, saltLength, $0.baseAddress!) }
        _ = iv.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, gcmIvLength, $0.baseAddress!) }
        
        // Timestamp as big-endian Int64
        let timestamp = Int64(Date().timeIntervalSince1970 * 1000)
        let timestampBytes = withUnsafeBytes(of: timestamp.bigEndian) { Data($0) }
        
        // Derive key using Argon2id + HKDF
        let key = try deriveKey(password: password, salt: salt)
        
        // Encrypt using AES-GCM
        let plaintextData = plaintext.data(using: .utf8)!
        let sealedBox = try AES.GCM.seal(
            plaintextData,
            using: key,
            nonce: AES.GCM.Nonce(data: iv),
            authenticating: timestampBytes
        )
        
        // Combine: version(1) + salt(16) + iv(12) + timestamp(8) + ciphertext + tag
        var combined = Data()
        combined.append(0x02)  // Version: Argon2+HKDF
        combined.append(salt)
        combined.append(iv)
        combined.append(timestampBytes)
        combined.append(sealedBox.ciphertext)
        combined.append(sealedBox.tag)
        
        return combined.base64EncodedString()
    }
    
    /// Decrypt ciphertext
    func decrypt(_ encryptedBase64: String) throws -> String {
        guard let password = passwordProvider(), !password.isEmpty else {
            throw CryptoError.passwordNotSet
        }
        return try decryptWithPassword(encryptedBase64, password: password)
    }
    
    /// Decrypt with explicit password (avoids callback, useful for cached password)
    func decryptWithPassword(_ encryptedBase64: String, password: String) throws -> String {
        guard let combined = Data(base64Encoded: encryptedBase64) else {
            throw CryptoError.invalidFormat
        }
        
        let minLength = 1 + saltLength + gcmIvLength + timestampLength + gcmTagLength
        guard combined.count >= minLength else {
            throw CryptoError.invalidFormat
        }
        
        let version = combined[0]
        guard version == 0x02 else {
            throw CryptoError.unsupportedVersion
        }
        
        return try decryptV2(combined: combined, password: password)
    }
    
    private func decryptV2(combined: Data, password: String) throws -> String {
        var pos = 1  // Skip version byte
        
        let salt = combined[pos..<pos+saltLength]
        pos += saltLength
        
        let iv = combined[pos..<pos+gcmIvLength]
        pos += gcmIvLength
        
        let timestampBytes = combined[pos..<pos+timestampLength]
        pos += timestampLength
        
        let ciphertextAndTag = combined[pos...]
        let ciphertextLength = ciphertextAndTag.count - gcmTagLength
        guard ciphertextLength >= 0 else {
            throw CryptoError.invalidFormat
        }
        
        let ciphertext = ciphertextAndTag[pos..<pos+ciphertextLength]
        let tag = ciphertextAndTag[(pos+ciphertextLength)...]
        
        // Validate timestamp (not older than 100 years)
        let timestamp = timestampBytes.withUnsafeBytes { $0.load(as: Int64.self).bigEndian }
        let age = Int64(Date().timeIntervalSince1970 * 1000) - timestamp
        let maxAge: Int64 = 100 * 365 * 24 * 60 * 60 * 1000
        guard age <= maxAge else {
            throw CryptoError.dataTooOld
        }
        
        // Derive key
        let key = try deriveKey(password: password, salt: Data(salt))
        
        // Decrypt using AES-GCM
        let sealedBox = try AES.GCM.SealedBox(
            nonce: AES.GCM.Nonce(data: iv),
            ciphertext: ciphertext,
            tag: tag
        )
        
        let plaintext = try AES.GCM.open(sealedBox, using: key, authenticating: Data(timestampBytes))
        
        guard let result = String(data: plaintext, encoding: .utf8) else {
            throw CryptoError.decryptionFailed
        }
        
        return result
    }
    
    /// Derive key using Argon2id + HKDF-SHA256
    private func deriveKey(password: String, salt: Data) throws -> SymmetricKey {
        // Step 1: Argon2id
        let argon2Output = try argon2id(
            password: password,
            salt: salt,
            iterations: argon2Iterations,
            memoryKB: argon2MemoryKB,
            parallelism: argon2Parallelism,
            outputLength: keyLength
        )
        
        // Step 2: HKDF-SHA256
        let hkdfKey = SymmetricKey(data: argon2Output)
        let derivedKey = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: hkdfKey,
            salt: salt,
            info: hkdfInfo,
            outputByteCount: keyLength
        )
        
        return derivedKey
    }
    
    /// Argon2id implementation
    /// Note: iOS doesn't have native Argon2, so we use a pure Swift implementation
    private func argon2id(
        password: String,
        salt: Data,
        iterations: UInt32,
        memoryKB: UInt32,
        parallelism: UInt32,
        outputLength: Int
    ) throws -> Data {
        // Use the Argon2 Swift implementation
        let argon2 = Argon2Swift()
        return try argon2.hash(
            password: password,
            salt: salt,
            iterations: iterations,
            memoryKB: memoryKB,
            parallelism: parallelism,
            outputLength: outputLength,
            type: .id,
            version: .v13
        )
    }
}

enum CryptoError: Error, LocalizedError {
    case passwordNotSet
    case invalidFormat
    case unsupportedVersion
    case dataTooOld
    case decryptionFailed
    case argon2Failed
    
    var errorDescription: String? {
        switch self {
        case .passwordNotSet: return "Encryption password not set"
        case .invalidFormat: return "Invalid encrypted data format"
        case .unsupportedVersion: return "This note was encrypted with an old method. Please re-encrypt."
        case .dataTooOld: return "Encrypted data is too old (>100 years)"
        case .decryptionFailed: return "Decryption failed"
        case .argon2Failed: return "Key derivation failed"
        }
    }
}
