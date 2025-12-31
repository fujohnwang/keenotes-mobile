#!/bin/bash

# Android初始化调试脚本
# 用于测试和调试Android环境下的初始化问题

echo "=== Android初始化调试脚本 ==="

# 1. 编译测试
echo "Step 1: 编译测试..."
mvn clean compile -Pandroid
if [ $? -ne 0 ]; then
    echo "❌ 编译失败"
    exit 1
fi
echo "✅ 编译成功"

# 2. 打包测试
echo "Step 2: 打包测试..."
mvn package -Pandroid -DskipTests
if [ $? -ne 0 ]; then
    echo "❌ 打包失败"
    exit 1
fi
echo "✅ 打包成功"

# 3. 检查关键文件
echo "Step 3: 检查关键文件..."
echo "检查LocalCacheService..."
grep -n "resolveDbPath\|initDatabase\|StorageService" src/main/java/cn/keevol/keenotes/mobilefx/LocalCacheService.java | head -10

echo "检查ServiceManager..."
grep -n "getLocalCacheService\|localCacheInitialized" src/main/java/cn/keevol/keenotes/mobilefx/ServiceManager.java | head -5

echo "检查MainViewV2..."
grep -n "isLocalCacheReady\|loadReviewNotes" src/main/java/cn/keevol/keenotes/mobilefx/MainViewV2.java | head -5

# 4. 检查Android配置
echo "Step 4: 检查Android配置..."
echo "检查pom.xml中的Android profile..."
grep -A 20 "<id>android</id>" pom.xml

echo "检查Gluon Attach依赖..."
grep -A 5 "gluonhq.attach" pom.xml

echo "🎉 调试脚本完成！"
echo ""
echo "如果APK安装后仍显示'初始化中'，请检查以下几点："
echo "1. 确保Android设备有足够的存储空间"
echo "2. 检查应用是否有存储权限"
echo "3. 查看logcat输出中的[LocalCache]和[ServiceManager]日志"
echo "4. 尝试重启应用或清除应用数据"
echo ""
echo "logcat命令示例："
echo "adb logcat | grep -E '(LocalCache|ServiceManager|keenotes)'"