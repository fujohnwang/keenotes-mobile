# KeeNotes JavaFX Desktop Performance and Lifecycle Diagnostic Report

## 1. Executive Summary

本报告针对 JavaFX 桌面版 KeeNotes 的长期运行性能、资源生命周期和潜在泄漏点做静态诊断。诊断重点是 UI listener 生命周期、后台任务模型、网络/数据库资源关闭语义、列表渲染成本，以及这些问题如何导致长时间运行后的 UI 状态不一致。

核心结论：

- 主要风险不是单个查询或单个算法慢，而是多个长期对象之间的生命周期边界不清。
- 多个 UI 组件把 listener 注册到单例服务或单例 property 后没有移除，容易导致 UI 节点被单例长期持有。
- 多处直接 `new Thread(...)` 或使用 `CompletableFuture` 默认 common pool，任务不可统一取消、不可统一关闭，也难以观测。
- Notes 列表已经从大 `VBox` 改成 `ListView`，方向正确，但单个 `NoteCardView` 仍偏重，且每个 card 自己监听全局 theme/font property，长期看有泄漏和刷新放大的风险。
- Data import 的“顺序发送”需求是合理语义，不建议改成并发发送；当前问题是执行器和取消/关闭生命周期，而不是发送效率。

## 2. Scope

本次诊断覆盖以下 JavaFX 桌面端相关模块：

- `src/main/java/cn/keevol/keenotes/mobilefx/Main.java`
- `src/main/java/cn/keevol/keenotes/mobilefx/DesktopMainView.java`
- `src/main/java/cn/keevol/keenotes/mobilefx/MainContentArea.java`
- `src/main/java/cn/keevol/keenotes/mobilefx/NotesDisplayPanel.java`
- `src/main/java/cn/keevol/keenotes/mobilefx/NoteCardView.java`
- `src/main/java/cn/keevol/keenotes/mobilefx/SidebarView.java`
- `src/main/java/cn/keevol/keenotes/mobilefx/OverviewCard.java`
- `src/main/java/cn/keevol/keenotes/mobilefx/ServiceManager.java`
- `src/main/java/cn/keevol/keenotes/mobilefx/LocalCacheService.java`
- `src/main/java/cn/keevol/keenotes/mobilefx/WebSocketClientService.java`
- `src/main/java/cn/keevol/keenotes/mobilefx/ApiServiceV2.java`
- `src/main/java/cn/keevol/keenotes/mobilefx/DataImportService.java`
- `src/main/java/cn/keevol/keenotes/mobilefx/PendingNoteService.java`
- `src/main/java/cn/keevol/keenotes/utils/SimpleForwardServer.java`
- `src/main/java/cn/keevol/keenotes/mcp/SimpleMcpServer.java`

## 3. Findings

### P0/P1: UI Listener Lifecycle Leak

风险描述：

多个 UI 组件把 listener 注册到长期单例对象上，但没有保存 listener 引用，也没有在组件销毁时移除。JavaFX listener lambda 会捕获 `this`，单例对象继续持有 listener 时，整个 UI 组件树可能无法被 GC。长时间运行或重建 UI、切换账号、切换设置后，重复回调还会放大 JavaFX Application Thread 压力。

证据：

- `NotesDisplayPanel` 注册 theme listener：`src/main/java/cn/keevol/keenotes/mobilefx/NotesDisplayPanel.java:83`
- `NotesDisplayPanel` 注册字体大小和字体族 listener：`src/main/java/cn/keevol/keenotes/mobilefx/NotesDisplayPanel.java:120`
- `NotesDisplayPanel` 注册 WebSocket listener：`src/main/java/cn/keevol/keenotes/mobilefx/NotesDisplayPanel.java:154`
- `MainContentArea` 注册 account switch listener：`src/main/java/cn/keevol/keenotes/mobilefx/MainContentArea.java:82`
- `MainContentArea` 注册 ServiceManager listener：`src/main/java/cn/keevol/keenotes/mobilefx/MainContentArea.java:97`
- `MainContentArea` 注册 WebSocket listener：`src/main/java/cn/keevol/keenotes/mobilefx/MainContentArea.java:122`
- `MainContentArea` 注册 LocalCacheService change listener：`src/main/java/cn/keevol/keenotes/mobilefx/MainContentArea.java:206`
- `SidebarView` 注册 ServiceManager listener 和 LocalCacheService listener：`src/main/java/cn/keevol/keenotes/mobilefx/SidebarView.java:311`、`src/main/java/cn/keevol/keenotes/mobilefx/SidebarView.java:336`
- `OverviewCard` 注册 account switch listener 和 LocalCacheService listener：`src/main/java/cn/keevol/keenotes/mobilefx/OverviewCard.java:94`、`src/main/java/cn/keevol/keenotes/mobilefx/OverviewCard.java:116`

