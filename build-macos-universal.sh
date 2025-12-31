#!/bin/bash

# Universal macOS build script - builds for both Intel and Apple Silicon

echo "Building Universal macOS packages..."

# Clean first
mvn clean

# Build for Intel (x86_64)
echo "Building for Intel (x86_64)..."
mvn package -Pdesktop -DskipTests -Djavafx.platform=mac
if [ $? -ne 0 ]; then
    echo "Intel build failed"
    exit 1
fi

# Create Intel package
mkdir -p dist/intel
jpackage \
    --input target \
    --name "KeeNotes-Intel" \
    --main-jar keenotes-mobile-1.0.0-SNAPSHOT.jar \
    --main-class cn.keevol.keenotes.mobilefx.Main \
    --type dmg \
    --app-version 1.0.0 \
    --vendor "Keevol" \
    --icon "src/main/resources/icons/keenotes.icns" \
    --dest dist/intel

# Clean and build for Apple Silicon (aarch64)
echo "Building for Apple Silicon (aarch64)..."
mvn clean package -Pdesktop -DskipTests -Djavafx.platform=mac-aarch64
if [ $? -ne 0 ]; then
    echo "Apple Silicon build failed"
    exit 1
fi

# Create Apple Silicon package
mkdir -p dist/apple-silicon
jpackage \
    --input target \
    --name "KeeNotes-AppleSilicon" \
    --main-jar keenotes-mobile-1.0.0-SNAPSHOT.jar \
    --main-class cn.keevol.keenotes.mobilefx.Main \
    --type dmg \
    --app-version 1.0.0 \
    --vendor "Keevol" \
    --icon "src/main/resources/icons/keenotes.icns" \
    --dest dist/apple-silicon

echo "Universal macOS build completed!"
echo "Intel version: dist/intel/KeeNotes-Intel-1.0.0.dmg"
echo "Apple Silicon version: dist/apple-silicon/KeeNotes-AppleSilicon-1.0.0.dmg"