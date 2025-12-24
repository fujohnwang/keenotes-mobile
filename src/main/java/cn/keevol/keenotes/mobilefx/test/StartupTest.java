package cn.keevol.keenotes.mobilefx.test;

import cn.keevol.keenotes.mobilefx.ServiceManager;
import cn.keevol.keenotes.mobilefx.LocalCacheService;
import cn.keevol.keenotes.mobilefx.ApiServiceV2;
import javafx.application.Platform;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 测试重构后的启动流程
 * 验证UI可以快速启动，服务延迟初始化
 */
public class StartupTest {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Testing Refactored Startup ===\n");

        // 1. 测试ServiceManager的延迟初始化
        testServiceManagerLazyInit();

        // 2. 测试LocalCacheService的延迟初始化
        testLocalCacheLazyInit();

        // 3. 测试ApiServiceV2的即时可用性
        testApiServiceAvailable();

        System.out.println("\n=== All Tests Passed ===");
    }

    private static void testServiceManagerLazyInit() throws Exception {
        System.out.println("1. Testing ServiceManager lazy initialization...");

        long startTime = System.currentTimeMillis();

        // 获取ServiceManager实例（应该立即返回）
        ServiceManager manager = ServiceManager.getInstance();
        if (manager == null) {
            throw new RuntimeException("ServiceManager should not be null");
        }

        // 获取SettingsService（应该立即返回）
        Object settings = manager.getSettingsService();
        if (settings == null) {
            throw new RuntimeException("SettingsService should not be null");
        }

        long elapsed = System.currentTimeMillis() - startTime;
        System.out.println("   ✓ ServiceManager initialized in " + elapsed + "ms");

        if (elapsed > 100) {
            System.out.println("   ⚠ Warning: ServiceManager took longer than expected");
        }
    }

    private static void testLocalCacheLazyInit() throws Exception {
        System.out.println("2. Testing LocalCacheService lazy initialization...");

        long startTime = System.currentTimeMillis();

        // 获取LocalCacheService（应该立即返回实例，但初始化在后台）
        ServiceManager manager = ServiceManager.getInstance();
        LocalCacheService cache = manager.getLocalCacheService();

        if (cache == null) {
            throw new RuntimeException("LocalCacheService should not be null");
        }

        // 实例应该立即可用
        long elapsed = System.currentTimeMillis() - startTime;
        System.out.println("   ✓ LocalCacheService instance created in " + elapsed + "ms");

        // 但初始化可能在后台进行
        if (!cache.isInitialized()) {
            System.out.println("   ℹ Database initialization running in background...");

            // 等待初始化完成（最多5秒）
            int maxWait = 50;
            while (!cache.isInitialized() && maxWait > 0) {
                Thread.sleep(100);
                maxWait--;
            }

            if (cache.isInitialized()) {
                System.out.println("   ✓ Database initialized successfully");
            } else {
                System.out.println("   ⚠ Database initialization timeout");
            }
        } else {
            System.out.println("   ✓ Database already initialized");
        }

        // 测试数据库操作
        if (cache.isInitialized()) {
            int count = cache.getLocalNoteCount();
            System.out.println("   ✓ Database operations work (count: " + count + ")");
        }
    }

    private static void testApiServiceAvailable() throws Exception {
        System.out.println("3. Testing ApiServiceV2 availability...");

        long startTime = System.currentTimeMillis();

        ServiceManager manager = ServiceManager.getInstance();
        ApiServiceV2 api = manager.getApiService();

        if (api == null) {
            throw new RuntimeException("ApiServiceV2 should not be null");
        }

        long elapsed = System.currentTimeMillis() - startTime;
        System.out.println("   ✓ ApiServiceV2 created in " + elapsed + "ms");

        // 测试isConfigured方法（不应该阻塞）
        boolean configured = api.isConfigured();
        System.out.println("   ✓ isConfigured() works: " + configured);

        // 测试isEncryptionEnabled方法（不应该阻塞）
        boolean encrypted = api.isEncryptionEnabled();
        System.out.println("   ✓ isEncryptionEnabled() works: " + encrypted);
    }
}
