package cn.keevol.keenotes.mobilefx;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Helper class for encrypting and decrypting sensitive configuration values.
 * Uses AES-256-GCM with a key derived from user.home and a fixed salt.
 */
class CryptoHelper {
    
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128; // bits
    private static final int GCM_IV_LENGTH = 12; // bytes (96 bits, recommended for GCM)
    private static final String SALT = "NeverM1ssing@nyFleetingIdeas";
    private static final String ENC_PREFIX = "ENC:";
    
    private static SecretKey cachedKey;
    
    /**
     * Derives encryption key from user.home and fixed salt.
     */
    private static SecretKey deriveKey() {
        if (cachedKey != null) {
            return cachedKey;
        }
        
        try {
            String userHome = System.getProperty("user.home");
            String keyMaterial = userHome + SALT;
            
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = digest.digest(keyMaterial.getBytes(StandardCharsets.UTF_8));
            
            cachedKey = new SecretKeySpec(keyBytes, "AES");
            return cachedKey;
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive encryption key", e);
        }
    }
    
    /**
     * Encrypts a plaintext value.
     * Format: base64(IV + ciphertext)
     */
    static String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        
        try {
            SecretKey key = deriveKey();
            
            // Generate random IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            SecureRandom random = new SecureRandom();
            random.nextBytes(iv);
            
            // Encrypt
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, spec);
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            
            // Combine IV + ciphertext
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);
            
            return ENC_PREFIX + Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            System.err.println("[CryptoHelper] Encryption failed: " + e.getMessage());
            throw new RuntimeException("Encryption failed", e);
        }
    }
    
    /**
     * Decrypts an encrypted value.
     * Handles both encrypted (ENC:...) and plaintext values for backward compatibility.
     */
    static String decrypt(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        
        // Check if encrypted
        if (!value.startsWith(ENC_PREFIX)) {
            // Plaintext value (backward compatibility)
            return value;
        }
        
        try {
            SecretKey key = deriveKey();
            
            // Remove prefix and decode
            String encoded = value.substring(ENC_PREFIX.length());
            byte[] combined = Base64.getDecoder().decode(encoded);
            
            // Extract IV and ciphertext
            ByteBuffer buffer = ByteBuffer.wrap(combined);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            
            // Decrypt
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, spec);
            byte[] plaintext = cipher.doFinal(ciphertext);
            
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[CryptoHelper] Decryption failed: " + e.getMessage());
            // Return null to indicate decryption failure
            return null;
        }
    }
    
    /**
     * Checks if a value is encrypted.
     */
    static boolean isEncrypted(String value) {
        return value != null && value.startsWith(ENC_PREFIX);
    }
}
