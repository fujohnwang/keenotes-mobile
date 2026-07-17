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
