# 修复 Pending Notes UI 风格一致性

## 问题描述
三端的 pending notes 列表 UI 没有复用现有的 note card 组件，导致风格与 Review Notes 等页面不一致。
Desktop 端还存在 theme 切换不响应的问题（硬编码颜色）。

---

## Phase 1: Desktop (JavaFX)

### 1.1 Pending Banner — theme 响应
- [x] `createPendingBanner()` 中的 banner 背景色、文字颜色使用 CSS class 或监听 ThemeService 动态切换
- [x] 返回按钮使用现有 `.back-button` CSS class
- [x] 标题使用现有 `.search-pane-title` CSS class

### 1.2 Pending Notes 列表 — 复用 NoteCardView
- [x] `showPendingNotesList()` 中删除自定义的 `createPendingNoteCard()`
- [x] 将 `PendingNoteData` 转换为 `NoteData`，直接使用 `NoteCardView` 渲染每条 pending note
- [x] 列表容器使用与 `NotesDisplayPanel` 一致的 ScrollPane + VBox 结构和 CSS class

### 1.3 验证
- [x] mvn compile 通过
- [ ] Dark theme 下 banner 和列表风格与 Review Notes 一致
- [ ] Light theme 下 banner 和列表风格与 Review Notes 一致

---

## Phase 2: iOS (SwiftUI)

### 2.1 Pending Banner — 使用系统语义色
- [x] banner 背景色从硬编码 `Color(red:...)` 改为系统语义色（如 `Color(.systemOrange).opacity(0.15)`）
- [x] 文字颜色使用 `.orange` 或语义色，确保 dark/light mode 自适应

### 2.2 Pending Notes 列表 — 复用 NoteRow
- [x] `PendingNotesListView` 中将自定义卡片替换为复用 `NoteRow` 组件
- [x] 将 `PendingNote` 转换为 `Note` 传入 `NoteRow`

### 2.3 验证
- [x] getDiagnostics 无错误
- [ ] Dark/Light mode 下风格与 ReviewView 一致

---

## Phase 3: Android (Kotlin)

### 3.1 Pending Banner — 使用 theme 资源色
- [x] `fragment_note.xml` 中 banner 的硬编码颜色改为引用 theme/color 资源
- [x] `bg_pending_banner.xml` 背景色使用 theme 资源

### 3.2 Pending Notes 列表 — 直接复用 NotesAdapter + item_note.xml
- [x] 删除自定义的 `PendingNotesAdapter.kt` 和 `item_pending_note.xml`
- [x] `PendingNote` 添加 `toNote()` 转换方法
- [x] `PendingNotesFragment` 直接使用 `NotesAdapter`，通过 `map { it.toNote() }` 转换数据
- [x] 点击复制、长按选择等交互自动继承自 `NotesAdapter`
- [x] `PendingNotesFragment` 的 toolbar 样式与其他 Fragment 保持一致

### 3.3 验证
- [x] getDiagnostics 无错误
