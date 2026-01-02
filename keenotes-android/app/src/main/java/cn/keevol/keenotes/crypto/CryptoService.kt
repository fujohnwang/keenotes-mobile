package cn.keevol.keenotes.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.digests.SHA256Digest
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Crypto service using Argon2 + HKDF + AES-GCM
 * Compatible with JavaFX version
 */
class CryptoService(private val getPassword: () -> String?) {
    
    companion object {
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128 // bits
        private const val GCM_IV_LENGTH = 12 // bytes
        private const val SALT_LENGTH = 16 // bytes
        private const val KEY_LENGTH = 32 // bytes (256 bits)
        private const val TIMESTAMP_LENGTH = 8 // bytes (long)
        
        // Argon2 parameters
        private const val ARGON2_ITERATIONS = 3
        private const val ARGON2_MEMORY_KB = 65536 // 64MB
        private const val ARGON2_PARALLELISM = 1
        
        // HKDF info
        private val HKDF_INFO = "KeeNotes-E2E-Encryption-v2".toByteArray(StandardCharsets.UTF_8)
    }
    
    fun isEncryptionEnabled(): Boolean {
        val password = getPassword()
        return !password.isNullOrEmpty()
    }
    
    /**
     * Encrypt plaintext using Argon2+HKDF+AES-GCM
     * Format: Base64(version + salt + iv + timestamp + ciphertext + tag)
     */
    fun encrypt(plaintext: String): String {
        val password = getPassword()
            ?: throw IllegalStateException("Encryption password not set")
        
        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH).also { random.nextBytes(it) }
        val iv = ByteArray(GCM_IV_LENGTH).also { random.nextBytes(it) }
        
        val timestamp = System.currentTimeMillis()
        val timestampBytes = ByteBuffer.allocate(TIMESTAMP_LENGTH).putLong(timestamp).array()
        
        val key = deriveKeyArgon2HKDF(password, salt)
        
        val cipher = Cipher.getInstance(ALGORITHM)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec)
        cipher.updateAAD(timestampBytes)
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        
        // Combine: version(1) + salt(16) + iv(12) + timestamp(8) + ciphertext
        val combined = ByteArray(1 + salt.size + iv.size + TIMESTAMP_LENGTH + ciphertext.size)
        var pos = 0
        combined[pos++] = 0x02 // Version: Argon2+HKDF
        System.arraycopy(salt, 0, combined, pos, salt.size)
        pos += salt.size
        System.arraycopy(iv, 0, combined, pos, iv.size)
        pos += iv.size
        System.arraycopy(timestampBytes, 0, combined, pos, TIMESTAMP_LENGTH)
        pos += TIMESTAMP_LENGTH
        System.arraycopy(ciphertext, 0, combined, pos, ciphertext.size)
        
        return Base64.getEncoder().encodeToString(combined)
    }
    
    /**
     * Decrypt ciphertext
     */
    fun decrypt(encryptedBase64: String): String {
        val password = getPassword()
            ?: throw IllegalStateException("Encryption password not set")
        
        return decryptWithPassword(encryptedBase64, password)
    }
    
    /**
     * Decrypt ciphertext with explicit password (avoids callback)
     * Use this when you already have the password cached
     */
    fun decryptWithPassword(encryptedBase64: String, password: String): String {
        val combined = Base64.getDecoder().decode(encryptedBase64)
        
        if (combined.size < 1 + SALT_LENGTH + GCM_IV_LENGTH + TIMESTAMP_LENGTH) {
            throw IllegalArgumentException("Invalid encrypted data format")
        }
        
        val version = combined[0]
        if (version != 0x02.toByte()) {
            throw UnsupportedOperationException(
                "This note was encrypted with an old method. Please re-encrypt."
            )
        }
        
        return decryptV2(combined, password)
    }
    
    private fun decryptV2(combined: ByteArray, password: String): String {
        var pos = 1 // Skip version byte
        
        val salt = ByteArray(SALT_LENGTH)
        val iv = ByteArray(GCM_IV_LENGTH)
        val timestampBytes = ByteArray(TIMESTAMP_LENGTH)
        val ciphertext = ByteArray(combined.size - 1 - SALT_LENGTH - GCM_IV_LENGTH - TIMESTAMP_LENGTH)
        
        System.arraycopy(combined, pos, salt, 0, SALT_LENGTH)
        pos += SALT_LENGTH
        System.arraycopy(combined, pos, iv, 0, GCM_IV_LENGTH)
        pos += GCM_IV_LENGTH
        System.arraycopy(combined, pos, timestampBytes, 0, TIMESTAMP_LENGTH)
        pos += TIMESTAMP_LENGTH
        System.arraycopy(combined, pos, ciphertext, 0, ciphertext.size)
        
        val timestamp = ByteBuffer.wrap(timestampBytes).long
        val age = System.currentTimeMillis() - timestamp
        if (age > 10L * 365 * 24 * 60 * 60 * 1000) {
            throw SecurityException("Encrypted data is too old (>10 years)")
        }
        
        val key = deriveKeyArgon2HKDF(password, salt)
        
        val cipher = Cipher.getInstance(ALGORITHM)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)
        cipher.updateAAD(timestampBytes)
        val plaintext = cipher.doFinal(ciphertext)
        
        return String(plaintext, StandardCharsets.UTF_8)
    }
    
    private fun deriveKeyArgon2HKDF(password: String, salt: ByteArray): SecretKey {
        // Step 1: Argon2id
        val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(ARGON2_ITERATIONS)
            .withMemoryAsKB(ARGON2_MEMORY_KB)
            .withParallelism(ARGON2_PARALLELISM)
            .withSalt(salt)
        
        val argon2 = Argon2BytesGenerator()
        argon2.init(builder.build())
        
        val argon2Output = ByteArray(KEY_LENGTH)
        argon2.generateBytes(password.toCharArray(), argon2Output)
        
        // Step 2: HKDF-SHA256
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        val hkdfParams = HKDFParameters(argon2Output, salt, HKDF_INFO)
        hkdf.init(hkdfParams)
        
        val derivedKey = ByteArray(KEY_LENGTH)
        hkdf.generateBytes(derivedKey, 0, KEY_LENGTH)
        
        return SecretKeySpec(derivedKey, "AES")
    }
}
