package cn.keevol.keenotes.mobilefx;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;

/**
 * AES-GCM encryption service for E2E encryption.
 * Password is stored locally and never sent to server.
 */
public class CryptoService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128; // bits
    private static final int GCM_IV_LENGTH = 12;   // bytes
    private static final int SALT_LENGTH = 16;     // bytes
    private static final int KEY_LENGTH = 256;     // bits
    private static final int ITERATIONS = 65536;

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
     * Encrypt plaintext using AES-GCM with the stored password.
     * Format: Base64(salt + iv + ciphertext)
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

        // Derive key from password
        SecretKey key = deriveKey(password, salt);

        // Encrypt
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec);
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes("UTF-8"));

        // Combine salt + iv + ciphertext
        byte[] combined = new byte[salt.length + iv.length + ciphertext.length];
        System.arraycopy(salt, 0, combined, 0, salt.length);
        System.arraycopy(iv, 0, combined, salt.length, iv.length);
        System.arraycopy(ciphertext, 0, combined, salt.length + iv.length, ciphertext.length);

        return Base64.getEncoder().encodeToString(combined);
    }


    /**
     * Decrypt ciphertext using AES-GCM with the stored password.
     */
    public String decrypt(String encryptedBase64) throws Exception {
        String password = settings.getEncryptionPassword();
        if (password == null || password.isEmpty()) {
            throw new IllegalStateException("Encryption password not set");
        }

        byte[] combined = Base64.getDecoder().decode(encryptedBase64);

        // Extract salt, iv, ciphertext
        byte[] salt = new byte[SALT_LENGTH];
        byte[] iv = new byte[GCM_IV_LENGTH];
        byte[] ciphertext = new byte[combined.length - SALT_LENGTH - GCM_IV_LENGTH];

        System.arraycopy(combined, 0, salt, 0, SALT_LENGTH);
        System.arraycopy(combined, SALT_LENGTH, iv, 0, GCM_IV_LENGTH);
        System.arraycopy(combined, SALT_LENGTH + GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

        // Derive key from password
        SecretKey key = deriveKey(password, salt);

        // Decrypt
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec);
        byte[] plaintext = cipher.doFinal(ciphertext);

        return new String(plaintext, "UTF-8");
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
}
