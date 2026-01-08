import Foundation

/// Swift wrapper for official Argon2 C implementation
/// Fully compatible with Bouncy Castle (Java/Android)
class Argon2Swift {
    
    enum Argon2Type {
        case d, i, id
        
        var cType: Argon2_type {
            switch self {
            case .d: return Argon2_d
            case .i: return Argon2_i
            case .id: return Argon2_id
            }
        }
    }
    
    enum Argon2Version {
        case v10, v13
        
        var cVersion: Argon2_version {
            switch self {
            case .v10: return ARGON2_VERSION_10
            case .v13: return ARGON2_VERSION_13
            }
        }
    }
    
    enum Argon2Error: Error, LocalizedError {
        case hashingFailed(String)
        
        var errorDescription: String? {
            switch self {
            case .hashingFailed(let msg):
                return "Argon2 hashing failed: \(msg)"
            }
        }
    }
    
    func hash(
        password: String,
        salt: Data,
        iterations: UInt32,
        memoryKB: UInt32,
        parallelism: UInt32,
        outputLength: Int,
        type: Argon2Type,
        version: Argon2Version
    ) throws -> Data {
        let passwordBytes = Array(password.utf8)
        let saltBytes = Array(salt)
        var output = [UInt8](repeating: 0, count: outputLength)
        
        let result = argon2_hash(
            iterations,
            memoryKB,
            parallelism,
            passwordBytes,
            passwordBytes.count,
            saltBytes,
            saltBytes.count,
            &output,
            output.count,
            nil,  // encoded output (not needed)
            0,    // encoded length
            type.cType,
            UInt32(version.cVersion.rawValue)
        )
        
        guard result == Int32(ARGON2_OK.rawValue) else {
            let errorMsg = String(cString: argon2_error_message(result))
            throw Argon2Error.hashingFailed(errorMsg)
        }
        
        return Data(output)
    }
}
