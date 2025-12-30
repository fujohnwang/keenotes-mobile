#!/bin/bash
# Get the platform
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
    MINGW*|MSYS*|CYGWIN*)
        PLATFORM="win"
        ;;
esac

# Build module path
MODULE_PATH=$(find ~/.m2/repository/org/openjfx -name "javafx-*-${PLATFORM}.jar" -o -name "javafx-*.jar" | grep "${PLATFORM}" | tr '\n' ':')

# Run application
java --module-path "${MODULE_PATH:0:-1}" \
     --add-modules javafx.controls,javafx.fxml \
     -cp target/keenotes-mobile-1.0.0-SNAPSHOT.jar \
     cn.keevol.keenotes.mobilefx.Main "$@"
