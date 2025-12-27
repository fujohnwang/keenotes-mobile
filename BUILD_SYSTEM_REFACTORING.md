# KeeNotes 构建系统重构总结

## 问题诊断

### 原始问题
- 使用 `jpackage` 打包的应用安装后启动错误
- JavaFX 组件缺失错误

### 根本原因
1. **错误的打包插件**：`pom.xml` 中使用了 `spring-boot-maven-plugin`，但项目不是 Spring Boot 项目
2. **缺少平台特定的 JavaFX 依赖**：JavaFX 需要为不同平台提供带 classifier 的 native libraries
3. **缺少 shade 插件**：没有将所有依赖打包成 fat JAR
4. **缺少 icon 资源**：桌面应用需要相应格式的 icon

## 解决方案实施

### 1. Maven 构建配置 (`pom.xml`)

#### 移除的配置
```xml
<!-- 移除：错误的 Spring Boot 插件 -->
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
</plugin>
```

#### 新增的配置

**a. Desktop Profile 使用 Maven Shade Plugin**
```xml
<profile>
    <id>desktop</id>
    <activation>
        <activeByDefault>true</activeByDefault>
    </activation>
    <dependencies>
        <!-- 为每个 JavaFX 模块添加平台特定的 native libraries -->
        <dependency>
            <groupId>org.openjfx</groupId>
            <artifactId>javafx-base</artifactId>
            <version>${javafx.version}</version>
            <classifier>${javafx.platform}</classifier>
        </dependency>
        <!-- 其他 JavaFX 模块相同配置 -->
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <version>3.5.1</version>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals>
                            <goal>shade</goal>
                        </goals>
                        <configuration>
                            <transformers>
                                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                                    <mainClass>${main.class}</mainClass>
                                </transformer>
                                <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
                            </transformers>
                            <filters>
                                <filter>
                                    <artifact>*:*</artifact>
                                    <excludes>
                                        <exclude>META-INF/*.SF</exclude>
                                        <exclude>META-INF/*.DSA</exclude>
                                        <exclude>META-INF/*.RSA</exclude>
                                    </excludes>
                                </filter>
                            </filters>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</profile>
```

**b. 属性配置**
```xml
<properties>
    <javafx.version>23.0.1</javafx.version>
    <!-- javafx.platform 通过命令行参数指定 -->
</properties>
```

### 2. Icon 资源准备

#### macOS ICNS 格式
```bash
# 从 Android PNG 创建不同尺寸
sips -z 16 16 app-icon.png --out icon_16x16.png
sips -z 32 32 app-icon.png --out icon_32x32.png
sips -z 128 128 app-icon.png --out icon_128x128.png
sips -z 256 256 app-icon.png --out icon_256x256.png
sips -z 512 512 app-icon.png --out icon_512x512.png

# 使用 iconutil 创建 ICNS
iconutil -c icns keenotes-desktop.iconset -o src/main/resources/icons/keenotes.icns
```

#### Windows/Linux PNG 格式
```bash
# 直接使用 PNG（Windows 推荐使用 ICO 格式）
cp ic_launcher.png src/main/resources/icons/icon-512.png
```

### 3. GitHub Actions 更新

#### Desktop Build Workflow (`.github/workflows/desktop-build.yml`)

**使用 Matrix 策略并行构建多平台**

```yaml
jobs:
  build-windows:
    runs-on: windows-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '21'
      
      - name: Build JAR
        shell: bash
        run: mvn clean package -Pdesktop -DskipTests "-Djavafx.platform=win"
      
      - name: Create Windows EXE
        shell: pwsh
        run: |
          jpackage `
            --input target `
            --name KeeNotes `
            --main-jar keenotes-mobile-1.0.0-SNAPSHOT.jar `
            --main-class cn.keevol.keenotes.mobilefx.Main `
            --type exe `
            --app-version 1.0.0 `
            --vendor "Keevol" `
            --icon "src/main/resources/icons/icon-512.png" `
            --dest dist

  build-macos:
    runs-on: macos-latest
    steps:
      # 类似配置，使用 mac-aarch64
      - name: Build JAR
        run: mvn clean package -Pdesktop -DskipTests -Djavafx.platform=mac-aarch64
      
      - name: Create macOS DMG
        run: |
          jpackage \
            --input target \
            --name KeeNotes \
            --main-jar keenotes-mobile-1.0.0-SNAPSHOT.jar \
            --main-class cn.keevol.keenotes.mobilefx.Main \
            --type dmg \
            --app-version 1.0.0 \
            --vendor "Keevol" \
            --icon "src/main/resources/icons/keenotes.icns" \
            --dest dist

  build-linux:
    runs-on: ubuntu-latest
    steps:
      # 类似配置，使用 linux
      - name: Build JAR
        run: mvn clean package -Pdesktop -DskipTests "-Djavafx.platform=linux"
      
      - name: Create DEB/RPM
        run: |
          jpackage ... --type deb ...
          jpackage ... --type rpm ...
```

### 4. 构建脚本

#### `build-desktop.sh` - 自动检测平台并构建
```bash
#!/bin/bash

