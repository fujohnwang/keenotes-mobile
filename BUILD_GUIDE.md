# KeeNotes Build Guide

## Desktop Application

### Prerequisites
- JDK 21 or later
- Maven 3.8+

### Build Process

#### 1. Development Run
```bash
mvn javafx:run -Djavafx.platform=<platform>
```

#### 2. Build JAR (Fat JAR)
```bash
mvn clean package -Pdesktop -DskipTests -Djavafx.platform=<platform>
```

This creates `target/keenotes-mobile-1.0.0-SNAPSHOT.jar` containing:
- Application classes
- All dependencies
- Platform-specific JavaFX native libraries

#### 3. Create Installation Package
```bash
# macOS
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

# Linux (DEB)
jpackage \
  --input target \
  --name keenotes \
  --main-jar keenotes-mobile-1.0.0-SNAPSHOT.jar \
  --main-class cn.keevol.keenotes.mobilefx.Main \
  --type deb \
  --app-version 1.0.0 \
  --vendor "Keevol" \
  --icon "src/main/resources/icons/icon-512.png" \
  --linux-shortcut \
  --dest dist

# Windows
jpackage \
  --input target \
  --name KeeNotes \
  --main-jar keenotes-mobile-1.0.0-SNAPSHOT.jar \
  --main-class cn.keevol.keenotes.mobilefx.Main \
  --type exe \
  --app-version 1.0.0 \
  --vendor "Keevol" \
  --icon "src/main/resources/icons/icon-512.png" \
  --dest dist
```

#### 4. Automated Build
```bash
./build-desktop.sh
```

This script:
- Detects current platform
- Builds fat JAR with correct JavaFX classifier
- Creates platform-specific installation package

### Platform Classifiers

| Platform | Classifier |
|----------|-----------|
| macOS ARM64 (Apple Silicon) | `mac-aarch64` |
| macOS Intel (x64) | `mac` |
| Linux x64 | `linux` |
| Linux ARM64 | `linux-aarch64` |
| Windows x64 | `win` |

## Mobile Applications

### Android
```bash
export ANDROID_HOME=/path/to/android/sdk
export GRAALVM_HOME=/path/to/gluon-graalvm

mvn gluonfx:build -Pandroid
mvn gluonfx:package -Pandroid
```

Output: `target/gluonfx/aarch64-android/*.apk`

### iOS (macOS only)
```bash
export GRAALVM_HOME=/path/to/gluon-graalvm

mvn gluonfx:build -Pios
mvn gluonfx:package -Pios
```

Output: `target/gluonfx/arm64-ios/*.ipa`

## Troubleshooting

### JavaFX Runtime Components Missing
If you see "JavaFX runtime components are missing" error:
1. Ensure you're using the correct `javafx.platform` classifier
2. For `java -jar`, use `./run.sh` script instead
3. For installation packages, use jpackage (creates self-contained apps)

### Icon Issues
- macOS: Requires ICNS format (already provided)
- Windows: Requires ICO format (PNG works but not recommended)
- Linux: PNG format supported

### Module System Issues
The project uses JPMS modules. When using Maven Shade Plugin:
- Some encapsulation warnings are expected
- The final JAR includes all necessary dependencies
- Use jpackage for production deployments (handles modules correctly)

## CI/CD

GitHub Actions automatically build for all platforms:
- `.github/workflows/desktop-build.yml` - Desktop (Windows, macOS, Linux)
- `.github/workflows/android.yml` - Android APK

Artifacts are uploaded and available in Actions runs.
