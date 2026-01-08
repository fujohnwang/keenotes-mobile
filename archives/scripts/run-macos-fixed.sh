#!/bin/bash

# macOS 运行脚本 - 修复版本
# 解决 Java 版本不匹配和 JavaFX 模块问题

echo "=== KeeNotes macOS 启动脚本 ==="
echo "检查系统环境..."

# 检查 Java 版本
JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
echo "当前 Java 版本: $JAVA_VERSION"

# 检查系统架构
OS_ARCH=$(uname -m)
echo "系统架构: $OS_ARCH"

# 确定 JavaFX 平台
if [ "$OS_ARCH" = "arm64" ]; then
    JAVAFX_PLATFORM="mac-aarch64"
    echo "使用 Apple Silicon 版本的 JavaFX"
else
    JAVAFX_PLATFORM="mac"
    echo "使用 Intel 版本的 JavaFX"
fi

# 检查 JAR 文件是否存在
JAR_FILE="target/keenotes-mobile-1.0.0-SNAPSHOT.jar"
if [ ! -f "$JAR_FILE" ]; then
    echo "错误: JAR 文件不存在: $JAR_FILE"
    echo "请先运行构建命令:"
    echo "  mvn clean package -Pdesktop -DskipTests -Djavafx.platform=$JAVAFX_PLATFORM"
    exit 1
fi

echo "JAR 文件存在: $JAR_FILE"

# 构建 JavaFX 模块路径
echo "构建 JavaFX 模块路径..."

# 查找 Maven 仓库中的 JavaFX JAR 文件
M2_REPO="$HOME/.m2/repository"
JAVAFX_BASE="$M2_REPO/org/openjfx"

# 构建模块路径
MODULE_PATH=""

# 添加 JavaFX 核心模块
for module in javafx-base javafx-graphics javafx-controls javafx-fxml; do
    # 先尝试平台特定版本
    PLATFORM_JAR="$JAVAFX_BASE/$module/23.0.1/$module-23.0.1-$JAVAFX_PLATFORM.jar"
    BASE_JAR="$JAVAFX_BASE/$module/23.0.1/$module-23.0.1.jar"
    
    if [ -f "$PLATFORM_JAR" ]; then
        MODULE_PATH="$MODULE_PATH:$PLATFORM_JAR"
        echo "  添加: $module (平台特定)"
    elif [ -f "$BASE_JAR" ]; then
        MODULE_PATH="$MODULE_PATH:$BASE_JAR"
        echo "  添加: $module (通用版本)"
    else
        echo "  警告: 未找到 $module"
    fi
done

# 移除开头的冒号
MODULE_PATH="${MODULE_PATH:1}"

if [ -z "$MODULE_PATH" ]; then
    echo "错误: 未找到 JavaFX 模块"
    echo "请确保已正确构建项目:"
    echo "  mvn clean package -Pdesktop -DskipTests -Djavafx.platform=$JAVAFX_PLATFORM"
    exit 1
fi

echo "模块路径构建完成"

# 设置 JVM 参数
JVM_ARGS=""

# macOS 特定参数
JVM_ARGS="$JVM_ARGS -Xdock:name=KeeNotes"
JVM_ARGS="$JVM_ARGS -Xdock:icon=src/main/resources/icons/app-icon.png"

# JavaFX 参数
JVM_ARGS="$JVM_ARGS --module-path $MODULE_PATH"
JVM_ARGS="$JVM_ARGS --add-modules javafx.controls,javafx.fxml,javafx.base,javafx.graphics"

# 内存参数
JVM_ARGS="$JVM_ARGS -Xmx512m"

# 调试参数（可选）
if [ "$1" = "--debug" ]; then
    JVM_ARGS="$JVM_ARGS -Djavafx.verbose=true"
    JVM_ARGS="$JVM_ARGS -Djava.util.logging.level=FINE"
    echo "启用调试模式"
fi

echo "启动应用程序..."
echo "JVM 参数: $JVM_ARGS"

# 启动应用
java $JVM_ARGS \
     -cp "$JAR_FILE" \
     cn.keevol.keenotes.mobilefx.Main

echo "应用程序已退出"