# 检测平台
OS_NAME=$(uname -s)
OS_ARCH=$(uname -m)

case "$OS_NAME" in
    Darwin)
        if [ "$OS_ARCH" = "arm64" ]; then
            PLATFORM="mac-aarch64"
        else
            PLATFORM="mac"
        fi
        ;;
    Linux)
        if [ "$OS_ARCH" = "aarch64" ]; then
            PLATFORM="linux-aarch64"
        else
            PLATFORM="linux"
        fi
        ;;
esac

# 构建
mvn clean package -Pdesktop -DskipTests -Djavafx.platform=$PLATFORM

# 创建安装包
jpackage ...
```

#### `run.sh` - 开发运行脚本
```bash
#!/bin/bash

# 自动检测平台并构建 module-path
# 使用 --module-path 和 --add-modules 运行应用
java --module-path "<javafx-jars>" \
     --add-modules javafx.controls,javafx.fxml \
     -cp target/keenotes-mobile-1.0.0-SNAPSHOT.jar \
     cn.keevol.keenotes.mobilefx.Main "$@"
```

### 5. 文档更新

#### `README.md`
- 更新运行命令，添加平台参数说明
- 添加构建指南
- 说明不同平台的 classifier

#### `BUILD_GUIDE.md`
- 详细的构建步骤
- 平台特定配置
- 故障排除指南

## 平台支持

### Platform Classifiers

| Platform | OS | Arch | Classifier |
|----------|----|----|-----------|
| macOS ARM64 | macOS | arm64 | `mac-aarch64` |
| macOS Intel | macOS | x64 | `mac` |
| Linux x64 | Linux | x86_64 | `linux` |
| Linux ARM64 | Linux | aarch64 | `linux-aarch64` |
| Windows x64 | Windows | x64 | `win` |

### 打包格式

| Platform | Package Format |
|----------|---------------|
| macOS | DMG |
| Windows | EXE |
| Linux | DEB, RPM |

## 构建流程

### Desktop (开发环境)
```bash
# 1. 代码修改后运行
./run.sh

# 2. 构建 fat JAR
mvn clean package -Pdesktop -DskipTests -Djavafx.platform=mac-aarch64

# 3. 创建安装包
./build-desktop.sh
```

### CI/CD (GitHub Actions)
- Push 到 main/master 分支触发自动构建
- 并行构建 Windows、macOS、Linux 版本
- 上传安装包到 GitHub Actions Artifacts

## 技术要点

### 1. JavaFX 模块化打包

JavaFX 作为模块化库，在 JPMS 环境下需要特殊处理：

- **问题**：`java -jar` 无法自动加载模块依赖
- **解决**：使用 `jpackage` 创建自包含应用，包含自定义 JRE

### 2. Maven Shade Plugin 与模块系统

- Shade 会打破强封装（警告是正常的）
- `module-info.class` 需要保留在 JAR 根目录
- 过滤掉签名文件（SF/DSA/RSA）避免冲突

### 3. 平台特定 Native Libraries

JavaFX 包含平台特定的 JNI 库：
- macOS: `.dylib`
- Windows: `.dll`
- Linux: `.so`

这些库通过 classifier 指定：
```
javafx-graphics-23.0.1-mac-aarch64.jar
```

## 验证

### 本地验证
```bash
# 测试构建
./build-desktop.sh

# 测试运行
open dist/KeeNotes-1.0.0.dmg  # macOS
# 应用应该能够正常启动，没有 JavaFX 组件缺失错误
```

### CI/CD 验证
- 检查 GitHub Actions 运行状态
- 下载 artifacts 验证安装包
- 在对应平台测试安装和运行

## 文件变更清单

### 新增文件
- `src/main/resources/icons/keenotes.icns` - macOS icon
- `src/main/resources/icons/icon-512.png` - Windows/Linux icon
- `build-desktop.sh` - 桌面构建脚本
- `run.sh` - 开发运行脚本
- `BUILD_GUIDE.md` - 详细构建指南

### 修改文件
- `pom.xml` - 移除 spring-boot-maven-plugin，添加 shade plugin
- `.github/workflows/desktop-build.yml` - 更新构建流程
- `README.md` - 更新使用说明

### 删除文件
- 无

## 后续优化建议

1. **Windows ICO 格式**：使用 ImageMagick 创建专业的 ICO 格式 icon
2. **代码签名**：为 Windows/macOS 应用添加代码签名
3. **自动化测试**：在 CI/CD 中添加 UI 自动化测试
4. **Release 自动化**：创建 GitHub Release 时自动上传构建产物
5. **多架构支持**：为 macOS 创建 Universal Binary（包含 ARM64 + x64）

## 总结

通过这次重构：
- ✅ 解决了 JavaFX 组件缺失问题
- ✅ 实现了跨平台自动化构建
- ✅ 提供了完整的开发文档
- ✅ 建立了可重复的构建流程

现在可以稳定地：
- 在本地开发和测试桌面应用
- 在 CI/CD 中自动构建所有平台的安装包
- 用户可以直接下载并运行安装包，无需配置 Java 环境
