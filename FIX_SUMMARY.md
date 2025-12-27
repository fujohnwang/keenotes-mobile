# GitHub Actions 构建错误修复总结

## 问题描述

GitHub Actions 在构建 Windows 和 Linux 平台时报错：
```
Error: Unknown lifecycle phase ".platform=win-amd64"
```

## 根本原因

1. **Maven 参数格式问题**：缺少引号导致参数被错误解析
2. **Shell 兼容性问题**：Windows 默认使用 PowerShell，参数处理不同
3. **JavaFX Classifier 命名规则变更**：JavaFX 23 使用不同的 classifier 格式

## 解决方案

### 1. 修复命令格式

**Windows 构建**：
```yaml
# 之前
run: mvn clean package -Pdesktop -DskipTests -Djavafx.platform=win-amd64

# 之后
shell: bash
run: mvn clean package -Pdesktop -DskipTests "-Djavafx.platform=win"
```

**Linux 构建**：
```yaml
# 之前
run: mvn clean package -Pdesktop -DskipTests -Djavafx.platform=linux-amd64

# 之后
run: mvn clean package -Pdesktop -DskipTests "-Djavafx.platform=linux"
```

**macOS 构建**：
```yaml
# 之前
run: mvn clean package -Pdesktop -DskipTests -Djavafx.platform=mac-aarch64

# 之后
run: mvn clean package -Pdesktop -DskipTests "-Djavafx.platform=mac-aarch64"
```

### 2. 更新 JavaFX Classifier

JavaFX 23 正确的 classifier 值：

| 平台 | 旧值（错误） | 新值（正确） |
|------|-------------|-------------|
| macOS Intel | `mac-x64` | `mac` |
| macOS ARM64 | `mac-aarch64` | `mac-aarch64` |
| Linux x64 | `linux-amd64` | `linux` |
| Linux ARM64 | `linux-aarch64` | `linux-aarch64` |
| Windows x64 | `win-x64` | `win` |

### 3. 更新的文件

#### 配置文件
- `.github/workflows/desktop-build.yml` - 修复构建命令
- `build-desktop.sh` - 更新平台检测逻辑

#### 文档文件
- `README.md` - 更新平台说明
- `BUILD_GUIDE.md` - 更新 classifier 表格
- `BUILD_SYSTEM_REFACTORING.md` - 更新示例代码

#### 新增文件
- `GITHUB_ACTIONS_FIX.md` - 详细修复说明

## 验证结果

### 本地测试

```bash
# Linux 构建
mvn clean package -Pdesktop -DskipTests "-Djavafx.platform=linux"
# ✅ BUILD SUCCESS

# macOS 构建
mvn clean package -Pdesktop -DskipTests "-Djavafx.platform=mac-aarch64"
# ✅ BUILD SUCCESS
```

### GitHub Actions 验证

- [x] Windows 构建命令格式修复
- [x] Linux 构建命令格式修复
- [x] macOS 构建命令格式修复
- [x] JavaFX classifier 更新
- [ ] Windows 实际构建运行（等待 CI）
- [ ] Linux 实际构建运行（等待 CI）
- [ ] macOS 实际构建运行（等待 CI）

## 技术要点

### JavaFX Classifier 变更原因

JavaFX 23 简化了平台标识：
- 对于主流桌面平台（x64），使用通用名称（`linux`, `win`, `mac`）
- ARM64 平台需要明确指定（`linux-aarch64`, `mac-aarch64`）

### Maven 参数最佳实践

```bash
# ✅ 推荐：使用引号
mvn package "-Dproperty=value"

# ❌ 避免：无引号（某些 shell 下会出错）
mvn package -Dproperty=value

# ✅ 对于 Windows：明确指定 shell
- shell: bash
  run: mvn package "-Dproperty=value"
```

### GitHub Actions Shell 选择

| 平台 | 默认 Shell | 推荐做法 |
|------|-----------|---------|
| Windows | PowerShell | 指定 `shell: bash` 以保持一致性 |
| macOS | bash | 默认即可 |
| Linux | bash | 默认即可 |

## 后续步骤

1. **提交代码**：推送修复到 GitHub
2. **验证 CI**：观察 GitHub Actions 运行结果
3. **测试产物**：下载构建的安装包进行测试
4. **代码签名**：生产环境考虑添加代码签名

---

**修复日期**: 2025-12-27
**修复人员**: OpenCode Agent
**状态**: ✅ 本地验证通过，等待 CI 验证
