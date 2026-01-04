#!/bin/bash

echo "Building KeeNotes for desktop..."

# Use build-desktop.sh
./build-desktop.sh

if [ $? -eq 0 ]; then
    echo "Desktop build complete!"
    echo "Output files:"
    ls -lh dist/
else
    echo "Desktop build failed!"
    exit 1
fi
