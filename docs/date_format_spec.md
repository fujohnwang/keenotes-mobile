# Note 日期格式审计报告

> 审计日期：2026-03-23
> 审计范围：JavaFX、Android、iOS 三端以及 MCP/API 服务器层面对 note 的 createdAt/ts 字段的日期格式检查、转化和标准化逻辑

---

## 执行摘要

本审计发现 **三端在日期格式处理上存在严重不一致**，同一端内不同组件也使用不同格式。这可能导致数据同步问题、排序错误和用户体验不一致。

---

## 1. JavaFX 桌面端

| 组件 | 日期格式 | 处理方式 | 关键代码位置 |
|------|---------|---------|-------------|
| **发送 Note (ApiServiceV2)** | `yyyy-MM-dd HH:mm:ss` | 使用 `DateTimeFormatter` 生成当前时间 | `ApiServiceV2.java:19,93` |
| **Import Note (DataImportService)** | 接受 `created_at` 或 `ts` 字段 | 仅验证字段存在性，**不验证格式**，直接透传 | `DataImportService.java:94-100,174-176` |
| **MCP AddNoteTool** | `yyyy-MM-dd HH:mm:ss` | 生成当前时间戳 | `AddNoteTool.java:16-17,43` |
| **WebSocket 发送** | `LocalDateTime.now().toString()` | 使用默认 ISO 格式（**与 HTTP API 不同！**） | `WebSocketClientService.java:310` |
| **WebSocket 接收** | 透传服务器返回的 `created_at` | 直接存储，**无格式验证** | `WebSocketClientService.java:404,489` |
| **本地缓存 (SQLite)** | 字符串存储 | `DATETIME` 类型，使用 SQLite 的 `CURRENT_TIMESTAMP` | `LocalCacheService.java:203,227` |

### JavaFX 问题发现

1. **HTTP API 和 WebSocket 使用不同格式**：HTTP 使用 `yyyy-MM-dd HH:mm:ss`，WebSocket 使用 `LocalDateTime.toString()`（ISO 格式）
2. **Import 时不验证日期格式**：仅检查字段存在性，不验证格式是否正确
3. **ForwardHandler 使用 `created_at` 字段名**：与其他组件使用的 `ts` 字段名不一致

---

## 2. Android 端

| 组件 | 日期格式 | 处理方式 | 关键代码位置 |
|------|---------|---------|-------------|
| **发送 Note (ApiService)** | `Instant.now().toString()` | 使用 ISO 8601 格式（**与 JavaFX 不同！**） | `ApiService.kt:84` |
| **PendingNoteService** | `yyyy-MM-dd HH:mm:ss` | 使用 `DateTimeFormatter` 生成 | `PendingNoteService.kt:24,63` |
| **WebSocket 接收** | 透传 `created_at` 或 `createdAt` | 尝试两个字段名，**无格式验证** | `WebSocketService.kt:470` |
| **Note 实体类** | 字符串存储 | 注释说明是 ISO 8601 格式 | `Note.kt:20` |

### Android 问题发现

1. **与 JavaFX 格式不一致**：Android 使用 `Instant.now().toString()`（ISO 8601），JavaFX 使用 `yyyy-MM-dd HH:mm:ss`
2. **PendingNote 使用不同格式**：`PendingNoteService` 使用 `yyyy-MM-dd HH:mm:ss`，而 `ApiService` 使用 ISO 格式
3. **WebSocket 接收时兼容两个字段名**：`created_at` 和 `createdAt`，但没有格式标准化

---

## 3. iOS 端

| 组件 | 日期格式 | 处理方式 | 关键代码位置 |
|------|---------|---------|-------------|
| **发送 Note (ApiService)** | `yyyy-MM-dd HH:mm:ss` | 使用 `DateFormatter` 生成 | `ApiService.swift:16-20,47` |
| **PendingNoteService** | 使用 ISO8601DateFormatter | 生成 ISO 格式（**与 ApiService 不同！**） | `DatabaseService.swift:276` |
| **WebSocket 接收** | 透传 `created_at` 或 `createdAt` | 尝试两个字段名，**无格式验证** | `WebSocketService.swift:370` |
| **Note 实体类** | 字符串存储 | 无特定格式约束 | `Note.swift:9` |

