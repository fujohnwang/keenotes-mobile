# Lessons Learned

## UI 风格一致性

**问题**: 新增 UI 组件时使用硬编码颜色和自定义卡片，没有复用现有组件，导致风格不一致且不响应 theme 切换。

**规则**:
1. 新增列表类 UI 时，必须先检查现有的列表组件（Desktop: `NoteCardView`/`NotesDisplayPanel`，iOS: `NoteRow`，Android: `NotesAdapter`+`item_note.xml`），优先复用，不要新建 Adapter 和 layout
2. 颜色不允许硬编码 — Desktop 用 CSS class + theme 变量，iOS 用系统语义色（`Color.orange` 而非 `Color(red:...)`），Android 用 `@color/` 资源引用
3. 新增组件前先问自己：「这个组件在 Review Notes 页面是什么样的？我的实现能不能跟它保持一致？」
4. 如果需要数据类型转换来复用组件（如 `PendingNote → Note`），添加 `toNote()` 转换方法，不要为了省事自己写一套新 UI
5. 复用现有组件意味着交互行为（点击复制、长按选择等）也自动继承，不需要重新实现

## Swift 负数范围字面量语法

**问题**: `CGFloat.random(in: -500...-50)` 在 Swift 中会报 "Ambiguous missing whitespace between unary and binary operators"，因为编译器无法区分 `...` 前的 `-` 是一元负号还是二元减号。

**规则**:
1. Swift 中使用负数范围时，必须给负数加括号：`(-500)...(-50)`
2. 每次修改 iOS 代码后，必须用 `xcodebuild` 编译验证再交付，不能只靠肉眼检查

## Android Animator Duration Scale = 0

**问题**: 设备开发者选项中 `Animator duration scale` 设为 0x，导致所有 `ValueAnimator`/`ObjectAnimator` 动画瞬间完成（44ms 而非 2000ms），肉眼完全看不到。

**规则**:
1. Android 动画不能依赖 `ValueAnimator`/`ObjectAnimator` — 它们受系统 `Animator duration scale` 设置影响
2. 需要不受系统设置影响的动画时，使用 `Choreographer.postFrameCallback` 手动逐帧驱动
3. 调试 Android 动画问题时，第一步检查 `Settings > Developer options > Animator duration scale`

## Swift Canvas 中 CGFloat/Double 类型歧义

**问题**: 在 SwiftUI `Canvas` 闭包中做算术运算时，`CGFloat` 和 `Double` 混合运算会导致 `ambiguous use of operator '/'` 编译错误。

**规则**:
1. Canvas 闭包中的坐标计算，确保运算两侧类型一致 — 用 `CGFloat(x)` 显式转换或用 `2.0` 而非 `2`
2. 特别注意 `size / 2` 这种写法 — 如果 `size` 是 `CGFloat`，除数应写成 `2.0` 而非 `2`
