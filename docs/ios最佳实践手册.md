# iOS 最佳实践手册

## 键盘收起：TextField + submitLabel(.done) + onSubmit

在 iOS 上，当 `TextField` 获得焦点后键盘弹出，如果没有明确的收起机制，键盘会一直停留并遮挡底部 TabBar/Dock，导致用户无法切换视图。

### 推荐做法

```swift
TextField("placeholder", text: $draft)
    .submitLabel(.done)        // 键盘右下角显示 "Done" 按钮
    .onSubmit { saveAndDismiss() }  // 按 Done 时触发保存并收起键盘

private func saveAndDismiss() {
    // 执行保存逻辑
    // ...
    
    // 收起键盘
    UIApplication.shared.sendAction(
        #selector(UIResponder.resignFirstResponder),
        to: nil, from: nil, for: nil
    )
}
```

### 要点

- `.submitLabel(.done)` 将键盘上的 return 键文案改为 "Done"，给用户明确的完成暗示
- `.onSubmit` 在用户按下 Done 时触发，适合执行保存并收起键盘
- `UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), ...)` 是 SwiftUI 中收起键盘的通用方式
- 如果页面上还有独立的 Save/Update 按钮，按钮的 action 里也应该调用同样的收起键盘逻辑
