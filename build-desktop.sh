#!/bin/bash

# Build script for Desktop application

# Detect platform
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

echo "Building for platform: $PLATFORM"

# Clean and package with Maven
mvn clean package -Pdesktop -DskipTests -Djavafx.platform=$PLATFORM

# Create distribution
if [ $? -eq 0 ]; then
    echo "Build successful. Creating package..."
    
    mkdir -p dist
    
    case "$OS_NAME" in
        Darwin)
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
            ;;
        Linux)
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
            ;;
        MINGW*|MSYS*|CYGWIN*)
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
            ;;
    esac
    
    if [ $? -eq 0 ]; then
        echo "Package created successfully in dist/"
    else
        echo "Package creation failed"
        exit 1
    fi
else
    echo "Build failed"
    exit 1
fi
