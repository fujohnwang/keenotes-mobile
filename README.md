# KeeNotes Mobile

A cross-platform mobile note-taking app built with JavaFX and GluonFX.

## Requirements

- **JDK 21** or later
- **Maven 3.8+**
- **GraalVM** (for native compilation)

## Run on Desktop

```bash
mvn javafx:run
```

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
└── com/keenotes/mobile/
    ├── Main.java        # Application entry point
    └── MainView.java    # Main UI view

src/main/resources/
├── icons/               # App icons
└── styles/
    └── main.css         # Main stylesheet
```
