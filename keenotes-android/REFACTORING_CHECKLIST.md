# Android 版重构 Checklist

## 目标
参考 iOS 版的 UI 设计和排版，重构 Android 版界面，保持暗色调风格不变，底层功能复用。

## Phase 1: 底部导航改造 (BottomNavigationView)

- [ ] 1.1 修改 `activity_main.xml`
  - 移除顶部 headerLayout（搜索框 + Review/Settings 按钮）
  - 移除 searchOverlay 相关组件
  - 添加 BottomNavigationView（3个导航项：Note、Review、Settings）
  - 调整布局：nav_host_fragment 在中间，statusBar 在底部，BottomNavigationView 在 statusBar 上方

- [ ] 1.2 创建底部导航菜单资源文件
  - 创建 `res/menu/bottom_nav_menu.xml`
  - 配置三个菜单项：Note、Review、Settings
  - 使用现有图标资源

- [ ] 1.3 更新 `MainActivity.kt`
  - 移除 setupSearchOverlay() 和 setupSearchField() 相关代码
  - 移除搜索相关的成员变量（searchAdapter, searchJob）
  - 实现 BottomNavigationView 的选项切换逻辑
  - 更新 setupNavigation() 方法，使用 BottomNavigationView 控制导航
  - 移除 headerLayout 的显示/隐藏逻辑
  - 保持 setupStatusBar() 和 WebSocket 连接逻辑不变

- [ ] 1.4 更新 `nav_graph.xml`
  - 移除 noteFragment 到 reviewFragment 和 settingsFragment 的 action
  - 移除 reviewFragment 到 settingsFragment 的 action
  - 三个 fragment 平级，通过 BottomNavigationView 切换

- [ ] 1.5 测试底部导航功能
  - 验证三个 tab 切换正常
  - 验证 statusBar 显示正常
  - 验证暗色调风格保持一致

## Phase 2: 创建独立搜索功能 (SearchFragment)

- [ ] 2.1 创建 `SearchFragment.kt`
  - 实现搜索输入框（自动聚焦）
  - 实现 500ms 防抖搜索
  - 复用现有数据库查询逻辑
  - 显示搜索结果列表（复用 NotesAdapter）
  - 显示加载状态和空状态

- [ ] 2.2 创建 `fragment_search.xml` 布局
  - 顶部：标题 "Search" + 返回按钮
  - 搜索输入框（带清除按钮）
  - Header row：notes count (左) + Sync Channel status (右)
  - 搜索结果列表（RecyclerView）
  - 加载和空状态提示

- [ ] 2.3 更新 `nav_graph.xml`
  - 添加 searchFragment
  - 添加 noteFragment 到 searchFragment 的 action

- [ ] 2.4 更新 `NoteFragment.kt`
  - 添加 Toolbar 配置
  - 在右上角添加搜索图标按钮
  - 实现导航到 SearchFragment

- [ ] 2.5 更新 `fragment_note.xml`
  - 添加 Toolbar 支持（用于显示搜索图标）

- [ ] 2.6 测试搜索功能
  - 验证搜索入口可点击
  - 验证搜索输入自动聚焦
  - 验证搜索结果正确显示
  - 验证返回按钮功能正常
  - 验证 Sync Channel 状态显示

## Phase 3: Note 界面融入式设计 (Embedded Design)

- [ ] 3.1 重新设计 `fragment_note.xml`
  - 创建统一的输入容器（FrameLayout 或 ConstraintLayout）
  - 输入框：移除背景填充，只保留外边框
  - 底部行：Send Channel status (左) + Send button (右)
  - 添加居中的 "Sending..." 加载提示（ProgressBar + TextView）
  - 移除独立的 Save 按钮和 Status Text
  - 保留 Echo Card

- [ ] 3.2 更新 `NoteFragment.kt`
  - 实现 Send Channel 状态显示逻辑
  - 监听网络连接状态（参考 iOS NetworkMonitor）
  - 监听配置状态
  - 更新发送逻辑，显示居中加载提示
  - 移除旧的 statusText 更新逻辑

- [ ] 3.3 创建网络监听工具类（可选）
  - 创建 `NetworkMonitor.kt`（参考 iOS 实现）
  - 使用 ConnectivityManager 监听网络状态

- [ ] 3.4 更新 Send Channel 状态显示
  - 状态指示器（圆点）+ 文本
  - 未配置：橙色 "Not Configured"
  - 已配置 + 有网络：绿色 "✓"
  - 已配置 + 无网络：红色 "No Network"