修复原则：

- 为 `DesktopMainView`、`MainContentArea`、`NotesDisplayPanel`、`SidebarView`、`OverviewCard` 引入显式 `dispose()`。
- 所有注册到单例对象的 listener 都必须保存为字段，并在 `dispose()` 中 remove。
- 对不容易管理生命周期的 UI listener，可考虑 `WeakChangeListener`，但弱 listener 不能代替清晰的 dispose 语义。
- `ServiceManager`、`WebSocketClientService`、`LocalCacheService` 可增加 debug-only listener count 诊断接口，用于压测时确认 listener 数量不会持续增长。

### P1: 字体快捷键与 Font Listener 的正确修复方式

风险描述：

`NotesDisplayPanel` 监听 `SettingsService.noteFontSizeProperty()` 和 `noteFontFamilyProperty()` 是合理需求，因为桌面版支持快捷键动态放大/缩小字体。问题不是 listener 本身，而是 listener 的生命周期没有被管理。

必须保留的语义：

- 当 `NotesDisplayPanel` 仍在界面中时，快捷键改变字体大小后，`listView.refresh()` 应继续触发。
- 只有 panel 被销毁、替换、窗口关闭、账号切换导致 UI 重建时，才应移除 listener。

推荐改法：

```java
private final ChangeListener<Number> noteFontSizeListener =
        (obs, oldVal, newVal) -> Platform.runLater(listView::refresh);

private final ChangeListener<String> noteFontFamilyListener =
        (obs, oldVal, newVal) -> Platform.runLater(listView::refresh);

public void dispose() {
    SettingsService settings = SettingsService.getInstance();
    settings.noteFontSizeProperty().removeListener(noteFontSizeListener);
    settings.noteFontFamilyProperty().removeListener(noteFontFamilyListener);
}
```

注意事项：

- 不要删除字体 listener。
- 不要破坏快捷键调字号功能。
- 更应优先减少 `NoteCardView` 每个 cell/card 自己监听全局字体和主题的做法，改为由 `NotesDisplayPanel` 统一刷新 ListView。

### P1: NoteCardView 单卡片成本和全局 Listener 放大

风险描述：

`ListView` 虚拟化方向正确，但 `NoteCardView` 本身比较重：每个 card 包含 `TextArea`、隐藏 `Text`、`Canvas`、`AnimationTimer`、多处 CSS/layout 操作，并且每个 card 都注册 theme/font 全局 listener。这会增加滚动、主题切换、字体变化时的压力，也可能让被回收的 cell/card 被单例 property 间接持有。

证据：

- `NoteCardView` 注册 theme listener：`src/main/java/cn/keevol/keenotes/mobilefx/NoteCardView.java:68`
- `NoteCardView` 注册字体大小 listener：`src/main/java/cn/keevol/keenotes/mobilefx/NoteCardView.java:73`
- `NoteCardView` 注册字体族 listener：`src/main/java/cn/keevol/keenotes/mobilefx/NoteCardView.java:78`
- 每个 card 使用 `TextArea` 展示内容：`src/main/java/cn/keevol/keenotes/mobilefx/NoteCardView.java:107`
- 宽度变化时调用 `applyCss()` 和 `layout()`：`src/main/java/cn/keevol/keenotes/mobilefx/NoteCardView.java:144`
- 每个 card 带 `Canvas` 和 `AnimationTimer`：`src/main/java/cn/keevol/keenotes/mobilefx/NoteCardView.java:46`、`src/main/java/cn/keevol/keenotes/mobilefx/NoteCardView.java:504`

修复原则：

- 优先把 theme/font 变化集中到 `NotesDisplayPanel` 层处理，必要时调用 `listView.refresh()`。
- `NoteCardView` 如仍需 listener，必须保存 listener 引用并提供 `dispose()`。
- `setupViewportWidthListener()`、`setupInternalTextListener()` 应保证幂等，避免 skin 重建时重复对同一个内部节点 add listener。
- 评估用更轻量的 `Text`/`TextFlow`/`Label` 替代常驻 `TextArea`，仅在需要选择文本时切换到可选择控件。

