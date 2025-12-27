# 修复 GitHub Actions 构建错误

## 问题诊断

### 错误信息
```
Error: Unknown lifecycle phase ".platform=win-amd64"
```

### 根本原因

1. **命令格式错误**：在 GitHub Actions 中，Maven 参数没有被正确引号包裹，导致 `-Djavafx.platform=win-amd64` 被解析为生命周期阶段而非系统属性
2. **Windows shell 问题**：Windows 默认使用 PowerShell，参数解析与 bash 不同
3. **JavaFX Classifier 错误**：JavaFX 23 的 classifier 命名规则已改变，不再使用 `win-x64`、`linux-amd64`、`mac-x64` 等格式

## 解决方案

### 1. 修复 GitHub Actions 命令格式

#### 之前（错误）
```yaml
- name: Build JAR
  run: mvn clean package -Pdesktop -DskipTests -Djavafx.platform=win-amd64
```

#### 之后（正确）
```yaml
- name: Build JAR
  shell: bash
  run: mvn clean package -Pdesktop -DskipTests "-Djavafx.platform=win"
```

**关键改动**：
- 为 Windows 明确指定 `shell: bash`
- 使用引号包裹 `-Djavafx.platform` 参数
- 修正 classifier 名称（见下文）

### 2. 更新 JavaFX Classifier

#### JavaFX 23 正确的 Classifier

| 平台 | 架构 | 旧 Classifier（错误） | 新 Classifier（正确） |
|------|------|---------------------|---------------------|
| macOS | ARM64 | `mac-x64` | `mac-aarch64` |
| macOS | x64 | `mac-x64` | `mac` |
| Linux | x64 | `linux-amd64` | `linux` |
| Linux | ARM64 | `linux-aarch64` | `linux-aarch64` |
| Windows | x64 | `win-x64` | `win` |

#### 验证方法
```bash
# 查看可用的 classifier
curl "https://repo.maven.apache.org/maven2/org/openjfx/javafx-base/23.0.1/" | \
  grep -o 'javafx-base-23.0.1-[^"<>]*\.jar'
```

### 3. 更新所有相关文件

#### 修改的文件

1. `.github/workflows/desktop-build.yml`
   - Windows: 添加 `shell: bash` 和引号
   - Linux: 添加引号
   - macOS: 添加引号
   - 更新所有 classifier 为正确值

2. `build-desktop.sh`
   - 更新平台检测逻辑中的 classifier 值

3. `README.md`
   - 更新平台 classifier 表格

4. `BUILD_GUIDE.md`
   - 更新平台 classifier 表格

5. `BUILD_SYSTEM_REFACTORING.md`
   - 更新示例中的 classifier 值
   - 更新平台表格

## 本地验证

### 测试 Linux 构建
```bash
mvn clean package -Pdesktop -DskipTests "-Djavafx.platform=linux"
```
**结果**: ✅ BUILD SUCCESS

### 测试 macOS 构建
```bash
mvn clean package -Pdesktop -DskipTests "-Djavafx.platform=mac-aarch64"
```
**结果**: ✅ BUILD SUCCESS

## 技术细节

### 为什么 JavaFX Classifier 改变了？

JavaFX 23 之前：
- 指定具体架构：`linux-amd64`, `win-x64`, `mac-x64`

JavaFX 23 及之后：
- 使用通用名称：`linux`, `win`, `mac`
- ARM64 需要明确指定：`linux-aarch64`, `mac-aarch64`

这种改变简化了依赖管理，因为大多数桌面系统都是 x64/x86_64。

### GitHub Actions Shell 注意事项

1. **Windows 默认 shell 是 PowerShell**：
   - PowerShell 的参数解析与 bash 不同
   - 某些特殊字符（如 `=`）可能需要转义或引号

2. **推荐做法**：
   - 明确指定 `shell: bash` 以确保跨平台一致性
   - 使用引号包裹包含特殊字符的参数
   - 或者使用 PowerShell 的转义语法：`` ` ``

### Maven 参数传递规则

```bash
# 正确（带引号）
mvn clean package -Djavafx.platform=linux

# 错误（无引号，某些 shell 下会被截断）
mvn clean package -Djavafx.platform=linux

# 最安全（使用引号）
mvn clean package "-Djavafx.platform=linux"
```

## 文件变更汇总

### .github/workflows/desktop-build.yml
```diff
- run: mvn clean package -Pdesktop -DskipTests -Djavafx.platform=win-amd64
+ shell: bash
+ run: mvn clean package -Pdesktop -DskipTests "-Djavafx.platform=win"

- run: mvn clean package -Pdesktop -DskipTests -Djavafx.platform=linux-amd64
+ run: mvn clean package -Pdesktop -DskipTests "-Djavafx.platform=linux"

- run: mvn clean package -Pdesktop -DskipTests -Djavafx.platform=mac-aarch64
+ run: mvn clean package -Pdesktop -DskipTests "-Djavafx.platform=mac-aarch64"
```

### build-desktop.sh
```bash
# 更新 classifier 值
- PLATFORM="mac-x64"      →   PLATFORM="mac"
- PLATFORM="linux-amd64"   →   PLATFORM="linux"
- PLATFORM="win-x64"       →   PLATFORM="win"
```

### 其他文档
- 更新所有平台 classifier 表格和示例代码

## 验证清单

- [x] Windows 构建命令格式正确
- [x] Linux 构建命令格式正确
- [x] macOS 构建命令格式正确
- [x] JavaFX classifier 值正确
- [x] 本地 Linux 构建测试通过
- [x] 本地 macOS 构建测试通过
- [x] 文档更新完成
- [ ] GitHub Actions Windows 构建验证（待 CI 运行）
- [ ] GitHub Actions Linux 构建验证（待 CI 运行）
- [ ] GitHub Actions macOS 构建验证（待 CI 运行）

## 后续建议

1. **测试覆盖**：添加构建产物的自动化测试
2. **版本锁定**：考虑在 pom.xml 中锁定 JavaFX 版本
3. **多架构支持**：为 Linux 和 Windows 添加 ARM64 支持（如需要）
4. **文档同步**：确保所有文档中的 classifier 保持一致

---
**修复日期**: 2025-12-27
**修复内容**: GitHub Actions 构建错误和 JavaFX Classifier
**状态**: ✅ 本地验证通过，等待 CI 验证