### iOS 问题发现

1. **ApiService 和 PendingNote 使用不同格式**：ApiService 使用 `yyyy-MM-dd HH:mm:ss`，PendingNote 使用 ISO 8601
2. **与 Android HTTP API 格式不一致**：Android 使用 ISO 格式，iOS 使用自定义格式

---

## 4. MCP/API 服务器层

**关键发现：服务器端代码在这个仓库中不存在！**

这个仓库只包含客户端代码（JavaFX、Android、iOS）。服务器端处理逻辑应该在另一个仓库中。从客户端代码可以推断：

1. **JavaFX (ForwardHandler)** 期望接收 `created_at` 字段
2. **所有客户端** 发送 `ts` 字段到服务器
3. **服务器返回** 的 note 数据使用 `created_at` 字段

---

## 关键问题总结

### 问题 1：三端发送格式不一致

```
JavaFX HTTP API:  yyyy-MM-dd HH:mm:ss
JavaFX WebSocket: LocalDateTime.toString() (ISO-like)
Android HTTP API: Instant.now().toString() (ISO 8601)
iOS HTTP API:     yyyy-MM-dd HH:mm:ss
```

### 问题 2：同一端内不同组件格式不一致

```
JavaFX: HTTP API vs WebSocket 使用不同格式
Android: ApiService vs PendingNoteService 使用不同格式
iOS: ApiService vs PendingNoteService 使用不同格式
```

### 问题 3：Import 时无日期格式验证

- `DataImportService` 仅验证字段存在性
- 不验证格式是否符合预期
- 可能导致无效日期存入系统

### 问题 4：WebSocket 接收时无格式标准化

- 直接透传服务器返回的日期字符串
- 不验证、不转换格式
- 客户端显示时可能解析失败

### 问题 5：字段名不一致

- 发送时使用：`ts`
- 接收时期望：`created_at` 或 `createdAt`
- ForwardHandler 期望：`created_at`

---

## 建议修复方案

### 短期修复（兼容性）

1. **统一所有端的发送格式**
   - 建议使用 ISO 8601 标准格式（`Instant.now().toString()`）
   - 或者统一使用 `yyyy-MM-dd HH:mm:ss`

2. **统一字段名**
   - 全部使用 `ts` 或 `created_at`，保持一致
   - 建议与服务器协商确定标准字段名

### 中期修复（健壮性）

3. **添加日期格式验证**
   - 在 Import 和接收服务器数据时验证日期格式
   - 拒绝无效格式，提供明确错误信息

4. **添加日期格式转换器**
   - 在显示层统一转换为用户本地格式
   - 内部存储使用标准化格式

### 长期修复（架构）

5. **服务器端标准化**
   - 服务器应该接收各种格式，但存储和返回标准化格式
   - 服务器应该验证所有输入的日期格式

6. **使用强类型日期**
   - 考虑在数据库层使用 `DATETIME` 类型而非字符串
   - 在应用层使用 `Instant` 或 `LocalDateTime` 对象而非字符串

---

## 附录：代码引用

### JavaFX

```java
// ApiServiceV2.java:19
private static final DateTimeFormatter TS_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

// WebSocketClientService.java:310
.put("timestamp", LocalDateTime.now().toString());
```

### Android

```kotlin
// ApiService.kt:84
val ts = java.time.Instant.now().toString()

// PendingNoteService.kt:24,63
private val TS_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
val now = LocalDateTime.now().format(TS_FORMATTER)
```

### iOS

```swift
// ApiService.swift:16-20
private let dateFormatter: DateFormatter = {
    let formatter = DateFormatter()
    formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
    return formatter
}()

// DatabaseService.swift:276
let now = ISO8601DateFormatter().string(from: Date())
```

---

*文档版本：1.0*
*最后更新：2026-03-23*
