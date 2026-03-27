# Sync Channel Status 状态机修复

## 问题描述

用户反馈：不管是重试中还是重试失败，sync channel status 的 label 状态一直显示正常的 ✅。只有点击 sync channel 重连之后，note 才会再次同步成功。但实际上，可能那时候 sync channel 已经是僵死状态放弃重试了。

## 根本原因分析

### 当前状态机的问题

**WebSocketClientService 的状态通知机制不完整**：

1. **连接断开时**：
   - `onClosed()` 和 `onFailure()` 调用 `notifyConnectionStatus(false)`
   - UI 显示 `✗`（disconnected）

2. **重连过程中（第1-10次重试）**：
   - `scheduleReconnect()` 安排重连任务
   - **但没有通知 UI 进入 "reconnecting" 状态**
   - UI 一直保持 `✗`（disconnected），用户不知道系统在重试

3. **达到最大重试次数后**：
   - 调用 `notifyOffline()`
   - UI 显示 "offline"（灰色）

4. **手动重连后**：
   - `manualReconnect()` 重置 `reconnectAttempts = 0` 和 `isOffline.set(false)`
   - 调用 `connect()`，成功后显示 `✓`

### 状态机对比

**实际状态**：
```
connected → disconnected → reconnecting(1) → reconnecting(2) → ... → reconnecting(10) → offline
```

**UI 显示（修复前）**：
```
✓ → ✗ → ✗ → ✗ → ... → ✗ → offline
```

**问题**：
- 用户看到 `✗` 时，不知道系统是否在重试
- 用户可能误以为连接已经彻底断开
- 实际上系统可能正在第5次重试，但 UI 没有反馈

## 解决方案

### 1. 添加 `onReconnecting` 回调

在 `WebSocketClientService.SyncListener` 接口中添加新的回调方法：

```java
public interface SyncListener {
    void onConnectionStatus(boolean connected);
    void onSyncProgress(int current, int total);
    void onSyncComplete(int total, long lastSyncId);
    void onRealtimeUpdate(long id, String content);
    void onError(String message);
    
    /** 重连耗尽后进入离线状态 */
    default void onOffline() {}
    
    /** 正在重连中 */
    default void onReconnecting(int attempt, int maxAttempts) {}
}
```

### 2. 在 `scheduleReconnect()` 中通知 UI

```java
private void scheduleReconnect() {
    // ... 前置检查 ...
    
    int delay = RECONNECT_BASE_DELAY_MS * (int) Math.pow(2, reconnectAttempts);
    reconnectAttempts++;
    
    logger.info("Scheduling reconnect in " + delay + "ms (attempt " + reconnectAttempts + "/"
            + MAX_RECONNECT_ATTEMPTS + ")");
    
    // 通知 UI 进入重连状态
    notifyReconnecting(reconnectAttempts, MAX_RECONNECT_ATTEMPTS);
    
    // ... 安排重连任务 ...
}
```

### 3. UI 层处理 `onReconnecting` 回调

在 `NotesDisplayPanel` 中：

```java
@Override
public void onReconnecting(int attempt, int maxAttempts) {
    Platform.runLater(() -> showSyncChannelReconnecting(attempt, maxAttempts));
}

private void showSyncChannelReconnecting(int attempt, int maxAttempts) {
    if (syncChannelIndicator == null || syncChannelLabel == null) {
        return;
    }
    boolean isDark = ThemeService.getInstance().isDarkTheme();
    String reconnectingColor = isDark ? "#D29922" : "#BF8700";
    syncChannelIndicator.setFill(Color.web(reconnectingColor));
    syncChannelLabel.setText("Sync Channel: reconnecting (" + attempt + "/" + maxAttempts + ")");
    syncChannelLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: " + reconnectingColor + ";");
}
```

## 修复后的状态机

### 完整状态流转

