# 增量同步重构 Checklist

## 背景
手机端同步几万条数据时，因熄屏导致 WebSocket 断连，而 last_sync_id 只在 sync_complete 时更新，导致每次重连都从头同步，形成死循环。

## 方案概要
1. 每个 sync_batch 到达后立即写入 DB，不再使用 syncBatchBuffer 缓存
2. 每个 batch 写入后，用该 batch 中最大的 note ID 更新 last_sync_id（断点续传）
3. sync_complete 简化为：用服务器返回的 last_sync_id 再更新一次，设置同步状态为完成
4. 移除 syncBatchBuffer 及相关字段（expectedBatches、receivedBatches 保留用于进度/日志）
5. 移动端同步期间保持屏幕常亮（Android: FLAG_KEEP_SCREEN_ON, iOS: isIdleTimerDisabled）

---

## Step 1: Desktop (JavaFX) - WebSocketClientService.java
- [x] 1.1 修改 handleSyncBatch：每个 batch 解析后直接调 batchInsertNotes 写入 DB
- [x] 1.2 修改 handleSyncBatch：写入后取 batch 中最大 note ID，调 updateLastSyncId
- [x] 1.3 简化 handleSyncComplete：移除 batchInsertNotes 调用，只更新 last_sync_id + 重置状态
- [x] 1.4 移除 syncBatchBuffer 字段及其所有引用
- [x] 1.5 验证：mvn compile 通过

## Step 2: Android - WebSocketService.kt
- [x] 2.1 修改 handleSyncBatch：每个 batch 解析后直接调 noteDao.insertAll 写入 DB
- [x] 2.2 修改 handleSyncBatch：写入后取 batch 中最大 note ID，更新 syncStateDao + lastSyncId
- [x] 2.3 简化 handleSyncComplete：移除 noteDao.insertAll 调用，只更新 last_sync_id + 重置状态
- [x] 2.4 移除 syncBatchBuffer 字段及其所有引用
- [x] 2.5 添加同步期间屏幕常亮（FLAG_KEEP_SCREEN_ON）
- [x] 2.6 验证：gradle build 通过（无 gradlew，已通过 IDE diagnostics 验证无语法/类型错误）

## Step 3: iOS - WebSocketService.swift
- [x] 3.1 修改 handleSyncBatch：每个 batch 解析后直接调 databaseService.insertNotes 写入 DB
- [x] 3.2 修改 handleSyncBatch：写入后取 batch 中最大 note ID，更新 databaseService.updateSyncState
- [x] 3.3 简化 handleSyncComplete：移除 insertNotes 调用，只更新 last_sync_id + 重置状态
- [x] 3.4 移除 syncBatchBuffer 字段及其所有引用
- [x] 3.5 添加同步期间屏幕常亮（UIApplication.shared.isIdleTimerDisabled）
- [x] 3.6 验证：xcodebuild 通过
