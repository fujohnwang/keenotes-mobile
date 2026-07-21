## JavaFX Keep it 卡死修复记录

- 根因定位在 FX 线程的 optimistic note resolve 阶段：`noteItems.set()` 触发 `NoteListCell.updateItem()`，进而走 `NoteCardView.update()` 的 `TextArea.setText()` 和同步 layout 刷新，长期驻留后容易进入布局反馈循环。
- 修复取舍：确认远端 note 时只走 lightweight metadata update，更新 id/date/channel，不重新 setText；内容本身不变，真实数据会在后续 reload 中自然对齐。
- 同时把 `NoteCardView` 的 layout refresh 合并到下一次 FX pulse，并让边框 `AnimationTimer` 跑满后停止，降低长期驻留的 FX 线程压力。

## NoteCard 复制区域重构记录

- `NoteCardView` 的 click-to-copy 只保留在正文 `TextArea` 区域，header 行不再触发 copy，方便后续接入更多操作元素。
- Copied toast 挂在 `NoteCardView` 根 `StackPane` 上，定位到整张卡片中心；整卡 hover 保留，但 cursor 只在正文区域显示 hand。

## NoteCard 分享海报/视频记录

- `NoteCardView` header 右侧新增 share 入口，打开组件级分享 Dialog；所有复用 `NoteCardView` 的列表都会继承该入口。
- 桌面端海报复用 iOS 的梅兰竹菊水墨素材和 BGM 资源，渲染逻辑用 Java2D 实现，保持 9:16 纸张、动态字号和 footer 信息。
- 视频导出先依赖本机 `ffmpeg`，不引入内置编码器；启动 Dialog 时自动检测，找不到就禁用“保存视频”并显示简短提示。
- 分享 Dialog 使用隐藏的 `CANCEL_CLOSE` ButtonType 保留系统关闭行为；可见的“关闭”按钮只是自定义 toolbar 入口。
- 默认保存名为 `keenotes-{note.id}-{yyyy-MM-dd}-{HHmmss}`，其中时间戳取打开保存面板时的本地时间。
- 图片海报保存成功后会同时复制同一张图片到系统剪切板；视频保存不写剪切板。
- Java2D 海报正文/footer 显式加载 bundled `MiSans-Regular.ttf`，避免 logical font fallback 导致中英文标点 glyph 丢失。

## Android 海报/视频分享记录

- Android 端采用 Media3 Transformer 导出视频，不内置 ffmpeg；为减少 scoped storage 兼容分支，`minSdk` 提到 29。
- 视频只保存到相册；海报支持保存到相册和系统分享，分享临时文件通过 FileProvider 暴露 content uri。
- 预览打开/切换背景时不再显示水墨素材名称；关闭入口使用 X 图标，避免和缩小图标混淆。

## 开源前敏感历史清理记录

- 2026-07-19：用 `git filter-branch` 删除误入历史的 `src/main/resources/fonts/NotoSansSC-Regular.ttf`，并将 `todos/overall_performance_optimization.md` 里的真实运行日志替换为 `[REDACTED]`。
- 本机没有 `git filter-repo`，所以选择 `filter-branch`；清理前完整备份在 `/private/tmp/keenotes-mobile-before-sensitive-history-rewrite.bundle`，这个 bundle 仍包含旧敏感历史，不要上传。

## 修订为新笔记回填记录

- 最小化实现只在客户端 UI 层加 revise 入口：普通 note 卡片/放大视图把原文回填到输入区，旧 note 不变，发送仍走现有新增 note 链路。
- 三端在输入区已有草稿时都要求确认覆盖；pending notes 不显示 revise 入口，避免和待发送队列语义混淆。
- 桌面端覆盖确认从原生 `Alert` 改为无标题栏自定义 Dialog，沿用 KeeNotes 的主题色、圆角和主/次按钮层级；行为仍只是确认是否覆盖输入区草稿。
- 桌面端 Dialog 的图标 badge 采用 top 对齐，而不是拉满文案高度；这样视觉重量更轻，避免左侧形成不必要的栏位。
- Android 端把 Note 输入草稿提升到 `MainActivity` 的非持久化 UI 状态，并通过 `savedInstanceState` 保住同一次界面会话；修复 Fragment 重建导致滑动/点击切 tab 后草稿丢失，以及 revise 回填时误判输入框为空的问题。
- Android 端 revise 的覆盖确认改为在来源页原地弹出；确认后才跳转输入页，取消不产生导航副作用，`NoteFragment` 仍保留兜底确认逻辑。

## request_id 客户端接入记录

- 新发送链路先生成 `PreparedNote`，一次性完成加密、UTC 时间戳和 `request_id`，发送失败或离线时把同一份 encrypted payload 存入 pending，重试不再重新加密。
- 本地旧 pending row 没有 `encryptedContent/requestId` 时继续走旧发送逻辑；这是升级兼容，不影响旧客户端，也不改远端旧数据。
- pending 表新增列都是 nullable；iOS/JavaFX 运行时先查列再 `ALTER TABLE`，Android Room 4->5 migration 也加了 `PRAGMA table_info` guard，避免重复加列失败。
- Android 编译环境差异较大，这轮只做源码改动，不在 Codex 环境跑 Android 编译验证。
- JavaFX 端 HTTP/pending retry 出现网络失败时会主动标记 WebSocket suspect 并重连；`onFailure` 也会下发 disconnected，避免断网后 Sync Channel 长时间假绿。
- iOS/Android 端也补了同类 suspect reconnect：HTTP POST 或 pending retry 网络失败后，主动取消当前 WebSocket、置 disconnected，并走既有重连后 pending 自动重试链路。