```
connected (✓ 绿色)
    ↓ (连接断开)
disconnected (✗ 红色)
    ↓ (开始重连)
reconnecting (1/10) (⟳ 黄色)
    ↓ (重试失败)
reconnecting (2/10) (⟳ 黄色)
    ↓ (重试失败)
...
    ↓ (重试失败)
reconnecting (10/10) (⟳ 黄色)
    ↓ (达到最大重试次数)
offline (⊗ 灰色)
    ↓ (用户手动点击重连)
reconnecting (0/10) (⟳ 黄色)
    ↓ (连接成功)
connected (✓ 绿色)
```

### 状态说明

| 状态 | 显示 | 颜色 | 说明 |
|------|------|------|------|
| connected | `Sync Channel: ✓` | 绿色 | 连接正常 |
| disconnected | `Sync Channel: ✗` | 红色 | 连接断开（瞬态，立即进入重连） |
| reconnecting | `Sync Channel: reconnecting (N/10)` | 黄色 | 正在重连，显示当前尝试次数 |
| offline | `Sync Channel: offline` | 灰色 | 重连耗尽，可点击手动重连 |

## 用户体验改进

### 修复前
- 用户看到 `✗` 时，不知道系统是否在重试
- 用户可能误以为需要手动重连
- 实际上系统可能正在第5次重试

### 修复后
- 用户看到 `reconnecting (5/10)` 时，知道系统正在第5次重试
- 用户知道还有5次重试机会
- 用户可以等待系统自动重连，或者选择手动重连

## 修改文件清单

### JavaFX 端
1. `src/main/java/cn/keevol/keenotes/mobilefx/WebSocketClientService.java`
   - 添加 `onReconnecting(int attempt, int maxAttempts)` 回调接口
   - 添加 `notifyReconnecting()` 通知方法
   - 在 `scheduleReconnect()` 中调用 `notifyReconnecting()`

2. `src/main/java/cn/keevol/keenotes/mobilefx/NotesDisplayPanel.java`
   - 实现 `onReconnecting()` 回调
   - 修改 `showSyncChannelReconnecting()` 方法，添加 attempt 和 maxAttempts 参数
   - 修复手动重连时的调用

## 编译验证

- ✅ **JavaFX 端**: 编译成功

## 后续工作

### Android 端和 iOS 端

Android 和 iOS 端也需要类似的修复：

1. **Android 端**：
   - 在 `WebSocketService.ConnectionState` 中添加 `RECONNECTING` 状态
   - 或者在 `WebSocketService` 中添加 `reconnectAttempts` 和 `maxReconnectAttempts` 的 StateFlow
   - 在 UI 中根据重连状态显示不同的文案和颜色

2. **iOS 端**：
   - 在 `WebSocketService.ConnectionState` 中添加 `reconnecting` 状态
   - 或者添加 `@Published var reconnectAttempts: Int` 和 `maxReconnectAttempts`
   - 在 UI 中根据重连状态显示不同的文案和颜色

## 测试建议

1. **正常重连测试**：
   - 断开网络连接
   - 观察 UI 是否显示 `reconnecting (1/10)`, `reconnecting (2/10)`, ...
   - 恢复网络连接
   - 观察是否在某次重试中成功连接，显示 `✓`

2. **重连耗尽测试**：
   - 断开网络连接
   - 等待10次重试全部失败
   - 观察 UI 是否最终显示 `offline`

3. **手动重连测试**：
   - 在 `offline` 状态下点击 sync channel status
   - 观察 UI 是否显示 `reconnecting (0/10)`
   - 如果网络正常，观察是否成功连接并显示 `✓`

4. **快速断连重连测试**：
   - 快速断开和恢复网络连接多次
   - 观察 UI 状态是否正确更新
   - 确保不会出现状态错乱

## 总结

本次修复解决了 sync channel status 状态机不完整的问题，让用户能够清楚地看到系统的重连状态。通过添加 `onReconnecting` 回调和显示重连次数，用户可以：

1. 知道系统正在自动重连
2. 知道当前重连到第几次
3. 知道还有多少次重试机会
4. 在重连耗尽后，可以手动触发重连

这大大改善了用户体验，避免了用户误以为连接已经彻底断开而手动重连的情况。