- [ ] 3.5 测试 Note 界面
  - 验证融入式设计外观正确
  - 验证 Send Channel 状态显示正确
  - 验证发送功能正常
  - 验证居中加载提示显示正常
  - 验证暗色调风格保持一致

## Phase 4: Review 界面调整

- [ ] 4.1 更新 `fragment_review.xml`
  - 移除返回按钮（btnBack）
  - 更新标题为 "KeeNotes Review"
  - 在 period selector 下方添加 header row
  - Header row 包含：notes count with period info (左) + Sync Channel status (右)
  - 保持 period selector 和 notes list 不变

- [ ] 4.2 更新 `ReviewFragment.kt`
  - 移除 setupHeader() 中的返回按钮逻辑
  - 添加 Sync Channel 状态显示逻辑
  - 更新 notes count 显示，包含 period 信息
  - 格式："{count} note(s) - Last {period}" 或 "{count} note(s) - All"
  - 监听 WebSocket 连接状态更新 Sync Channel

- [ ] 4.3 添加 Sync Channel 状态组件
  - 在 fragment_review.xml 中添加 Sync Channel 状态显示
  - 状态指示器（圆点）+ "Sync Channel:" + 状态文本
  - Connected: 绿色 "✓"
  - Connecting: 橙色 "..."
  - Disconnected: 灰色 "✗"

- [ ] 4.4 测试 Review 界面
  - 验证标题显示正确
  - 验证 notes count 包含 period 信息
  - 验证 Sync Channel 状态显示正确
  - 验证 period 切换功能正常
  - 验证暗色调风格保持一致

## Phase 5: Settings 界面调整

- [ ] 5.1 更新 SettingsFragment 标题
  - 修改标题为 "KeeNotes Settings"
  - 可能需要更新 `strings.xml` 或直接在代码中设置

- [ ] 5.2 测试 Settings 界面
  - 验证标题显示正确
  - 验证功能正常

## Phase 6: 清理和优化

- [ ] 6.1 清理 MainActivity
  - 移除所有搜索相关的代码
  - 移除 headerLayout 相关的代码
  - 确保代码整洁

- [ ] 6.2 更新 statusBar 显示逻辑
  - 确保 statusBar 在所有界面都正确显示
  - Note 界面：显示 statusBar
  - Review 界面：显示 statusBar
  - Settings 界面：隐藏 statusBar（保持原有逻辑）
  - Search 界面：显示 statusBar

- [ ] 6.3 测试完整导航流程
  - Note → Review → Settings → Note
  - Note → Search → Note
  - 验证所有界面切换流畅

- [ ] 6.4 测试所有功能
  - Note 输入和发送
  - Search 搜索
  - Review 时间段切换
  - Settings 配置
  - WebSocket 同步
  - 状态显示

- [ ] 6.5 验证暗色调风格
  - 检查所有界面颜色一致
  - 检查所有组件样式统一
  - 确保没有引入新的颜色或样式

## Phase 7: 最终测试

- [ ] 7.1 功能测试
  - 所有导航功能正常
  - 所有输入功能正常
  - 所有显示功能正常

- [ ] 7.2 UI 测试
  - 所有界面布局正确
  - 所有状态显示正确
  - 暗色调风格一致

- [ ] 7.3 性能测试
  - 界面切换流畅
  - 搜索响应及时
  - 无内存泄漏

## 注意事项

1. **保持暗色调风格不变**：所有 UI 改动都要使用现有的颜色资源
2. **复用现有逻辑**：数据库、网络、加密等底层功能完全复用
3. **参考 iOS 设计**：UI 布局和交互参考 iOS 版本
4. **逐步实施**：按 Phase 顺序执行，每个 Phase 完成后测试
5. **代码整洁**：移除不再使用的代码和资源

## 参考文件

- iOS 参考：
  - `keenotes-ios/KeeNotes/Views/NoteView.swift` - 融入式设计
  - `keenotes-ios/KeeNotes/Views/ReviewView.swift` - Review 布局
  - `keenotes-ios/KeeNotes/Views/SearchView.swift` - 搜索功能

- Android 当前实现：
  - `keenotes-android/app/src/main/java/cn/keevol/keenotes/ui/MainActivity.kt`
  - `keenotes-android/app/src/main/res/layout/activity_main.xml`
  - `keenotes-android/app/src/main/java/cn/keevol/keenotes/ui/note/NoteFragment.kt`
  - `keenotes-android/app/src/main/java/cn/keevol/keenotes/ui/review/ReviewFragment.kt`
