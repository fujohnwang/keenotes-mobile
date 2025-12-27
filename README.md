# KeeNotes Mobile

A cross-platform mobile note-taking app built with JavaFX and GluonFX.

## Requirements

- **JDK 21** or later
- **Maven 3.8+**
- **GraalVM** (for native compilation)

## Run on Desktop

```bash
mvn javafx:run -Djavafx.platform=<platform>
```

Available platforms:
- `mac-aarch64` - macOS ARM64 (Apple Silicon)
- `mac-x64` - macOS Intel
- `linux-amd64` - Linux x86_64
- `linux-aarch64` - Linux ARM64
- `win-x64` - Windows x64

Or use the provided run script:

```bash
./run.sh [args]
```

## Build Desktop Application

```bash
./build-desktop.sh
```

This will:
1. Clean and package the application as a fat JAR
2. Create platform-specific installation package (DMG/EXE/DEB/RPM)

## Build for Android

```bash
# Requires ANDROID_HOME and GRAALVM_HOME environment variables
mvn gluonfx:build -Pandroid
mvn gluonfx:package -Pandroid
```

## Build for iOS

```bash
# Requires macOS, Xcode, and GRAALVM_HOME
mvn gluonfx:build -Pios
mvn gluonfx:package -Pios
```

## Project Structure

```
src/main/java/
├── module-info.java
└── cn/keevol/keenotes/mobilefx/
    ├── Main.java        # Application entry point
    └── MainView.java    # Main UI view

src/main/resources/
├── icons/               # App icons
└── styles/
    └── main.css         # Main stylesheet
```

## Building for Different Platforms

The project uses Maven profiles for different platforms:

- **desktop** (default): Creates fat JAR with all dependencies and platform-specific JavaFX libraries
- **android**: Uses GluonFX to build native Android APK
- **ios**: Uses GluonFX to build native iOS app

GitHub Actions workflows automatically build for all platforms using matrix strategy.

### Manual Build

```bash
# Desktop (requires specifying platform)
mvn clean package -Pdesktop -Djavafx.platform=mac-aarch64

# Android
mvn gluonfx:build -Pandroid
mvn gluonfx:package -Pandroid

# iOS (macOS only)
mvn gluonfx:build -Pios
mvn gluonfx:package -Pios
```
