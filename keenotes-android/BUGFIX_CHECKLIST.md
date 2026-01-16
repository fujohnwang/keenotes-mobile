# Android Bug Fix Checklist

## 问题 1: Review 界面新数据到来时没有显示最新 note
- [x] 移除 submitList(null) 避免全量刷新
- [x] 从 collectLatest 改为 collect
- [x] 使用 toMutableList() 创建新实例
- [x] 依赖 DiffUtil 自动处理增量更新
- [x] 只在进入界面和切换 period 时全量刷新
- [ ] 测试：验证新 note 是否自动显示（需要用户测试）

## 问题 2: 移除 Review 界面主区域上方的 Loading/Empty 文本
- [x] 移除 `loadingText` 组件
- [x] 移除 `emptyText` 组件
- [x] 更新 ReviewFragment.kt 逻辑
- [x] 简化空状态处理 ✅

## 问题 3: Dock 里的 icon 单独高亮问题
- [x] 修复 ic_note.xml 硬编码颜色 → 使用 @android:color/white
- [x] 修复 ic_settings.xml 硬编码颜色 → 使用 @android:color/white
- [x] 修复 ic_review.xml 硬编码颜色 → 使用 @android:color/white
- [x] 创建 bottom_nav_item_color.xml 颜色选择器
- [x] 在 MainActivity 中强制设置 itemIconTintList 覆盖 Material 3 默认行为
- [x] 禁用 Material 3 的 active indicator
- [ ] 测试：验证 icon 是否只使用整体背景高亮（需要用户测试）

## 问题 4: Settings 初次设置后点击 Save 退出到桌面
- [x] 简化导航逻辑，统一使用 view.postDelayed
- [x] 移除多个 Handler 实例，避免内存泄漏
- [x] 使用 MainActivity.navigateToNote() 触发底部导航切换
- [ ] 测试：验证保存后是否正确跳转到 Note 界面（需要用户测试）

## 修复总结
已完成代码修复：
1. Review 界面优化为增量更新，只在必要时全量刷新 ✅
2. 移除了多余的 loading/empty 文本组件 ✅
3. 强制覆盖 Material 3 的 icon tint，确保使用自定义颜色 ✅
4. 简化 Settings 保存后的导航逻辑 ❌
    - 不是键盘的问题。

需要用户测试验证问题是否完全解决。



---
个人给AI开辟新视角和方式：

关于问题4， 也就是初次设置点击Save后，程序退出到桌面的问题，我希望你从如下几个角度作为切入点，对实现逻辑和代码进行review并找出根本原因：

1. android是严格UI线程安全的，所以，你的代码中有没有在非UI线程里访问UI组件？ 
2. 你使用的实现代码中，有没有针对android版本小于11的兼容性问题？ 因为测试机器用的android10的版本。
3. 对于Save后的逻辑处理，原则上应该是点击后走两个并行分支：
	1. 后台线程save settings，然后确保状态安全后，再执行ws相关逻辑；
	2. 后台线程，sleep500ms，之后采用UI线程安全的方式去调用切换逻辑（类似javafx里的Platform.runLater)

