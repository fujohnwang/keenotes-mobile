# Endpoint 切换状态处理分析

## 问题概述

用户在 Settings 中切换 endpoint 时，当前的实现**存在状态清理不完整的问题**。

## 当前实现分析

### SettingsView.saveSettings() 逻辑
```java
private void saveSettings() {
    // 1. 验证密码匹配
    // 2. 检查是否为新配置 (从未配置到已配置)
    boolean wasConfiguredBefore = settings.isConfigured();
    
    // 3. 保存新设置
    settings.setEndpointUrl(endpointField.getText().trim());
    settings.setToken(tokenField.getText());
    settings.setEncryptionPassword(password);
    settings.save();
    
    // 4. 只处理从未配置到已配置的情况
    if (!wasConfiguredBefore && settings.isConfigured()) {
        // 触发缓存初始化
        ServiceManager.getInstance().getLocalCacheService();
    }
    
    // 5. 尝试重新连接WebSocket
    if (settings.isConfigured()) {
        ServiceManager.getInstance().connectWebSocketIfNeeded();
    }
}
```

### 🔴 关键问题识别

#### 1. **缺少 Endpoint 变更检测**
```java
// 当前代码只检查：未配置 -> 已配置
boolean wasConfiguredBefore = settings.isConfigured();

// 缺少检查：endpoint1 -> endpoint2 的变更
// 应该添加：
String oldEndpoint = settings.getEndpointUrl();
String newEndpoint = endpointField.getText().trim();
boolean endpointChanged = !oldEndpoint.equals(newEndpoint);
```

#### 2. **缺少旧连接清理**
- 当 endpoint 变更时，没有主动断开旧的 WebSocket 连接
- 旧连接可能仍然存在，造成资源泄露
- 可能出现同时连接到两个不同 endpoint 的情况

#### 3. **缺少本地缓存清理**
- 不同 endpoint 的数据应该隔离
- 切换 endpoint 时，本地缓存中的数据可能来自旧 endpoint
- 没有清理或重置本地缓存的逻辑

#### 4. **缺少同步状态重置**
- `lastSyncId` 等同步状态没有重置
- 新 endpoint 的同步可能从错误的位置开始

## 具体问题场景

### 场景 1: Endpoint 切换
```
用户操作：
1. 连接到 endpoint1 (https://api1.example.com)
2. 同步了一些数据，lastSyncId = 100
3. 在 Settings 中改为 endpoint2 (https://api2.example.com)
4. 点击 Save

当前行为：
❌ 旧的 WebSocket 连接仍然存在
❌ 本地缓存包含 endpoint1 的数据
❌ lastSyncId = 100，但 endpoint2 可能没有这个 ID
❌ 新连接尝试从 ID 100 开始同步，可能失败

期望行为：
✅ 断开旧的 WebSocket 连接
✅ 清理本地缓存
✅ 重置同步状态 (lastSyncId = -1)
✅ 连接到新 endpoint 并从头开始同步
```

### 场景 2: Token 变更 (相同 Endpoint)
```
用户操作：
1. 连接到 endpoint1 with token1
2. 在 Settings 中改为 token2 (相同 endpoint)
3. 点击 Save

当前行为：
❌ 旧连接使用 token1 仍然存在
❌ 新连接尝试使用 token2，可能导致认证冲突

期望行为：
✅ 断开旧连接
✅ 使用新 token 重新连接
```

## 代码层面的问题

### ServiceManager.connectWebSocketIfNeeded()
```java
public void connectWebSocketIfNeeded() {
    // 问题：没有检查是否需要断开旧连接
    // 直接调用 ws.connect()，但如果已经连接会被忽略
    WebSocketClientService ws = getWebSocketService();
    ws.connect(); // 如果已连接，这个调用会被忽略
}
```

### WebSocketClientService.connect()
```java
public void connect() {
    if (isConnected.get() || isConnecting.get()) {
        logger.info("Already connected or connecting");
        return; // 🔴 问题：直接返回，不检查 endpoint 是否变更
    }
    // ...
}
```

## 修复方案

### 1. 在 SettingsView 中添加变更检测
```java
private void saveSettings() {
    // 保存变更前的状态
    String oldEndpoint = settings.getEndpointUrl();
    String oldToken = settings.getToken();
    boolean wasConfiguredBefore = settings.isConfigured();
    
    // 保存新设置
    settings.setEndpointUrl(endpointField.getText().trim());
    settings.setToken(tokenField.getText());
    settings.setEncryptionPassword(password);
    settings.save();
    
    // 检查关键配置是否变更
    String newEndpoint = settings.getEndpointUrl();
    String newToken = settings.getToken();
    
    boolean endpointChanged = !Objects.equals(oldEndpoint, newEndpoint);
    boolean tokenChanged = !Objects.equals(oldToken, newToken);
    boolean configurationChanged = endpointChanged || tokenChanged;
    
    if (configurationChanged && wasConfiguredBefore) {
        // 配置变更：需要清理旧状态并重新初始化
        System.out.println("[SettingsView] Configuration changed, reinitializing services...");
        ServiceManager.getInstance().reinitializeServices();
    } else if (!wasConfiguredBefore && settings.isConfigured()) {
        // 首次配置：正常初始化
        System.out.println("[SettingsView] New configuration detected, initializing services...");
        ServiceManager.getInstance().initializeServices();
    }
}
```