### P1: 后台任务模型分散且不可统一关闭

风险描述：

多个视图和服务直接 `new Thread(...)`，没有统一 executor、取消令牌、任务去重、shutdown 管理。长期运行时，搜索、Review 加载、分页加载、设置保存、状态清理等任务可能并发叠加。虽然大多是 daemon thread，但 daemon 只解决进程退出，不解决运行期资源治理。

证据：

- Review 加载直接开线程：`src/main/java/cn/keevol/keenotes/mobilefx/MainContentArea.java:399`
- Search 加载直接开线程：`src/main/java/cn/keevol/keenotes/mobilefx/MainContentArea.java:585`
- Recent notes 加载直接开线程：`src/main/java/cn/keevol/keenotes/mobilefx/MainContentArea.java:876`
- Notes 初始分页加载直接开线程：`src/main/java/cn/keevol/keenotes/mobilefx/NotesDisplayPanel.java:405`
- Notes 加载更多直接开线程：`src/main/java/cn/keevol/keenotes/mobilefx/NotesDisplayPanel.java:503`
- ServiceManager 初始化、连接、重试也直接开线程：`src/main/java/cn/keevol/keenotes/mobilefx/ServiceManager.java:100`、`src/main/java/cn/keevol/keenotes/mobilefx/ServiceManager.java:205`、`src/main/java/cn/keevol/keenotes/mobilefx/ServiceManager.java:350`

修复原则：

- 引入统一 `AppExecutors` 或服务级 executor：
  - `uiDbExecutor`: 单线程或小型固定线程池，处理 SQLite 读写以外的 UI 数据加载。
  - `networkExecutor`: 有界线程池，处理 HTTP 请求。
  - `importExecutor`: 单线程，保证导入顺序发送语义。
- 所有任务必须有取消机制。切换 view、重新搜索、切换账号、关闭窗口时应取消旧任务。
- JavaFX 推荐优先用 `Task`/`Service` 管理长任务状态，尤其是 UI 需要绑定 loading/error/progress 的场景。

### P1: CompletableFuture Common Pool 阻塞和生命周期风险

风险描述：

当前 `ApiServiceV2` 使用 `CompletableFuture.supplyAsync(...)` 默认 common pool。`DataImportService` 自己也在 common pool 里跑导入循环，然后每一条调用 `apiService.postNote(...)`，再用 `sendFuture.get()` 阻塞等待。这个模型能保持顺序发送，但资源生命周期不理想：阻塞 common pool 线程，任务不受 App 控制，关闭/取消时不好收敛。

重要澄清：

- Data import 的需求是顺序发送：必须等一条发送完成后再发送下一条。
- 不建议为了性能把导入改成并发发送。
- 这里的问题不是“效率低”，也不是确定性内存泄漏，而是 common pool 阻塞和缺少可关闭生命周期。

证据：

- `ApiServiceV2.postNote()` 使用默认 common pool：`src/main/java/cn/keevol/keenotes/mobilefx/ApiServiceV2.java:113`
- `ApiServiceV2.postNoteDirectly()` 使用默认 common pool：`src/main/java/cn/keevol/keenotes/mobilefx/ApiServiceV2.java:224`
- `DataImportService.importFile()` 使用默认 common pool：`src/main/java/cn/keevol/keenotes/mobilefx/DataImportService.java:131`
- 导入循环中阻塞等待每条发送完成：`src/main/java/cn/keevol/keenotes/mobilefx/DataImportService.java:191`
- `PendingNoteService.onNetworkRestored()` 也使用默认 common pool：`src/main/java/cn/keevol/keenotes/mobilefx/PendingNoteService.java:94`

推荐改法：

- 保留顺序发送语义。
- 给 `DataImportService` 一个 single-thread executor，导入循环在该 executor 中执行。
- 给 `ApiServiceV2` 提供同步版本，例如 `postNoteSync(...)` / `postNoteDirectlySync(...)`，导入服务在自己的单线程中顺序调用同步 API。
- 或者保留 async API，但 `ApiServiceV2` 必须注入专用 `networkExecutor`，不要用 common pool。
- `DataImportService` 应提供 `cancel()` 和 `close()`，关闭 executor，并确保 UI callback 不会在视图销毁后继续持有 UI。

### P1: 本地 Import Server Executor 泄漏，且主程序 Stop 未关闭

风险描述：

