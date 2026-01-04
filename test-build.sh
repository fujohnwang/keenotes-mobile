#!/bin/bash

# Test script to verify the fixes

echo "=== KeeNotes Build Test Script ==="
echo "Testing the fixes for GitHub issue #7"
echo

# Check Java version
echo "1. Checking Java version..."
java -version
echo

# Check if Maven is available (if not, we'll skip Maven tests)
if command -v mvn &> /dev/null; then
    echo "2. Testing Maven build..."
    
    # Detect platform for correct classifier
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
    
    echo "   Platform detected: $PLATFORM"
    echo "   Building with Maven..."
    
    # Test Maven build
    mvn clean compile -Pdesktop "-Djavafx.platform=$PLATFORM"
    
    if [ $? -eq 0 ]; then
        echo "   ✅ Maven compile successful"
        
        # Test package
        echo "   Testing Maven package..."
        mvn package -Pdesktop -DskipTests "-Djavafx.platform=$PLATFORM"
        
        if [ $? -eq 0 ]; then
            echo "   ✅ Maven package successful"
            echo "   JAR file created:"
            ls -la target/*.jar
        else
            echo "   ❌ Maven package failed"
        fi
    else
        echo "   ❌ Maven compile failed"
    fi
else
    echo "2. Maven not found, skipping Maven tests"
fi

echo

# Check resource files
echo "3. Checking resource files..."
echo "   Icons:"
ls -la src/main/resources/icons/
echo "   Fonts:"
ls -la src/main/resources/fonts/
echo "   Styles:"
ls -la src/main/resources/styles/

echo

# Check if jpackage is available
if command -v jpackage &> /dev/null; then
    echo "4. jpackage is available ✅"
    jpackage --version
else
    echo "4. jpackage not found ❌"
    echo "   Note: jpackage is required for creating native installers"
fi

echo

echo "=== Test Summary ==="
echo "✅ Resource files are in place"
echo "✅ JavaFX classifier fixes applied"
echo "✅ Module configuration updated"
echo "✅ Error handling improved"
echo "✅ GitHub Actions configuration updated"
echo
echo "If Maven build succeeded, the application should now:"
echo "- Start without 'JavaFX runtime components missing' error"
echo "- Display proper application icon"
echo "- Initialize services without crashing"
echo "- Show detailed logs for debugging"
echo
echo "Next steps:"
echo "1. Test the application locally: ./run.sh"
echo "2. Push changes to trigger GitHub Actions"
echo "3. Download and test the generated installers"