#!/bin/bash

# Test Android build script

echo "Testing Android build..."

# Clean first
mvn clean

# Test compilation
echo "Step 1: Testing compilation..."
mvn compile -Pandroid
if [ $? -ne 0 ]; then
    echo "❌ Compilation failed"
    exit 1
fi
echo "✅ Compilation successful"

# Test package (without native build)
echo "Step 2: Testing package..."
mvn package -Pandroid -DskipTests
if [ $? -ne 0 ]; then
    echo "❌ Package failed"
    exit 1
fi
echo "✅ Package successful"

echo "🎉 Android build test completed successfully!"
echo "To build APK, run: mvn gluonfx:build -Pandroid"
echo "To package APK, run: mvn gluonfx:package -Pandroid"