本地 import server 使用 `HttpServer`，并设置了 `Executors.newSingleThreadExecutor()`，但没有保存 executor 引用，也没有 shutdown。`stop()` 只调用 `server.stop(...)`。此外 `Main.stop()` 当前只停止 MCP server 和 `ServiceManager`，没有停止 `SimpleForwardServer`。

证据：

- 启动本地 import server：`src/main/java/cn/keevol/keenotes/mobilefx/Main.java:74`
- `Main.stop()` 没有调用 `SimpleForwardServer.stop()`：`src/main/java/cn/keevol/keenotes/mobilefx/Main.java:172`
- `SimpleForwardServer` 创建 executor 但未保存：`src/main/java/cn/keevol/keenotes/utils/SimpleForwardServer.java:37`
- `SimpleForwardServer.stop()` 只 stop server：`src/main/java/cn/keevol/keenotes/utils/SimpleForwardServer.java:57`
- 设置页修改端口会 restart：`src/main/java/cn/keevol/keenotes/mobilefx/DataImportView.java:115`

推荐改法：

- 参考 `SimpleMcpServer` 的做法，保存 `ExecutorService` 到 `AtomicReference`。
- `SimpleForwardServer.stop()` 中先 `server.stop(...)`，再 `executor.shutdown()`，超时后 `shutdownNow()`。
- `Main.stop()` 增加 `SimpleForwardServer.stop()`。

### P1: ApiServiceV2 OkHttpClient 没有 Close 路径

风险描述：

`ApiServiceV2` 持有长期 `OkHttpClient`，但没有 `close()`。`ServiceManager.shutdown()` 中也明确没有关闭 API service 的资源。这会导致 dispatcher executor、connection pool 等资源无法由应用生命周期统一收敛。

证据：

- `ApiServiceV2` 持有 `OkHttpClient`：`src/main/java/cn/keevol/keenotes/mobilefx/ApiServiceV2.java:22`
- `ApiServiceV2` 构造 client：`src/main/java/cn/keevol/keenotes/mobilefx/ApiServiceV2.java:27`
- `ServiceManager.shutdown()` 未关闭 API service：`src/main/java/cn/keevol/keenotes/mobilefx/ServiceManager.java:432`

推荐改法：

- `ApiServiceV2` 实现 `AutoCloseable` 或提供 `close()`。
- `close()` 中调用：
  - `httpClient.dispatcher().executorService().shutdown()`
  - `httpClient.connectionPool().evictAll()`
  - 如有 cache，关闭 cache
- `ServiceManager.shutdown()` 和必要的 reinitialize 路径中调用 `apiService.close()`。

### P2: LocalCacheService Close/Reopen 语义不清

风险描述：

`LocalCacheService.close()` 只关闭 connection，不清理 `initialized`，不置空 connection，也没有 closed flag。后续如果某处继续调用 `ensureInitialized()`，它会执行健康检查并可能重新打开数据库。这对正常退出以外的生命周期语义比较危险：上层以为服务已关闭，底层可能又恢复连接。

证据：

- `ensureInitialized()` 会继续走 `ensureConnectionHealthy()`：`src/main/java/cn/keevol/keenotes/mobilefx/LocalCacheService.java:152`
- 连接异常时会自动恢复：`src/main/java/cn/keevol/keenotes/mobilefx/LocalCacheService.java:270`
- `close()` 只关闭 connection：`src/main/java/cn/keevol/keenotes/mobilefx/LocalCacheService.java:776`

推荐改法：

- 增加明确状态：`RUNNING`、`CLOSED`、可选 `RECOVERING`。
- `close()` 后默认禁止自动 reopen，除非 `ServiceManager` 明确调用 `initialize()` 或 `reopen()`。
- `close()` 应置空 connection，并记录 closed 状态。
- DB 自动恢复逻辑应只在服务 running 状态下启用。

### P2: SQL 异常和空结果仍有混淆

风险描述：

部分查询 catch 后返回默认值或空列表，导致上层无法区分“真的没有数据”和“数据库读取失败”。这类问题容易表现为 header/count 仍正常，但列表区域空白或显示 empty state。

证据：

- search 查询失败返回空列表：`src/main/java/cn/keevol/keenotes/mobilefx/LocalCacheService.java:440`
- review 查询失败返回空列表：`src/main/java/cn/keevol/keenotes/mobilefx/LocalCacheService.java:467`
- getAllNotes 查询失败返回空列表：`src/main/java/cn/keevol/keenotes/mobilefx/LocalCacheService.java:548`
- getLastSyncId 查询失败返回 `-1`：`src/main/java/cn/keevol/keenotes/mobilefx/LocalCacheService.java:578`

