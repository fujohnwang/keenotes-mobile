# Android Bug Fix Checklist

## 问题 1: Review 界面新数据到来时没有显示最新 note
- [x] 使用 ArrayList(notes) 替代 toList() 确保创建新实例
- [x] 测试新 note 是否显示

## 问题 2: 移除 Review 界面主区域上方的 Loading/Empty 文本
- [x] 移除 `loadingText` 组件
- [x] 移除 `emptyText` 组件
- [x] 更新 ReviewFragment.kt 逻辑
- [x] 简化空状态处理

## 问题 3: Dock 里的 icon 单独高亮问题
- [x] 修复 ic_note.xml 硬编码颜色
- [x] 修复 ic_settings.xml 硬编码颜色
- [x] 修复 ic_review.xml 硬编码颜色（之前已修复）
- [x] 所有 icon 现在使用 tint 颜色

## 问题 4: Settings 初次设置后点击 Save 退出到桌面
- [x] 修复 navigateToNote() 实现
- [x] 使用 BottomNavigationView.selectedItemId 切换
- [x] 确保在主线程执行

## 修复总结
所有 4 个问题已修复：
1. Review 新数据使用 ArrayList 确保列表更新
2. 移除了多余的 loading/empty 文本组件
3. 所有 bottom nav icon 统一使用 tint 颜色
4. Settings 保存后正确导航到 Note 界面
