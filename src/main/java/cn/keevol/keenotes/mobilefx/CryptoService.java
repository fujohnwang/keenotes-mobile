package cn.keevol.keenotes.mobilefx;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.digests.SHA256Digest;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 增强的加密服务 - 使用Argon2 + HKDF派生加密密钥
 * 
 * 密钥派生流程：
 * 1. 用户密码通过Argon2id生成中间密钥材料（32字节）
 * 2. 使用HKDF-SHA256从Argon2输出派生最终AES密钥
 * 3. 使用AES-256-GCM进行加密
 * 
 * 安全参数：
 * - Argon2id: 64MB内存, 3次迭代, 并行度1
 * - HKDF: SHA-256
 * - AES-256-GCM: 128位认证标签, 12字节IV
 */
public class CryptoService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128; // bits
    private static final int GCM_IV_LENGTH = 12; // bytes
    private static final int SALT_LENGTH = 16; // bytes
    private static final int KEY_LENGTH = 32; // bytes (256 bits)
    private static final int TIMESTAMP_LENGTH = 8; // bytes (long)

    // Argon2参数
    private static final int ARGON2_ITERATIONS = 3;
    private static final int ARGON2_MEMORY_KB = 65536; // 64MB
    private static final int ARGON2_PARALLELISM = 1;

    // HKDF info参数（用于派生特定用途的密钥）
    private static final byte[] HKDF_INFO = "KeeNotes-E2E-Encryption-v2".getBytes(StandardCharsets.UTF_8);

    private final SettingsService settings;

    public CryptoService() {
        this.settings = SettingsService.getInstance();
    }

    /**
     * 检查加密是否已启用
     */
    public boolean isEncryptionEnabled() {
        String password = settings.getEncryptionPassword();
        return password != null && !password.isEmpty();
    }

    /**
     * 使用Argon2+HKDF+AES-GCM加密
     * Format: Base64(version + salt + iv + timestamp + ciphertext + tag)
     * version: 1字节，标识加密方案版本（0x02表示Argon2+HKDF）
     */
    public String encrypt(String plaintext) throws Exception {
        String password = settings.getEncryptionPassword();
        if (password == null || password.isEmpty()) {
            throw new IllegalStateException("Encryption password not set");
        }

        // 生成随机盐和IV
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[SALT_LENGTH];
        byte[] iv = new byte[GCM_IV_LENGTH];
        random.nextBytes(salt);
        random.nextBytes(iv);

        // 获取当前时间戳作为AAD
        long timestamp = System.currentTimeMillis();
        byte[] timestampBytes = ByteBuffer.allocate(TIMESTAMP_LENGTH).putLong(timestamp).array();

        // 派生加密密钥
        SecretKey key = deriveKeyArgon2HKDF(password, salt);

        // AES-GCM加密
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec);
        cipher.updateAAD(timestampBytes);
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        // 组合：version(1) + salt(16) + iv(12) + timestamp(8) + ciphertext
        byte[] combined = new byte[1 + salt.length + iv.length + TIMESTAMP_LENGTH + ciphertext.length];
        int pos = 0;
        combined[pos++] = 0x02; // 版本标识：Argon2+HKDF
        System.arraycopy(salt, 0, combined, pos, salt.length);
        pos += salt.length;
        System.arraycopy(iv, 0, combined, pos, iv.length);
        pos += iv.length;
        System.arraycopy(timestampBytes, 0, combined, pos, TIMESTAMP_LENGTH);
        pos += TIMESTAMP_LENGTH;
        System.arraycopy(ciphertext, 0, combined, pos, ciphertext.length);

        return Base64.getEncoder().encodeToString(combined);
    }

    /**
     * 使用Argon2+HKDF+AES-GCM解密
     * 支持检测旧格式（PBKDF2），但抛出异常提示需要重新加密
     */
    public String decrypt(String encryptedBase64) throws Exception {
        String password = settings.getEncryptionPassword();
        if (password == null || password.isEmpty()) {
            throw new IllegalStateException("Encryption password not set");
        }

        byte[] combined = Base64.getDecoder().decode(encryptedBase64);

        // 检查最小长度
        if (combined.length < 1 + SALT_LENGTH + GCM_IV_LENGTH + TIMESTAMP_LENGTH) {
            throw new IllegalArgumentException("Invalid encrypted data format");
        }

        // 检查版本标识
        byte version = combined[0];

        if (version == 0x02) {
            // 新格式：Argon2+HKDF
            return decryptV2(combined, password);
        } else {
            // 旧格式（PBKDF2）或无版本标识
            // 尝试旧格式解密
            throw new UnsupportedOperationException(
                    "This note was encrypted with the old method (PBKDF2). " +
                            "Please re-encrypt your notes with the new Argon2+HKDF method for enhanced security.");
        }
    }

    /**
     * 解密V2格式（Argon2+HKDF）
     */
    private String decryptV2(byte[] combined, String password) throws Exception {
        int pos = 1; // 跳过版本字节

        byte[] salt = new byte[SALT_LENGTH];
        byte[] iv = new byte[GCM_IV_LENGTH];
        byte[] timestampBytes = new byte[TIMESTAMP_LENGTH];
        byte[] ciphertext = new byte[combined.length - 1 - SALT_LENGTH - GCM_IV_LENGTH - TIMESTAMP_LENGTH];

        System.arraycopy(combined, pos, salt, 0, SALT_LENGTH);
        pos += SALT_LENGTH;
        System.arraycopy(combined, pos, iv, 0, GCM_IV_LENGTH);
        pos += GCM_IV_LENGTH;
        System.arraycopy(combined, pos, timestampBytes, 0, TIMESTAMP_LENGTH);
        pos += TIMESTAMP_LENGTH;
        System.arraycopy(combined, pos, ciphertext, 0, ciphertext.length);

        // 验证时间戳（防止过旧数据）
        long timestamp = ByteBuffer.wrap(timestampBytes).getLong();
        long age = System.currentTimeMillis() - timestamp;
        if (age > 100L * 365 * 24 * 60 * 60 * 1000) {
            throw new SecurityException("Encrypted data is too old (>100 years), possible replay attack");
        }

        // 派生加密密钥
        SecretKey key = deriveKeyArgon2HKDF(password, salt);

        // AES-GCM解密
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec);
        cipher.updateAAD(timestampBytes);
        byte[] plaintext = cipher.doFinal(ciphertext);

        return new String(plaintext, StandardCharsets.UTF_8);
    }

    /**
     * 使用Argon2id + HKDF派生AES密钥
     */
    private SecretKey deriveKeyArgon2HKDF(String password, byte[] salt) {
        // Step 1: Argon2id
        Argon2Parameters.Builder builder = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(ARGON2_ITERATIONS)
                .withMemoryAsKB(ARGON2_MEMORY_KB)
                .withParallelism(ARGON2_PARALLELISM)
                .withSalt(salt);

        Argon2BytesGenerator argon2 = new Argon2BytesGenerator();
        argon2.init(builder.build());

        byte[] argon2Output = new byte[KEY_LENGTH];
        argon2.generateBytes(password.toCharArray(), argon2Output);

        // Step 2: HKDF-SHA256
        HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
        HKDFParameters hkdfParams = new HKDFParameters(argon2Output, salt, HKDF_INFO);
        hkdf.init(hkdfParams);

        byte[] derivedKey = new byte[KEY_LENGTH];
        hkdf.generateBytes(derivedKey, 0, KEY_LENGTH);

        return new SecretKeySpec(derivedKey, "AES");
    }

    /**
     * 解密并返回元数据
     */
    public DecryptionResult decryptWithMetadata(String encryptedBase64) throws Exception {
        String password = settings.getEncryptionPassword();
        if (password == null || password.isEmpty()) {
            throw new IllegalStateException("Encryption password not set");
        }

        byte[] combined = Base64.getDecoder().decode(encryptedBase64);

        if (combined.length < 1 + SALT_LENGTH + GCM_IV_LENGTH + TIMESTAMP_LENGTH) {
            throw new IllegalArgumentException("Invalid encrypted data format");
        }

        byte version = combined[0];
        if (version != 0x02) {
            throw new UnsupportedOperationException("Unsupported encryption version");
        }

        int pos = 1;
        byte[] salt = new byte[SALT_LENGTH];
        byte[] iv = new byte[GCM_IV_LENGTH];
        byte[] timestampBytes = new byte[TIMESTAMP_LENGTH];
        byte[] ciphertext = new byte[combined.length - 1 - SALT_LENGTH - GCM_IV_LENGTH - TIMESTAMP_LENGTH];

        System.arraycopy(combined, pos, salt, 0, SALT_LENGTH);
        pos += SALT_LENGTH;
        System.arraycopy(combined, pos, iv, 0, GCM_IV_LENGTH);
        pos += GCM_IV_LENGTH;
        System.arraycopy(combined, pos, timestampBytes, 0, TIMESTAMP_LENGTH);
        pos += TIMESTAMP_LENGTH;
        System.arraycopy(combined, pos, ciphertext, 0, ciphertext.length);

        long timestamp = ByteBuffer.wrap(timestampBytes).getLong();

        SecretKey key = deriveKeyArgon2HKDF(password, salt);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec);
        cipher.updateAAD(timestampBytes);
        byte[] plaintext = cipher.doFinal(ciphertext);

        return new DecryptionResult(new String(plaintext, StandardCharsets.UTF_8), timestamp);
    }

    /**
     * 解密结果包含元数据
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
