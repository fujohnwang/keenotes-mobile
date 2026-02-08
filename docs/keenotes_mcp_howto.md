# KeeNotes MCP Server 使用指南

## 概述

KeeNotes 桌面版内置了 MCP (Model Context Protocol) Server 功能，允许 AI 助手（如 Claude Desktop、Kiro 等）直接向 KeeNotes 添加笔记。

## 功能特性

- **自动启动**：随 KeeNotes 桌面应用启动
- **Streamable HTTP 传输**：使用现代 HTTP 协议，无需额外进程
- **E2EE 支持**：支持端到端加密笔记
- **轻量级**：纯 Java 实现，无重型框架依赖

## 配置

### 1. KeeNotes 端配置

在 KeeNotes 桌面应用中：

1. 打开 **Settings** → **Data Import** 页面
2. 找到 **MCP Server (Model Context Protocol)** 部分
3. 默认端口为 `1999`，可根据需要修改
4. 修改端口后会自动重启 MCP Server

### 2. AI 客户端配置

#### Claude Desktop

编辑 Claude Desktop 的配置文件：

**macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`  
**Windows**: `%APPDATA%\Claude\claude_desktop_config.json`

添加以下配置：

```json
{
  "mcpServers": {
    "keenotes": {
      "url": "http://localhost:1999/mcp",
      "transport": "http"
    }
  }
}
```

#### Kiro

在 Kiro 的 MCP 配置中添加：

```json
{
  "mcpServers": {
    "keenotes": {
      "url": "http://localhost:1999/mcp"
    }
  }
}
```

## 可用工具

### add_note

添加笔记到 KeeNotes。

**参数：**

- `content` (string, required): 笔记内容
- `channel` (string, optional): 来源渠道，默认为 `"ai-assistant"`
  - 建议值：`"claude"`, `"kiro"`, `"ai-assistant"` 等
- `encrypted` (boolean, optional): 内容是否已加密，默认为 `false`
  - `false`: 内容为明文，将使用 E2EE 加密后发送
  - `true`: 内容已加密，直接发送

**返回：**

- 成功：`✓ Note saved successfully to KeeNotes (channel: xxx)`
- 失败：`✗ Failed to save note: error message`

## 使用示例

### 在 Claude Desktop 中使用

配置完成后，重启 Claude Desktop，然后可以直接对话：

```
你：帮我记录一个笔记：今天学习了 MCP 协议的实现原理

Claude：好的，我已经帮你保存到 KeeNotes 了。
✓ Note saved successfully to KeeNotes (channel: claude)
```

### 在 Kiro 中使用

```
你：记一下：明天下午 3 点开会

Kiro：已保存到 KeeNotes。
✓ Note saved successfully to KeeNotes (channel: kiro)
```

## 技术细节

### 协议

- **传输协议**: Streamable HTTP (JSON-RPC 2.0 over HTTP)
- **端点**: `http://localhost:{port}/mcp`
- **默认端口**: 1999

### 支持的 MCP 方法

- `initialize`: 初始化连接，返回服务器能力
- `tools/list`: 列出所有可用工具
- `tools/call`: 调用指定工具

### 安全性

- 仅监听 `localhost`，不对外网开放
- 复用 KeeNotes 的 E2EE 加密配置
- 需要 KeeNotes 已配置 endpoint、token 和加密密码

## 故障排查

### MCP Server 未启动

1. 检查 KeeNotes 是否正常运行
2. 查看 KeeNotes 控制台输出，确认 MCP Server 启动日志
3. 检查端口是否被占用

### AI 客户端无法连接

1. 确认配置文件格式正确（JSON 语法）
2. 确认端口号与 KeeNotes 配置一致
3. 重启 AI 客户端应用
4. 检查防火墙设置

### 笔记保存失败

1. 确认 KeeNotes 已完成基本配置（endpoint、token、加密密码）
2. 检查网络连接
3. 查看 KeeNotes 控制台错误信息

## 扩展开发

未来可以添加更多工具：

- `search_notes`: 搜索笔记
- `get_recent_notes`: 获取最近笔记
- `get_note_stats`: 获取笔记统计信息

如有需求，可以在 `src/main/java/cn/keevol/keenotes/mcp/` 目录下添加新的 Tool 类。

## 相关资源

- [Model Context Protocol 官方文档](https://modelcontextprotocol.io/)
- [LangChain4j MCP 文档](https://docs.langchain4j.dev/tutorials/mcp)
- [KeeNotes 项目主页](https://keenotes.afoo.me)



## Q&A

### 当ai客户端调用我们的mcp的时候，mcp的触发词是“记”？

不是的。MCP (Model Context Protocol) 不是基于"触发词"工作的。

当 AI 客户端（如 Claude Desktop、Kiro）配置了 KeeNotes 的 MCP server 后：

1. **AI 自主判断** - AI 会根据用户的意图自主决定是否调用 `add_note` 工具
2. **自然语言理解** - 用户可以用任何方式表达，比如：
   - "帮我记录一个笔记：..."
   - "保存这个想法到 KeeNotes"
   - "记一下：..."
   - "Add a note: ..."
   - 或者任何其他表达记录笔记意图的说法

3. **工具描述驱动** - AI 是通过读取 `add_note` 工具的描述来理解何时应该使用它的

让我看看当前的工具描述：

```
"Add a new note to KeeNotes. The note content will be saved to the configured KeeNotes backend."
```

这个描述比较简单。AI 会根据这个描述来判断何时调用这个工具。用户可以用任何自然语言表达记录笔记的意图，AI 会自主决定是否调用 `add_note` 工具。

**没有固定的触发词**，AI 是通过理解用户意图来决定的。比如：
- "记一下：今天学习了 MCP"
- "帮我保存这个想法"
- "Add a note about the meeting"
- "把这个记到 KeeNotes"

都可能触发 AI 调用 `add_note` 工具。