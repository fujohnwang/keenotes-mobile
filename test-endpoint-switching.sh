#!/bin/bash

# Test script for endpoint switching functionality

echo "=== Endpoint Switching Test ==="
echo "This script helps verify the endpoint switching fixes"
echo

# Check if the key files have been modified
echo "1. Checking modified files..."

if grep -q "reinitializeServices" src/main/java/cn/keevol/keenotes/mobilefx/ServiceManager.java; then
    echo "   ✅ ServiceManager.reinitializeServices() method added"
else
    echo "   ❌ ServiceManager.reinitializeServices() method missing"
fi

if grep -q "initializeServices" src/main/java/cn/keevol/keenotes/mobilefx/ServiceManager.java; then
    echo "   ✅ ServiceManager.initializeServices() method added"
else
    echo "   ❌ ServiceManager.initializeServices() method missing"
fi

if grep -q "configurationChanged" src/main/java/cn/keevol/keenotes/mobilefx/SettingsView.java; then
    echo "   ✅ SettingsView configuration change detection added"
else
    echo "   ❌ SettingsView configuration change detection missing"
fi

if grep -q "resetSyncState" src/main/java/cn/keevol/keenotes/mobilefx/LocalCacheService.java; then
    echo "   ✅ LocalCacheService.resetSyncState() method added"
else
    echo "   ❌ LocalCacheService.resetSyncState() method missing"
fi

if grep -q "reconnect" src/main/java/cn/keevol/keenotes/mobilefx/WebSocketClientService.java; then
    echo "   ✅ WebSocketClientService.reconnect() method added"
else
    echo "   ❌ WebSocketClientService.reconnect() method missing"
fi

echo

# Check for key functionality
echo "2. Checking key functionality..."

echo "   Configuration change detection:"
if grep -q "endpointChanged.*tokenChanged.*passwordChanged" src/main/java/cn/keevol/keenotes/mobilefx/SettingsView.java; then
    echo "   ✅ Detects endpoint, token, and password changes"
else
    echo "   ❌ Configuration change detection incomplete"
fi

echo "   Service cleanup:"
if grep -q "webSocketService.disconnect" src/main/java/cn/keevol/keenotes/mobilefx/ServiceManager.java; then
    echo "   ✅ Disconnects old WebSocket connection"
else
    echo "   ❌ Old WebSocket connection cleanup missing"
fi

if grep -q "resetSyncState" src/main/java/cn/keevol/keenotes/mobilefx/ServiceManager.java; then
    echo "   ✅ Resets local cache sync state"
else
    echo "   ❌ Local cache sync state reset missing"
fi

echo

# Manual testing instructions
echo "3. Manual Testing Instructions:"
echo
echo "To test endpoint switching manually:"
echo
echo "Step 1: Initial Setup"
echo "   - Start the application: ./run.sh"
echo "   - Go to Settings"
echo "   - Configure endpoint1: https://api1.example.com"
echo "   - Configure a test token"
echo "   - Save settings"
echo "   - Verify connection attempt in logs"
echo
echo "Step 2: Endpoint Switch Test"
echo "   - Go back to Settings"
echo "   - Change endpoint to: https://api2.example.com"
echo "   - Save settings"
echo "   - Check logs for:"
echo "     * '[SettingsView] Configuration changed, reinitializing services...'"
echo "     * '[ServiceManager] Disconnecting old WebSocket connection...'"
echo "     * '[LocalCache] Resetting sync state (keeping data)...'"
echo "     * '[ServiceManager] Reconnecting to new endpoint...'"
echo
echo "Step 3: Token Change Test"
echo "   - Keep same endpoint, change only token"
echo "   - Save settings"
echo "   - Verify same cleanup and reconnection process"
echo
echo "Expected Behavior:"
echo "   ✅ Old connection should be disconnected"
echo "   ✅ Sync state should be reset"
echo "   ✅ New connection should be attempted"
echo "   ✅ UI should show 'Configuration changed, reconnecting...'"
echo "   ✅ No resource leaks or duplicate connections"
echo

echo "4. Log Messages to Watch For:"
echo
echo "Configuration Change Detection:"
echo "   [SettingsView] Configuration changed, reinitializing services..."
echo "   [SettingsView] - Endpoint changed: true/false"
echo "   [SettingsView] - Token changed: true/false"
echo "   [SettingsView] - Password changed: true/false"
echo
echo "Service Cleanup:"
echo "   [ServiceManager] Reinitializing services due to configuration change..."
echo "   [ServiceManager] Disconnecting old WebSocket connection..."
echo "   [WebSocket] Disconnecting..."
echo "   [LocalCache] Resetting sync state (keeping data)..."
echo
echo "Reconnection:"
echo "   [ServiceManager] Reconnecting to new endpoint..."
echo "   [ServiceManager] Checking WebSocket connection..."
echo "   [WebSocket] Force reconnecting..."
echo

echo "=== Test Complete ==="
echo
echo "If all checks pass, the endpoint switching fix is properly implemented."
echo "Run manual tests to verify the actual behavior."