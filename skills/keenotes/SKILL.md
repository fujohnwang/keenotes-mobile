---
name: keenotes-sender-skill
description: 保存笔记到 KeeNotes 笔记应用。当用户想要保存、记录、记住某些内容，或提到"记"、"保存"、"记录"、"note"、"save"、"remember"、"KeeNotes"等关键词时使用。
metadata:
  author: "fuqiang wang"
  version: "1.0"
---

# KeeNotes 笔记保存

将笔记保存到 KeeNotes，一个支持端到端加密的个人笔记应用。

## 何时使用此技能

当用户：
- 要求保存、记录或记住某些内容
- 想要在 KeeNotes 中创建笔记
- 提到"记"、"保存"、"记录"、"note"、"save"、"remember"等关键词
- 明确提到"KeeNotes"

## 前置条件

KeeNotes 桌面应用必须正在运行。

## 如何保存笔记

### 方法一：使用 MCP（推荐）

如果 MCP 工具 `mcp_keenotes_add_note` 可用，使用它：

```json
{
  "content": "笔记内容",
  "channel": "ai-assistant",
  "encrypted": false
}
```

参数：
- `content`（必需）：笔记内容
- `channel`（可选）：来源标识（如 "kiro"、"claude"、"ai-assistant"）
- `encrypted`（可选）：内容是否已加密（默认：false）

### 方法二：直接使用 HTTP API

如果 MCP 不可用，使用 curl 或 httpie 调用 KeeNotes 的 import server API：

**使用 curl：**
```bash
curl -X POST http://localhost:1979/ \
  -H "Content-Type: application/json" \
  -d '{
    "content": "笔记内容",
    "channel": "ai-assistant",
    "created_at": "2025-02-08 15:30:00",
    "encrypted": false
  }'
```

**使用 httpie：**
```bash
http POST localhost:1979/ \
  content="笔记内容" \
  channel="ai-assistant" \
  created_at="2025-02-08 15:30:00" \
  encrypted:=false
```

**API 说明：**
- 端点：`http://localhost:1979/`（默认端口，可在 Settings → Data Import 中配置）
- 方法：POST
- Content-Type：application/json
- 请求体字段：
  - `content`（必需）：笔记内容
  - `channel`（可选）：来源渠道，默认 "ai-assistant"
  - `created_at`（可选）：创建时间，格式 "YYYY-MM-DD HH:mm:ss"
  - `encrypted`（可选）：内容是否已加密，默认 false
    - false：内容为明文，将使用 E2EE 加密后发送
    - true：内容已加密，直接发送

## 使用示例

### 示例 1：快速笔记
```
用户："记一下：今天完成了 MCP 集成"

方法一（MCP）：
调用 mcp_keenotes_add_note，参数：
{"content": "今天完成了 MCP 集成", "channel": "kiro"}

方法二（curl）：
curl -X POST http://localhost:1979/ \
  -H "Content-Type: application/json" \
  -d '{"content":"今天完成了 MCP 集成","channel":"kiro","created_at":"2025-02-08 15:30:00"}'
```

### 示例 2：会议纪要
```
用户："保存到 KeeNotes：Q1 路线图讨论 - 重点关注移动端功能"

方法一（MCP）：
调用 mcp_keenotes_add_note，参数：
{
  "content": "Q1 路线图讨论 - 重点关注移动端功能",
  "channel": "kiro"
}

方法二（httpie）：
http POST localhost:1979/ \
  content="Q1 路线图讨论 - 重点关注移动端功能" \
  channel="kiro" \
  created_at="2025-02-08 15:30:00"
```

### 示例 3：提醒事项
```
用户："帮我记录：明天下午 3 点开会"

使用任一方法保存："明天下午 3 点开会"
```

## 响应处理

**成功：**
- MCP：返回 `{"content": [{"type": "text", "text": "✓ Note saved successfully to KeeNotes (channel: kiro)"}], "isError": false}`
- HTTP：状态码 200

**失败：**
- MCP：在 content 中返回错误消息
- HTTP：状态码 500，响应体包含错误信息

始终告知用户笔记是否保存成功或是否出现错误。

## 故障排查

如果保存失败：
1. 检查 KeeNotes 应用是否正在运行
2. 确认 import server 端口（默认 1979，可在 Settings → Data Import 中查看）
3. 如果使用 MCP，检查 MCP server 是否已启用（Settings → AI）
4. 尝试直接使用 HTTP API 方法诊断连接问题

## 技术细节

- Import Server 端点：`http://localhost:1979/`（默认）
- MCP Server 端点：`http://localhost:1999/mcp`（默认）
- 协议：HTTP POST with JSON
- 加密：如果 KeeNotes 中启用了 E2EE，内容会自动加密
- 不要预先加密内容，除非用户明确要求
- 时间戳格式：`YYYY-MM-DD HH:mm:ss`（如 "2025-02-08 15:30:00"）
