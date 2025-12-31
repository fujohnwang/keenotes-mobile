# macOS 运行时问题修复方案

## 问题分析

从 Console.app 的错误信息和代码分析，发现以下问题：

1. **Java 版本不匹配**：本地使用 Java 25，项目配置 Java 21
2. **JavaFX 模块路径问题**：运行时缺少正确的 JavaFX 模块
3. **Maven 配置与运行时不一致**：构建时使用的 JavaFX 版本与运行时不匹配

## 修复步骤

### 1. 安装正确的 Java 版本

```bash
# 安装 Java 21 (Temurin)
brew install --cask temurin21

# 设置 JAVA_HOME
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
```

### 2. 修复运行脚本

创建新的运行脚本，确保 JavaFX 模块正确加载。

### 3. 验证构建

使用正确的 Java 版本重新构建项目。

## 立即修复方案

如果不想更换 Java 版本，可以修改项目配置支持 Java 25。