### 2. 在 ServiceManager 中添加重新初始化方法
```java
/**
 * 重新初始化所有服务（用于配置变更）
 */
public synchronized void reinitializeServices() {
    System.out.println("[ServiceManager] Reinitializing services due to configuration change...");
    
    // 1. 断开旧的 WebSocket 连接
    if (webSocketService != null && webSocketService.isConnected()) {
        System.out.println("[ServiceManager] Disconnecting old WebSocket connection...");
        webSocketService.disconnect();
    }
    
    // 2. 清理本地缓存 (可选：根据需求决定是否清理数据)
    if (localCacheService != null) {
        System.out.println("[ServiceManager] Resetting local cache sync state...");
        localCacheService.resetSyncState(); // 重置同步状态，但保留数据
        // 或者 localCacheService.clearAllData(); // 完全清理数据
    }
    
    // 3. 重置服务状态
    webSocketConnected = false;
    servicesReady = false;
    
    // 4. 重新连接
    connectWebSocketIfNeeded();
}

/**
 * 初始化服务（用于首次配置）
 */
public synchronized void initializeServices() {
    // 触发缓存初始化
    getLocalCacheService();
    
    // 连接 WebSocket
    connectWebSocketIfNeeded();
}
```

### 3. 在 LocalCacheService 中添加状态重置方法
```java
/**
 * 重置同步状态（保留数据）
 */
public void resetSyncState() {
    ensureInitialized();
    try (PreparedStatement stmt = connection.prepareStatement(
            "UPDATE sync_state SET last_sync_id = -1, last_sync_time = NULL WHERE id = 1")) {
        stmt.executeUpdate();
        System.out.println("[LocalCache] Sync state reset to initial state");
    } catch (SQLException e) {
        System.err.println("[LocalCache] Failed to reset sync state: " + e.getMessage());
    }
}

/**
 * 清理所有缓存数据
 */
public void clearAllData() {
    ensureInitialized();
    try {
        // 清理笔记缓存
        try (PreparedStatement stmt = connection.prepareStatement("DELETE FROM notes_cache")) {
            int deleted = stmt.executeUpdate();
            System.out.println("[LocalCache] Cleared " + deleted + " cached notes");
        }
        
        // 重置同步状态
        resetSyncState();
        
        System.out.println("[LocalCache] All cache data cleared");
    } catch (SQLException e) {
        System.err.println("[LocalCache] Failed to clear cache data: " + e.getMessage());
    }
}
```

### 4. 在 WebSocketClientService 中改进连接逻辑
```java
/**
 * 强制重新连接（断开旧连接并建立新连接）
 */
public void reconnect() {
    System.out.println("[WebSocket] Force reconnecting...");
    
    // 断开旧连接
    if (isConnected.get()) {
        disconnect();
        
        // 等待断开完成
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    // 建立新连接
    connect();
}

/**
 * 检查当前连接的 endpoint 是否与配置匹配
 */
public boolean isConnectedToCurrentEndpoint() {
    if (!isConnected.get()) {
        return false;
    }
    
    String currentEndpoint = settings.getEndpointUrl();
    // 这里需要存储当前连接的 endpoint 进行比较
    // 可以在连接时保存 connectedEndpoint 字段
    return Objects.equals(connectedEndpoint, currentEndpoint);
}
```

## 用户体验改进

### 1. 添加状态提示
```java
// 在 SettingsView 中添加更详细的状态提示
if (configurationChanged && wasConfiguredBefore) {
    statusLabel.setText("Configuration changed, reconnecting...");
    // 异步更新状态
    new Thread(() -> {
        ServiceManager.getInstance().reinitializeServices();
        Platform.runLater(() -> {
            statusLabel.setText("Settings saved ✓ (Reconnected to new endpoint)");
        });
    }).start();
}
```

### 2. 添加确认对话框
```java
// 对于重大配置变更，可以添加确认对话框
if (endpointChanged && wasConfiguredBefore) {
    Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
    alert.setTitle("Endpoint Changed");
    alert.setHeaderText("You are changing the server endpoint");
    alert.setContentText("This will disconnect from the current server and clear local cache. Continue?");
    
    Optional<ButtonType> result = alert.showAndWait();
    if (result.isPresent() && result.get() == ButtonType.OK) {
        // 执行变更
    } else {
        // 恢复旧设置
        return;
    }
}
```

## 总结

当前的 endpoint 切换实现**存在严重的状态管理问题**：

### 🔴 主要问题
1. **不检测配置变更** - 只处理首次配置
2. **不清理旧连接** - 可能导致资源泄露
3. **不重置本地状态** - 数据混乱
4. **不处理同步冲突** - 可能导致数据不一致

### ✅ 建议修复优先级
1. **高优先级**: 添加配置变更检测和旧连接清理
2. **中优先级**: 添加本地缓存状态重置
3. **低优先级**: 改进用户体验和错误处理

### 🎯 修复后的预期行为
- 用户切换 endpoint 时，自动断开旧连接
- 清理或重置本地缓存状态
- 重新连接到新 endpoint
- 提供清晰的状态反馈

这个问题需要尽快修复，否则用户在切换 endpoint 时可能遇到数据混乱或连接问题。