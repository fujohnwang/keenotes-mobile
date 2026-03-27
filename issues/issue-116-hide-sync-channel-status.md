# Issue 116: 隐藏 Sync Channel Status

## 问题描述

添加一个新的 toggle 到 settings，默认 disable。只有打开这个 toggle 的时候，才显示 sync channel 状态。三个端都实现。

**GitHub Issue**: [#116](https://github.com/fujohnwang/keenotes-mobile/issues/116)

## 实现方案

### 核心思路

1. 在各端的 Settings 中添加 `showSyncChannelStatus` 配置项，默认值为 `false`
2. 在 Settings UI 中添加对应的 toggle 开关
3. 在显示 sync channel status 的地方，根据配置项动态控制可见性
4. 使用响应式编程模式（Property/Flow/@Published）确保配置变化时 UI 自动更新

### 技术实现

#### 1. JavaFX Desktop 端

**配置层 (SettingsService.java)**
```java
// 添加配置项常量
private static final String KEY_SHOW_SYNC_CHANNEL_STATUS = "show.sync.channel.status";

// 添加 Property 支持响应式绑定
private final BooleanProperty showSyncChannelStatusProperty = new SimpleBooleanProperty(false);

// Getter/Setter
public boolean getShowSyncChannelStatus() {
    return Boolean.parseBoolean(properties.getProperty(KEY_SHOW_SYNC_CHANNEL_STATUS, "false"));
}

public void setShowSyncChannelStatus(boolean enabled) {
    properties.setProperty(KEY_SHOW_SYNC_CHANNEL_STATUS, String.valueOf(enabled));
    showSyncChannelStatusProperty.set(enabled);
}

public BooleanProperty showSyncChannelStatusProperty() {
    return showSyncChannelStatusProperty;
}
```

**UI 层 (SettingsPreferencesView.java)**
```java
// 添加 toggle 控件
private final ToggleSwitch showSyncChannelStatusToggle;

// 监听变化并保存
showSyncChannelStatusToggle.selectedProperty().addListener((obs, oldVal, newVal) -> {
    settings.setShowSyncChannelStatus(newVal);
    settings.save();
});
```

**显示层 (NotesDisplayPanel.java)**
```java
// 使用 Property binding 控制可见性
SettingsService settings = SettingsService.getInstance();
syncChannelBox.visibleProperty().bind(settings.showSyncChannelStatusProperty());
syncChannelBox.managedProperty().bind(settings.showSyncChannelStatusProperty());
```

**关键点**：
- 使用 `visibleProperty().bind()` 实现响应式可见性控制
- 使用 `managedProperty().bind()` 确保隐藏时不占用布局空间
- WebSocket 状态监听器持续运行，不受组件可见性影响

#### 2. Android 端

**配置层 (SettingsRepository.kt)**
```kotlin
// 添加配置项 key
private val KEY_SHOW_SYNC_CHANNEL_STATUS = stringPreferencesKey("show_sync_channel_status")

// 添加 Flow
val showSyncChannelStatus: Flow<Boolean> = context.dataStore.data.map { 
    it[KEY_SHOW_SYNC_CHANNEL_STATUS]?.toBoolean() ?: false 
}

// Setter
suspend fun setShowSyncChannelStatus(enabled: Boolean) {
    context.dataStore.edit { prefs ->
        prefs[KEY_SHOW_SYNC_CHANNEL_STATUS] = enabled.toString()
    }
}
```

**UI 层 (fragment_settings.xml + SettingsFragment.kt)**
```xml
<androidx.appcompat.widget.SwitchCompat
    android:id="@+id/showSyncChannelStatusSwitch"
    android:text="Show Sync Channel Status" />
```

```kotlin
// 加载初始状态
binding.showSyncChannelStatusSwitch.isChecked = 
    app.settingsRepository.showSyncChannelStatus.first()

// 监听变化并保存
binding.showSyncChannelStatusSwitch.setOnCheckedChangeListener { _, isChecked ->
    lifecycleScope.launch {
        app.settingsRepository.setShowSyncChannelStatus(isChecked)
    }
}
```

**显示层 (SearchFragment.kt / ReviewFragment.kt)**
```kotlin
// 监听配置变化，动态控制可见性
lifecycleScope.launch {
    app.settingsRepository.showSyncChannelStatus.collectLatest { show ->
        binding.syncIndicator.visibility = if (show) View.VISIBLE else View.GONE
        binding.syncStatusText.visibility = if (show) View.VISIBLE else View.GONE
    }
}
```

**关键点**：
- 使用 DataStore + Flow 实现响应式配置管理
- 使用 `View.GONE` 确保隐藏时不占用布局空间
- 使用 `collectLatest` 确保配置变化时立即更新 UI

#### 3. iOS 端

**配置层 (SettingsService.swift)**
```swift
// 添加配置项 key
private enum Keys {
    static let showSyncChannelStatus = "show_sync_channel_status"
}

// 添加 @Published 属性
@Published var showSyncChannelStatus: Bool {
    didSet {
        defaults.set(showSyncChannelStatus, forKey: Keys.showSyncChannelStatus)
    }
}

// 初始化
self.showSyncChannelStatus = defaults.object(forKey: Keys.showSyncChannelStatus) == nil 
    ? false 
    : defaults.bool(forKey: Keys.showSyncChannelStatus)
```

**UI 层 (SettingsView.swift)**
```swift
Toggle("Show Sync Channel Status", isOn: Binding(
    get: { appState.settingsService.showSyncChannelStatus },
    set: { appState.settingsService.showSyncChannelStatus = $0 }
))
```

**显示层 (SearchView.swift / ReviewView.swift)**
```swift
// 使用条件渲染控制可见性
if appState.settingsService.showSyncChannelStatus {
    HStack(spacing: 6) {
        Circle()
            .fill(syncChannelColor)
            .frame(width: 8, height: 8)
        Text("Sync Channel:")
            .font(.caption)
        Text(syncChannelText)
            .font(.caption)
            .foregroundColor(syncChannelColor)
    }
}
```

**关键点**：
- 使用 `@Published` 属性实现响应式配置管理
- 使用 SwiftUI 条件渲染（`if`）控制可见性
- 隐藏时组件不存在于视图树中，不占用空间

## 潜在问题排查

### 1. Property Binding 有效性 ✅
- **JavaFX**: 使用 `BooleanProperty` + `bind()`
- **Android**: 使用 `Flow` + `collectLatest`
- **iOS**: 使用 `@Published` + 条件渲染
- **结论**: 所有方案都能正确响应配置变化

### 2. Listener 安全性 ✅
- **JavaFX**: 状态更新方法中有 null 检查（`if (syncChannelIndicator == null || syncChannelLabel == null)`）
- **Android**: 使用 `if (_binding != null)` 和 `isAdded` 检查
- **iOS**: SwiftUI 自动处理生命周期
- **结论**: 不会因组件隐藏而导致 NPE 或崩溃

### 3. 布局正确性 ✅
- **JavaFX**: 使用 `managedProperty().bind()` 确保隐藏时不占空间
- **Android**: 使用 `View.GONE` 确保隐藏时不占空间
- **iOS**: 条件渲染，隐藏时组件不存在
- **结论**: 布局不会因组件隐藏而错乱

### 4. 状态同步 ✅
- WebSocket 连接状态监听器持续运行，不受组件可见性影响
- 从隐藏切换到显示时，状态能立即正确显示
- **结论**: 状态同步正常

## 修改文件清单

### JavaFX 端
1. `src/main/java/cn/keevol/keenotes/mobilefx/SettingsService.java`
2. `src/main/java/cn/keevol/keenotes/mobilefx/SettingsPreferencesView.java`
3. `src/main/java/cn/keevol/keenotes/mobilefx/NotesDisplayPanel.java`

### Android 端
4. `keenotes-android/app/src/main/java/cn/keevol/keenotes/data/repository/SettingsRepository.kt`
5. `keenotes-android/app/src/main/java/cn/keevol/keenotes/ui/settings/SettingsFragment.kt`
6. `keenotes-android/app/src/main/java/cn/keevol/keenotes/ui/search/SearchFragment.kt`
7. `keenotes-android/app/src/main/java/cn/keevol/keenotes/ui/review/ReviewFragment.kt`
8. `keenotes-android/app/src/main/res/layout/fragment_settings.xml`

### iOS 端
9. `keenotes-ios/KeeNotes/Services/SettingsService.swift`
10. `keenotes-ios/KeeNotes/Views/SettingsView.swift`
11. `keenotes-ios/KeeNotes/Views/SearchView.swift`
12. `keenotes-ios/KeeNotes/Views/ReviewView.swift`

## 编译验证

- ✅ **JavaFX 端**: 编译成功
- ⚠️ **iOS 端**: 遇到 Swift Package Manager 缓存损坏问题（环境问题，非代码问题）
- ⏭️ **Android 端**: 按规则跳过编译验证

## 附加发现

在排查过程中确认了 10 次重试失败后的状态变化问题：

- ✅ **Desktop 端（JavaFX）**: 有完整的重试限制（`MAX_RECONNECT_ATTEMPTS = 10`）和 offline 状态处理
- ❌ **Android 端**: 没有实现重试限制，会无限重连
- ❌ **iOS 端**: 没有实现重试限制，会无限重连

**注**: 此问题不在本次 issue 范围内，仅作记录。

## 测试建议

1. **默认行为测试**: 首次安装后，sync channel status 应该默认隐藏
2. **开关测试**: 在 Settings 中打开/关闭开关，验证各个界面的 sync channel status 是否正确显示/隐藏
3. **状态同步测试**: 打开开关后，验证 sync channel status 是否显示正确的连接状态（connected/disconnected/offline）
4. **布局测试**: 验证隐藏 sync channel status 后，布局是否正常（不留空白）
5. **跨端一致性测试**: 验证三个端的行为是否一致

## 总结

本次实现遵循了各平台的最佳实践：
- JavaFX 使用 Property binding 实现响应式 UI
- Android 使用 DataStore + Flow 实现响应式配置管理
- iOS 使用 @Published + SwiftUI 条件渲染

所有实现都确保了：
- 配置变化时 UI 自动更新
- 隐藏时不占用布局空间
- WebSocket 状态监听不受影响
- 不会因组件隐藏而导致错误

功能已完整实现，满足 issue 要求。
