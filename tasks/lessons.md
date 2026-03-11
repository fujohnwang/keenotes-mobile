# Lessons Learned

## UI 风格一致性

**问题**: 新增 UI 组件时使用硬编码颜色和自定义卡片，没有复用现有组件，导致风格不一致且不响应 theme 切换。

**规则**:
1. 新增列表类 UI 时，必须先检查现有的列表组件（Desktop: `NoteCardView`/`NotesDisplayPanel`，iOS: `NoteRow`，Android: `NotesAdapter`+`item_note.xml`），优先复用，不要新建 Adapter 和 layout
2. 颜色不允许硬编码 — Desktop 用 CSS class + theme 变量，iOS 用系统语义色（`Color.orange` 而非 `Color(red:...)`），Android 用 `@color/` 资源引用
3. 新增组件前先问自己：「这个组件在 Review Notes 页面是什么样的？我的实现能不能跟它保持一致？」
4. 如果需要数据类型转换来复用组件（如 `PendingNote → Note`），添加 `toNote()` 转换方法，不要为了省事自己写一套新 UI
5. 复用现有组件意味着交互行为（点击复制、长按选择等）也自动继承，不需要重新实现
