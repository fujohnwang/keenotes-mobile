package cn.keevol.keenotes.mobilefx;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;

/**
 * AES-GCM encryption service for E2E encryption.
 * Password is stored locally and never sent to server.
 * Enhanced with timestamp-based AEAD for additional integrity verification.
 */
public class CryptoService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128; // bits
    private static final int GCM_IV_LENGTH = 12;   // bytes
    private static final int SALT_LENGTH = 16;     // bytes
    private static final int KEY_LENGTH = 256;     // bits
    private static final int ITERATIONS = 65536;
    private static final int TIMESTAMP_LENGTH = 8; // bytes (long)

    private final SettingsService settings;

    public CryptoService() {
        this.settings = SettingsService.getInstance();
    }

    /**
     * Check if encryption is enabled (password is set).
     */
    public boolean isEncryptionEnabled() {
        String password = settings.getEncryptionPassword();
        return password != null && !password.isEmpty();
    }

    /**
     * Encrypt plaintext using AES-GCM with timestamp as AAD.
     * Format: Base64(salt + iv + timestamp + ciphertext + tag)
     *
     * @param plaintext The text to encrypt
     * @return Base64 encoded encrypted data
     * @throws Exception if encryption fails
     */
    public String encrypt(String plaintext) throws Exception {
        String password = settings.getEncryptionPassword();
        if (password == null || password.isEmpty()) {
            throw new IllegalStateException("Encryption password not set");
        }

        // Generate random salt and IV
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[SALT_LENGTH];
        byte[] iv = new byte[GCM_IV_LENGTH];
        random.nextBytes(salt);
        random.nextBytes(iv);

        // Get current timestamp as additional authenticated data
        long timestamp = System.currentTimeMillis();
        byte[] timestampBytes = ByteBuffer.allocate(TIMESTAMP_LENGTH).putLong(timestamp).array();

        // Derive key from password
        SecretKey key = deriveKey(password, salt);

        // Encrypt with timestamp as AAD
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec);
        cipher.updateAAD(timestampBytes);  // Add timestamp as authenticated data
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes("UTF-8"));

        // Combine salt + iv + timestamp + ciphertext
        byte[] combined = new byte[salt.length + iv.length + TIMESTAMP_LENGTH + ciphertext.length];
        System.arraycopy(salt, 0, combined, 0, salt.length);
        System.arraycopy(iv, 0, combined, salt.length, iv.length);
        System.arraycopy(timestampBytes, 0, combined, salt.length + iv.length, TIMESTAMP_LENGTH);
        System.arraycopy(ciphertext, 0, combined, salt.length + iv.length + TIMESTAMP_LENGTH, ciphertext.length);

        return Base64.getEncoder().encodeToString(combined);
    }

    /**
     * Decrypt ciphertext using AES-GCM with timestamp verification.
     * Also returns the timestamp for potential validation.
     *
     * @param encryptedBase64 Base64 encoded encrypted data
     * @return Decrypted plaintext
     * @throws Exception if decryption fails or timestamp is invalid
     */
    public String decrypt(String encryptedBase64) throws Exception {
        String password = settings.getEncryptionPassword();
        if (password == null || password.isEmpty()) {
            throw new IllegalStateException("Encryption password not set");
        }

        byte[] combined = Base64.getDecoder().decode(encryptedBase64);

        // Extract components
        if (combined.length < SALT_LENGTH + GCM_IV_LENGTH + TIMESTAMP_LENGTH) {
            throw new IllegalArgumentException("Invalid encrypted data format");
        }

        byte[] salt = new byte[SALT_LENGTH];
        byte[] iv = new byte[GCM_IV_LENGTH];
        byte[] timestampBytes = new byte[TIMESTAMP_LENGTH];
        byte[] ciphertext = new byte[combined.length - SALT_LENGTH - GCM_IV_LENGTH - TIMESTAMP_LENGTH];

        System.arraycopy(combined, 0, salt, 0, SALT_LENGTH);
        System.arraycopy(combined, SALT_LENGTH, iv, 0, GCM_IV_LENGTH);
        System.arraycopy(combined, SALT_LENGTH + GCM_IV_LENGTH, timestampBytes, 0, TIMESTAMP_LENGTH);
        System.arraycopy(combined, SALT_LENGTH + GCM_IV_LENGTH + TIMESTAMP_LENGTH, ciphertext, 0, ciphertext.length);

        // Extract timestamp for potential validation
        long timestamp = ByteBuffer.wrap(timestampBytes).getLong();
        long age = System.currentTimeMillis() - timestamp;

        // Optional: Validate timestamp (reject data older than 10 years)
        if (age > 10L * 365 * 24 * 60 * 60 * 1000) {
            throw new SecurityException("Encrypted data is too old, possible replay attack");
        }

        // Derive key from password
        SecretKey key = deriveKey(password, salt);

        // Decrypt with timestamp verification
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec);
        cipher.updateAAD(timestampBytes);  // Verify timestamp as authenticated data
        byte[] plaintext = cipher.doFinal(ciphertext);

        return new String(plaintext, "UTF-8");
    }

    /**
     * Decrypt and return timestamp information.
     * Useful for debugging and validation.
     */
    public DecryptionResult decryptWithMetadata(String encryptedBase64) throws Exception {
        String password = settings.getEncryptionPassword();
        if (password == null || password.isEmpty()) {
            throw new IllegalStateException("Encryption password not set");
        }

        byte[] combined = Base64.getDecoder().decode(encryptedBase64);

        if (combined.length < SALT_LENGTH + GCM_IV_LENGTH + TIMESTAMP_LENGTH) {
            throw new IllegalArgumentException("Invalid encrypted data format");
        }

        byte[] salt = new byte[SALT_LENGTH];
        byte[] iv = new byte[GCM_IV_LENGTH];
        byte[] timestampBytes = new byte[TIMESTAMP_LENGTH];
        byte[] ciphertext = new byte[combined.length - SALT_LENGTH - GCM_IV_LENGTH - TIMESTAMP_LENGTH];

        System.arraycopy(combined, 0, salt, 0, SALT_LENGTH);
        System.arraycopy(combined, SALT_LENGTH, iv, 0, GCM_IV_LENGTH);
        System.arraycopy(combined, SALT_LENGTH + GCM_IV_LENGTH, timestampBytes, 0, TIMESTAMP_LENGTH);
        System.arraycopy(combined, SALT_LENGTH + GCM_IV_LENGTH + TIMESTAMP_LENGTH, ciphertext, 0, ciphertext.length);

        long timestamp = ByteBuffer.wrap(timestampBytes).getLong();

        SecretKey key = deriveKey(password, salt);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec);
        cipher.updateAAD(timestampBytes);
        byte[] plaintext = cipher.doFinal(ciphertext);

        return new DecryptionResult(new String(plaintext, "UTF-8"), timestamp);
    }

    /**
     * Derive AES key from password using PBKDF2.
     */
    private SecretKey deriveKey(String password, byte[] salt) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH);
        SecretKey tmp = factory.generateSecret(spec);
        return new SecretKeySpec(tmp.getEncoded(), "AES");
    }

    /**
     * Result of decryption with metadata.
     */
    public static class DecryptionResult {
        public final String plaintext;
        public final long timestamp;

        public DecryptionResult(String plaintext, long timestamp) {
            this.plaintext = plaintext;
            this.timestamp = timestamp;
        }

        public String getFormattedAge() {
            long ageMs = System.currentTimeMillis() - timestamp;
            long ageSeconds = ageMs / 1000;
            if (ageSeconds < 60) {
                return ageSeconds + "s ago";
            } else if (ageSeconds < 3600) {
                return (ageSeconds / 60) + "m ago";
            } else if (ageSeconds < 86400) {
                return (ageSeconds / 3600) + "h ago";
            } else {
                return (ageSeconds / 86400) + "d ago";
            }
        }
    }
}
