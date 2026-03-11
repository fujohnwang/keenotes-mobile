# Offline Cache Mode (Issue #98)

## 需求背景

用户在无网络环境下（如飞机上），无法发送闪念笔记。当前设计要求网络可用才能发送成功，发送失败后笔记直接丢失。

## 核心需求

1. 网络不可用时，将笔记暂存到本地 SQLite 的 `pending_notes` 表
2. 网络恢复后，自动重试发送暂存笔记
3. 发送成功后，物理删除 `pending_notes` 中对应记录（数据已进入服务端最终表）
4. 在 Note 页面提供 pending notes 的查看入口

## 数据库设计

各端在本地 SQLite 中新增 `pending_notes` 表：

```sql
CREATE TABLE IF NOT EXISTS pending_notes (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  content TEXT NOT NULL,
  channel TEXT DEFAULT 'desktop',  -- 各端使用各自的 channel 值
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);
```

- 只存明文，发送时走现有 `postNote()` API（内部处理加密）
- 发送成功后物理删除（因为数据已同步到服务端）

## 核心流程

```
用户点击发送
  → 检查网络状态（基于 WebSocket 连接状态）
  → 网络可用：走现有 postNote() 流程
      → 发送成功：正常流程
      → 发送失败（超时等）：存入 pending_notes，提示用户
  → 网络不可用：直接存入 pending_notes，提示"已暂存，网络恢复后自动发送"
```

## 自动重试机制

- 固定间隔 30 分钟定时重试
- WebSocket 重连成功时立即触发一次重试
- 按 `created_at` 顺序逐条发送
- 发送成功 → 物理删除该条记录

## UI 设计（三端一致）

### 提示条

当 `pendingCount > 0` 时，在 Note 页面输入区域上方显示提示条：

```
📤 X 条笔记待发送  [查看]
```

`pendingCount == 0` 时完全隐藏。

### Pending Notes 列表

点击"查看"后，在 Note 模式的主内容区域替换为 pending notes 列表：
- 顶部有返回按钮，回到正常 Note 视图
- 复用现有笔记列表的样式
- 每条显示明文内容和创建时间

### 各端实现规范

| 平台 | 提示条样式 | 列表样式 |
|------|-----------|---------|
| Desktop (JavaFX) | 内嵌组件，reactive binding | 复用 NotesDisplayPanel |
| Android | Material Design banner | 独立 Fragment/View |
| iOS | SwiftUI 内嵌组件 | 独立 View |

## 状态管理

使用 reactive property 管理 pending count：
- Desktop: `IntegerProperty pendingCountProperty`
- Android: `StateFlow<Int>` 或 `LiveData<Int>`
- iOS: `@Published var pendingCount: Int`

UI 各处绑定该 property，自动响应变化。

---

## 实现 Checklist

### Phase 1: Desktop (JavaFX)

- [x] 1.1 `LocalCacheService.initDatabase()` 新增 `pending_notes` 建表语句
- [x] 1.2 新建 `PendingNoteService` 类
  - pending notes 的 CRUD 操作
  - `pendingCountProperty` (reactive)
  - 定时重试逻辑（30 分钟间隔）
  - WebSocket 重连时触发重试
- [x] 1.3 修改 `MainContentArea.handleNoteSend()` — 加入网络检查和暂存逻辑
- [x] 1.4 `ServiceManager` 注册 `PendingNoteService`
- [x] 1.5 UI: Note 页面 pending 提示条 + pending notes 列表视图
- [x] 1.6 自检测：编译通过 + 逻辑验证

### Phase 2: iOS

- [x] 2.1 `LocalCacheService` 新增 `pending_notes` 建表
- [x] 2.2 新建 `PendingNoteService`（Swift 实现）
- [x] 2.3 修改 `NoteView` 发送逻辑 — 网络检查 + 暂存
- [x] 2.4 UI: pending 提示条 + pending notes 列表
- [x] 2.5 自检测：Xcode 编译通过

### Phase 3: Android

- [x] 3.1 本地数据库新增 `pending_notes` 建表
- [x] 3.2 新建 `PendingNoteService`（Kotlin 实现）
- [x] 3.3 修改发送逻辑 — 网络检查 + 暂存
- [x] 3.4 UI: pending 提示条 + pending notes 列表
- [x] 3.5 （用户自行用 Android Studio 编译测试）
