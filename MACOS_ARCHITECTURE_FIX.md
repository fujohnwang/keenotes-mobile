# macOS架构兼容性问题修复

## 🚫 问题分析

### 症状
- Intel Mac上显示🚫禁止安装图标
- 明明是"Intel"版本的DMG，但在Intel Mac上无法安装

### 根本原因
1. **GitHub Actions Runner问题**: 
   - `macos-latest` 现在是Apple Silicon (M1/M2)
   - 即使指定`-Djavafx.platform=mac`，jpackage仍在ARM64系统上运行
   - 生成的应用包含ARM64架构的JVM和原生代码

2. **JavaFX原生库问题**:
   - JavaFX包含平台特定的原生库(.dylib文件)
   - 如果构建环境和目标平台不匹配，会包含错误架构的库

3. **jpackage架构检测**:
   - jpackage会根据运行环境的架构创建应用
   - 在ARM64系统上无法创建真正的x86_64应用

## ✅ 修复方案

### 1. 使用不同的GitHub Actions Runner

```yaml
strategy:
  matrix:
    include:
      - arch: mac
        name-suffix: Intel
        runner: macos-13      # Intel macOS runner
      - arch: mac-aarch64  
        name-suffix: AppleSilicon
        runner: macos-latest  # Apple Silicon macOS runner
runs-on: ${{ matrix.runner }}
```

**关键点**:
- `macos-13`: GitHub提供的Intel macOS runner
- `macos-latest`: Apple Silicon macOS runner
- 确保构建环境架构与目标架构匹配

### 2. 添加架构验证步骤

```yaml
- name: Show system info
  run: |
    echo "System architecture: $(uname -m)"
    echo "Java architecture: $(java -XshowSettings:properties -version 2>&1 | grep 'os.arch')"
    echo "Building for: ${{ matrix.arch }}"

- name: Verify JAR architecture
  run: |
    echo "Checking JAR contents for native libraries..."
    jar tf target/keenotes-mobile-1.0.0-SNAPSHOT.jar | grep -E '\.(dylib|jnilib)$' || echo "No native libraries found in JAR"

- name: Verify app architecture
  run: |
    echo "Checking app architecture..."
    if [ -d "dist/KeeNotes-${{ matrix.name-suffix }}.app" ]; then
      file "dist/KeeNotes-${{ matrix.name-suffix }}.app/Contents/MacOS/"*
      lipo -info "dist/KeeNotes-${{ matrix.name-suffix }}.app/Contents/MacOS/"* 2>/dev/null || echo "lipo info not available"
    fi
```

### 3. 添加macOS包标识符

```yaml
--mac-package-identifier "cn.keevol.keenotes" \
--mac-package-name "KeeNotes" \
```

这有助于macOS正确识别应用。

## 🔍 验证方法

### 构建后验证
1. **系统架构检查**: `uname -m`
   - Intel: `x86_64`
   - Apple Silicon: `arm64`

2. **JAR内容检查**: 
   ```bash
   jar tf target/keenotes-mobile-1.0.0-SNAPSHOT.jar | grep -E '\.(dylib|jnilib)$'
   ```
   应该看到对应架构的原生库

3. **应用二进制检查**:
   ```bash
   file "KeeNotes.app/Contents/MacOS/KeeNotes"
   lipo -info "KeeNotes.app/Contents/MacOS/KeeNotes"
   ```

### 安装测试
- **Intel版本**: 应该只能在Intel Mac上安装
- **Apple Silicon版本**: 应该能在Apple Silicon Mac上安装，也能通过Rosetta在Intel Mac上运行

## 📋 预期结果

修复后的构建应该产生：

1. **真正的Intel版本**:
   - 在Intel macOS runner (`macos-13`) 上构建
   - 包含x86_64架构的JavaFX原生库
   - 可以在Intel Mac上正常安装和运行

2. **真正的Apple Silicon版本**:
   - 在Apple Silicon macOS runner (`macos-latest`) 上构建
   - 包含arm64架构的JavaFX原生库
   - 可以在Apple Silicon Mac上原生运行

## 🚨 注意事项

1. **GitHub Actions限制**: 
   - `macos-13` (Intel) 可能在未来被弃用
   - 需要关注GitHub Actions的runner更新

2. **JavaFX版本兼容性**:
   - 确保JavaFX 23.0.1支持目标架构
   - 检查是否有架构特定的已知问题

3. **测试覆盖**:
   - 必须在真实的Intel和Apple Silicon Mac上测试
   - 虚拟机测试可能不准确

## 🔄 回退方案

如果GitHub Actions的Intel runner不可用，可以考虑：

1. **交叉编译**: 研究是否可以在Apple Silicon上交叉编译Intel版本
2. **单一通用版本**: 只提供Apple Silicon版本（可通过Rosetta在Intel上运行）
3. **本地构建**: 在Intel Mac上本地构建Intel版本

修复后，Intel Mac用户应该能够正常安装和运行"Intel"版本的DMG。