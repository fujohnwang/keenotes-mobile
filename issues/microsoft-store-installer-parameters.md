microsoft store的软件需要静默安装，所以，不管是exe还是msi，需要有支持静默安装。

一般需要指定一个参数，用不同软件制作的安装包，这个参数就不一样。

---

1. ✅ Windows 使用 **jpackage** 生成两种安装包（EXE 和 MSI）
2. ✅ **EXE 静默安装**：`/VERYSILENT /NORESTART`
3. ✅ **MSI 静默安装**：`msiexec /i xxx.msi /quiet /norestart`



---

## 当前 Windows Installer 方案

你的项目使用 **jpackage**（Java 21 自带的打包工具）创建了两种 Windows 安装包：

### 1. **EXE 安装包**（第 75-87 行）
- 基于 **Inno Setup** 引擎
- 文件格式：`.exe`

### 2. **MSI 安装包**（第 89-101 行）
- 基于 **WiX Toolset** 引擎
- 文件格式：`.msi`

## 静默安装参数

根据不同的安装包类型，静默安装参数不同：

### EXE 安装包（Inno Setup）

用户可以使用以下参数进行静默安装：

```bash
# 完全静默安装（无界面）
KeeNotes-1.0.0.exe /VERYSILENT /NORESTART

# 静默安装（显示进度条）
KeeNotes-1.0.0.exe /SILENT /NORESTART

# 静默安装 + 指定安装目录
KeeNotes-1.0.0.exe /VERYSILENT /DIR="C:\Program Files\KeeNotes"

# 静默安装 + 禁止创建桌面快捷方式
KeeNotes-1.0.0.exe /VERYSILENT /TASKS="!desktopicon"
```

### MSI 安装包（WiX）

用户可以使用以下参数进行静默安装：

```bash
# 完全静默安装
msiexec /i KeeNotes-1.0.0.msi /quiet /norestart

# 静默安装 + 显示进度条
msiexec /i KeeNotes-1.0.0.msi /passive /norestart

# 静默安装 + 指定安装目录
msiexec /i KeeNotes-1.0.0.msi /quiet INSTALLDIR="C:\Program Files\KeeNotes"

# 静默安装 + 生成日志
msiexec /i KeeNotes-1.0.0.msi /quiet /L*v install.log
```

## 如果要在 jpackage 中配置默认静默安装行为

jpackage 本身**不支持直接生成默认静默安装的 installer**，但你可以：

### 方案 1：添加 jpackage 参数（推荐用于 MSI）

对于 MSI，可以添加 `--win-per-user-install` 参数，让安装更轻量：

```powershell
jpackage `
  --input target `
  --name KeeNotes `
  --main-jar keenotes-mobile-1.0.0-SNAPSHOT.jar `
  --main-class org.springframework.boot.loader.launch.JarLauncher `
  --type msi `
  --app-version ${{ steps.version.outputs.app_version }} `
  --vendor "Keevol" `
  --icon "src/main/resources/icons/app-icon.ico" `
  --java-options "-Xmx512m" `
  --win-shortcut `
  --win-menu `
  --win-menu-group "Keevol" `
  --win-dir-chooser `
  --win-per-user-install `  # 添加此参数，允许用户级安装（无需管理员权限）
  --dest dist
```

### 方案 2：使用自定义配置文件（高级）

如果需要更精细的控制，可以：

1. **对于 EXE（Inno Setup）**：使用 `--resource-dir` 提供自定义 `.iss` 脚本
2. **对于 MSI（WiX）**：使用 `--resource-dir` 提供自定义 `.wxs` 文件

但这需要额外的配置工作。















