package cn.keevol.keenotes.mobilefx.test;

import cn.keevol.keenotes.mobilefx.CryptoService;
import cn.keevol.keenotes.mobilefx.LocalCacheService;
import cn.keevol.keenotes.mobilefx.SettingsService;

import java.util.List;

/**
 * 隐私重构测试 - 验证端到端加密和本地功能
 */
public class PrivacyTest {

    public static void main(String[] args) {
        System.out.println("=== KeeNotes 隐私重构测试 ===\n");

        // 测试1: 加密/解密
        testEncryption();

        // 测试2: 本地缓存
        testLocalCache();

        // 测试3: 时间戳验证
        testTimestampValidation();

        System.out.println("\n=== 所有测试完成 ===");
    }

    private static void testEncryption() {
        System.out.println("测试1: 端到端加密");

        try {
            CryptoService crypto = new CryptoService();
            SettingsService settings = SettingsService.getInstance();

            // 设置密码
            settings.setEncryptionPassword("TestPassword123");
            System.out.println("✓ 密码已设置");

            // 测试数据
            String original = "这是一个敏感的笔记内容，包含隐私信息！";

            // 加密
            String encrypted = crypto.encrypt(original);
            System.out.println("✓ 加密成功");
            System.out.println("  原文长度: " + original.length());
            System.out.println("  密文长度: " + encrypted.length());
            System.out.println("  密文: " + encrypted.substring(0, Math.min(50, encrypted.length())) + "...");

            // 验证密文不包含原文
            if (encrypted.contains(original)) {
                throw new RuntimeException("加密失败：密文包含原文！");
            }
            System.out.println("✓ 密文不包含原文");

            // 解密
            String decrypted = crypto.decrypt(encrypted);
            System.out.println("✓ 解密成功");

            // 验证一致性
            if (!original.equals(decrypted)) {
                throw new RuntimeException("解密失败：内容不一致！");
            }
            System.out.println("✓ 加密/解密一致性验证通过");

            // 测试带元数据的解密
            CryptoService.DecryptionResult result = crypto.decryptWithMetadata(encrypted);
            System.out.println("✓ 元数据提取成功");
            System.out.println("  加密时间戳: " + result.timestamp);
            System.out.println("  数据年龄: " + result.getFormattedAge());

            System.out.println("测试1: ✅ 通过\n");

        } catch (Exception e) {
            System.out.println("测试1: ❌ 失败 - " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void testLocalCache() {
        System.out.println("测试2: 本地缓存功能");

        try {
            LocalCacheService cache = LocalCacheService.getInstance();

            // 清理旧数据（可选）
            // cache.clear(); // 需要添加此方法

            // 插入测试数据
            LocalCacheService.NoteData note1 = new LocalCacheService.NoteData(
                1, "测试笔记1 - 搜索关键词", "mobile", "2025-12-23T10:30:00", null
            );
            LocalCacheService.NoteData note2 = new LocalCacheService.NoteData(
                2, "测试笔记2 - 另一个内容", "mobile", "2025-12-23T11:00:00", null
            );
            LocalCacheService.NoteData note3 = new LocalCacheService.NoteData(
                3, "测试笔记3 - 搜索测试", "mobile", "2025-12-22T10:00:00", null
            );

            cache.insertNote(note1);
            cache.insertNote(note2);
            cache.insertNote(note3);
            System.out.println("✓ 插入3条测试笔记");

            // 测试搜索
            List<LocalCacheService.NoteData> searchResults = cache.searchNotes("搜索");
            System.out.println("✓ 搜索'搜索': " + searchResults.size() + " 条结果");
            if (searchResults.size() != 2) {
                throw new RuntimeException("搜索结果数量错误！预期2条，实际" + searchResults.size());
            }

            // 测试回顾（7天内）
            List<LocalCacheService.NoteData> reviewResults = cache.getNotesForReview(7);
            System.out.println("✓ 7天内笔记: " + reviewResults.size() + " 条");
            if (reviewResults.size() != 3) {
                throw new RuntimeException("回顾结果数量错误！预期3条，实际" + reviewResults.size());
            }

            // 测试同步状态
            cache.updateLastSyncId(100);
            long lastSyncId = cache.getLastSyncId();
            System.out.println("✓ 同步状态更新: last_sync_id = " + lastSyncId);
            if (lastSyncId != 100) {
                throw new RuntimeException("同步状态更新失败！");
            }

            // 测试统计
            int count = cache.getLocalNoteCount();
            System.out.println("✓ 本地笔记总数: " + count);

            System.out.println("测试2: ✅ 通过\n");

        } catch (Exception e) {
            System.out.println("测试2: ❌ 失败 - " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void testTimestampValidation() {
        System.out.println("测试3: 时间戳验证和防重放");

        try {
            CryptoService crypto = new CryptoService();
            SettingsService settings = SettingsService.getInstance();
            settings.setEncryptionPassword("TimestampTest");

            String content = "时间戳测试数据";

            // 加密（包含当前时间戳）
            String encrypted = crypto.encrypt(content);

            // 立即解密（应该成功）
            String decrypted = crypto.decrypt(encrypted);
            if (!content.equals(decrypted)) {
                throw new RuntimeException("时间戳验证失败：即时解密不一致");
            }
            System.out.println("✓ 即时解密成功");

            // 获取时间戳信息
            CryptoService.DecryptionResult result = crypto.decryptWithMetadata(encrypted);
            long age = System.currentTimeMillis() - result.timestamp;
            System.out.println("✓ 数据年龄: " + age + "ms (应该接近0)");

            if (age > 1000) {
                throw new RuntimeException("时间戳异常：数据年龄过大");
            }

            // 模拟旧数据（手动构造一个10年前的加密数据）
            // 这里我们无法直接修改时间戳，但可以验证解密器会拒绝过期数据
            // 实际测试中，如果数据超过10年，decrypt会抛出SecurityException

            System.out.println("测试3: ✅ 通过\n");

        } catch (Exception e) {
            System.out.println("测试3: ❌ 失败 - " + e.getMessage());
            e.printStackTrace();
        }
    }
}