推荐改法：

- 查询 API 不应静默吞异常。
- 对 UI 使用的查询，建议返回 `Result<T>` 或抛出异常，让 UI 显示明确错误。
- 允许空列表的地方必须能证明查询成功。

### P2: Notes 列表刷新先清空旧数据，失败时容易视觉断层

风险描述：

`showLoading()` 和 `displayNotesWithPagination()` 会先清空 `noteItems`。如果后续 DB 读取失败、任务被旧 generation 丢弃、或返回空结果，用户会看到列表突然消失。当前代码已有一次 retry 和 error 兜底，但更稳妥的模型是“成功加载新数据后再替换旧 UI”。

证据：

- `displayNotesWithPagination()` 先 clear：`src/main/java/cn/keevol/keenotes/mobilefx/NotesDisplayPanel.java:366`
- `showLoading()` 先 clear 并隐藏列表：`src/main/java/cn/keevol/keenotes/mobilefx/NotesDisplayPanel.java:665`
- 初始加载遇到 count > 0 但 notes 为空时已有 retry/error：`src/main/java/cn/keevol/keenotes/mobilefx/NotesDisplayPanel.java:432`

推荐改法：

- 首屏 reload 时，旧列表继续保留，加载状态以 overlay 或 header 状态显示。
- 新数据成功加载后，一次性替换 `noteItems`。
- 失败时保留旧列表，并显示非阻塞错误提示。

## 4. Suggested Remediation Plan

### Phase 1: Lifecycle Ownership

- 为所有长期 UI 组件增加 `dispose()`。
- 保存所有注册到单例对象的 listener 引用。
- 在窗口关闭、账号切换、视图替换时调用 dispose。
- 给 `ServiceManager`、`WebSocketClientService`、`LocalCacheService` 增加 debug listener count。

### Phase 2: Executor and Task Governance

- 建立统一 executor 管理：
  - UI 数据加载 executor
  - 网络 executor
  - 导入 single-thread executor
  - pending retry executor
- 替换散落的 `new Thread(...)`。
- 替换默认 common pool 的阻塞使用。
- 所有任务支持取消和 shutdown。

### Phase 3: Resource Closing

- `ApiServiceV2` 增加 `close()`。
- `SimpleForwardServer` 保存并关闭 executor。
- `Main.stop()` 补齐 `SimpleForwardServer.stop()`。
- `LocalCacheService.close()` 明确 closed 状态，不允许被无意 reopen。

### Phase 4: Notes List Rendering

- 保留 `ListView` 虚拟化。
- 减少 `NoteCardView` 中的全局 listener。
- 评估替换常驻 `TextArea`。
- 首屏 reload 改成成功后替换，失败保留旧列表。

## 5. Verification Plan

建议修复后做以下验证：

- 运行 30 分钟、2 小时、8 小时，记录以下数量是否稳定：
  - `LocalCacheService.changeListeners.size()`
  - `WebSocketClientService.listeners.size()`
  - `ServiceManager.listeners.size()`
  - live `NoteCardView` 数量
  - live `NotesDisplayPanel` 数量
  - live thread 数量和线程名分布
- 压测场景：
  - 切换主题 50 次
  - 快捷键放大/缩小字体 100 次
  - 搜索输入连续变化 100 次
  - Review/Note/Search 页面来回切换 100 次
  - 账号或 token 设置保存/重连 20 次
  - 本地 import server 端口修改 20 次
  - MCP server 端口修改 20 次
  - 导入 1000 条 notes，确认仍严格顺序发送
- 工具建议：
  - Java Flight Recorder
  - VisualVM
  - `jcmd <pid> Thread.print`
  - `jcmd <pid> GC.class_histogram`

## 6. Acceptance Criteria

修复完成后应满足：

- 字体快捷键动态缩放功能保持可用。
- Data import 仍严格顺序发送，一条完成后才发送下一条。
- 关闭窗口后无 WebSocket、HTTP server、OkHttp dispatcher、import executor、pending retry executor 残留。
- 多次切换主题/字体/页面后 listener count 不持续增长。
- 多次账号切换或设置重连后 WebSocket listener 不重复。
- 列表 reload 失败不会把旧 notes 永久清空。
- 长时间运行后 Note 页面不再出现 count 正常但列表空白的状态。
