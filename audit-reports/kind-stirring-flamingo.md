# JavaFX 桌面版性能与内存泄漏审计报告

## 审计概述

本次审计针对 JavaFX 桌面应用的内存管理和生命周期完整性进行全面分析，重点关注长时间运行后可能导致性能下降或内存泄漏的问题。

---

## 一、高风险问题（需立即修复）

### 1. MainContentArea - WebSocket 监听器累积泄漏

**文件**: `src/main/java/cn/keevol/keenotes/mobilefx/MainContentArea.java`

**问题描述**:
- 第 81-93 行：账户切换监听器中每次都会调用 `registerWebSocketListener()`
- 第 121-184 行：`registerWebSocketListener()` 方法创建匿名 `SyncListener` 并注册到 WebSocketClientService
- **关键问题**：旧的监听器没有被移除，新监听器不断累积

**代码片段**:
```java
// 第81-93行：账户切换监听
ServiceManager.getInstance().accountSwitchedProperty().addListener((obs, oldVal, newVal) -> {
    Platform.runLater(() -> {
        webSocketService = ServiceManager.getInstance().getWebSocketService();
        registerWebSocketListener();  // 每次切换都添加新监听器！
        displayedNoteIds.clear();
    });
});

// 第121-184行：registerWebSocketListener 方法
private void registerWebSocketListener() {
    webSocketService.addListener(new WebSocketClientService.SyncListener() {
        // 匿名内部类，没有保存引用，无法移除
    });
}
```

**影响**: 每次切换账户都会泄漏一个 WebSocket 监听器，长时间使用会累积大量监听器

**修复建议**:
1. 将 `SyncListener` 保存为成员变量
2. 在重新注册前先移除旧的监听器
3. 或者在组件销毁时统一清理

---

### 2. NotesDisplayPanel - WebSocket 监听器未移除

**文件**: `src/main/java/cn/keevol/keenotes/mobilefx/NotesDisplayPanel.java`

**问题描述**:
- 第 123-160 行：注册了匿名的 `SyncListener`
- 没有保存监听器引用，无法移除
- 当面板被销毁时，监听器仍然保留在 WebSocket 服务中

**影响**: NotesDisplayPanel 被重建时（如账户切换），旧监听器泄漏

---

### 3. NoteCardView - 动画定时器可能未停止

**文件**: `src/main/java/cn/keevol/keenotes/mobilefx/NoteCardView.java`

**问题描述**:
- 第 43 行：`private AnimationTimer borderTimer;`
- 第 384-398 行：`startBorderAnimation()` 方法启动动画
- 第 422-428 行：`cancelBorderAnimation()` 方法停止动画
- **关键问题**：没有证据表明 `cancelBorderAnimation()` 在组件销毁时被调用

**影响**: 如果 NoteCardView 被移除但动画未停止，会继续消耗 CPU 资源

---

### 4. SimpleForwardServer - ExecutorService 未关闭

**文件**: `src/main/java/cn/keevol/keenotes/utils/SimpleForwardServer.java`

**问题描述**:
- 第 37 行：`server.setExecutor(Executors.newSingleThreadExecutor());`
- **关键问题**：没有保存 ExecutorService 引用
- 第 57-66 行：`stop()` 方法只停止了 HttpServer，没有关闭 ExecutorService

**代码片段**:
```java
// 第37行 - 没有保存引用
server.setExecutor(Executors.newSingleThreadExecutor());

// 第57-66行 stop() 方法 - 没有关闭 ExecutorService
public static void stop() {
    if (server != null) {
        server.stop(0);
        server = null;
    }
}
```

**影响**: 线程池资源泄漏，线程无法被回收

---

## 二、中风险问题（建议修复）

### 5. ThemeService 监听器累积

**影响文件**:
- `NavigationButton.java` 第 30 行
- `NoteCardView.java` 第 56 行
- `KeyCaptureField.java` 第 33 行
- `SettingsSubPanel.java` 第 25 行
- `ReviewPeriodsPanel.java` 第 25 行
- `NotesDisplayPanel.java` 第 69 行
- `Main.java` 第 43 行

**问题描述**:
- 这些 UI 组件都添加了 `ThemeService.getInstance().currentThemeProperty().addListener(...)`
- 但都没有在组件销毁时移除监听器
- 由于 ThemeService 是单例，这些强引用会阻止 UI 组件被垃圾回收

**影响**: UI 组件无法被垃圾回收，内存泄漏

---

### 6. LocalCacheService Statement 资源泄漏

**文件**: `src/main/java/cn/keevol/keenotes/mobilefx/LocalCacheService.java`

**问题描述**:
- 第 179 行：`Statement pragmaStmt = connection.createStatement();`
- 第 195 行：`Statement stmt = connection.createStatement();`
- 虽然在方法最后调用了 `close()`，但如果中间抛出异常，资源可能泄漏

**代码片段**:
```java
// 第179-184行
Statement pragmaStmt = connection.createStatement();
pragmaStmt.execute("PRAGMA journal_mode=WAL");
// ...
pragmaStmt.close(); // 如果上面抛出异常，这行不会执行
```

**修复建议**: 使用 try-with-resources

---

### 7. ServiceManager 匿名监听器无法移除

**文件**: `src/main/java/cn/keevol/keenotes/mobilefx/Main.java` 第 116 行

**问题描述**:
```java
ServiceManager.getInstance().addListener((status, message) -> {
    System.out.println("[Service Status] " + status + ": " + message);
    updateServiceStatusUI(status, message);
});
```
- 添加了匿名监听器，但没有保存引用
- 无法调用 `removeListener()` 移除

---

### 8. MainContentArea LocalCache 监听器未移除

**文件**: `src/main/java/cn/keevol/keenotes/mobilefx/MainContentArea.java` 第 206-220 行

**问题描述**:
- 注册了匿名的 `NoteChangeListener`
- 没有保存引用，无法移除

---

## 三、低风险问题（可选修复）

### 9. DesktopMainView Scene 监听器未移除

**文件**: `src/main/java/cn/keevol/keenotes/mobilefx/DesktopMainView.java` 第 63 行

**问题描述**:
- 添加了 sceneProperty 监听器
- 每次 scene 变化都会设置新的 key handler
- 旧 Scene 的 key handler 没有被清理

---

### 10. SettingsPreferencesView 匿名监听器累积

**文件**: `src/main/java/cn/keevol/keenotes/mobilefx/SettingsPreferencesView.java` 第 33-110 行

**问题描述**:
- 大量匿名 ChangeListener 添加到各种属性
- 由于视图生命周期较长，影响相对较小

---

### 11. 图片资源重复加载

**文件**: `src/main/java/cn/keevol/keenotes/mobilefx/SidebarView.java` 第 187-194 行

**问题描述**:
- 每次创建 SidebarView 时都会加载新的 Image 对象
- 虽然 ImageView 会被垃圾回收，但 JavaFX 的 Image 缓存机制可能导致内存占用

---

## 四、已正确处理的资源管理（良好实践）

### 1. WebSocketClientService.shutdown() - 资源关闭典范

**文件**: `src/main/java/cn/keevol/keenotes/mobilefx/WebSocketClientService.java` 第 664-723 行

**优点**:
- 设置了关闭标志阻止新操作
- 按正确顺序关闭所有资源（调度器、连接池、缓存、WebSocket）
- 处理了中断异常
- 清空了所有引用

### 2. ApiServiceV2 - try-with-resources 正确使用

**文件**: `src/main/java/cn/keevol/keenotes/mobilefx/ApiServiceV2.java` 第 130 行

```java
try (Response response = httpClient.newCall(request).execute()) {
    // 正确使用 try-with-resources
}
```

### 3. SettingsWizard - 生命周期管理良好

**文件**: `src/main/java/cn/keevol/keenotes/mobilefx/SettingsWizard.java`

**优点**:
- 第 436-437 行：正确移除窗口大小监听器
- 第 347 行：有 cleanup() 方法清理 PopOver

### 4. PendingNoteService - ScheduledExecutorService 正确管理

**文件**: `src/main/java/cn/keevol/keenotes/mobilefx/PendingNoteService.java`

**优点**:
- 第 144-148 行：有 shutdown() 方法关闭调度器

---

## 五、修复建议优先级

| 优先级 | 问题 | 文件 | 修复复杂度 |
|--------|------|------|-----------|
| P0 | WebSocket 监听器累积 | MainContentArea.java | 中 |
| P0 | WebSocket 监听器未移除 | NotesDisplayPanel.java | 中 |
| P0 | AnimationTimer 未停止 | NoteCardView.java | 低 |
| P0 | ExecutorService 未关闭 | SimpleForwardServer.java | 低 |
| P1 | ThemeService 监听器累积 | 多个 UI 组件 | 中 |
| P1 | Statement 资源泄漏 | LocalCacheService.java | 低 |
| P1 | 匿名监听器无法移除 | Main.java | 低 |
| P2 | Scene 监听器未移除 | DesktopMainView.java | 低 |
| P2 | 图片资源重复加载 | SidebarView.java | 低 |

---

## 六、通用修复模式

### 模式 1: 为 UI 组件添加 dispose() 方法

```java
public class NoteCardView extends VBox {
    private ChangeListener<Theme> themeListener;
    private ChangeListener<Number> fontSizeListener;

    public NoteCardView() {
        // 保存监听器引用
        themeListener = (obs, oldVal, newVal) -> updateTheme();
        ThemeService.getInstance().currentThemeProperty().addListener(themeListener);

        fontSizeListener = (obs, oldVal, newVal) -> updateFontSize();
        settings.noteFontSizeProperty().addListener(fontSizeListener);
    }

    public void dispose() {
        // 移除所有监听器
        ThemeService.getInstance().currentThemeProperty().removeListener(themeListener);
        settings.noteFontSizeProperty().removeListener(fontSizeListener);

        // 停止动画
        cancelBorderAnimation();
    }
}
```

### 模式 2: 使用 WeakChangeListener

```java
// 使用弱引用监听器，允许垃圾回收
property.addListener(new WeakChangeListener<>(listener));
```

### 模式 3: 统一生命周期管理

```java
// 在应用关闭时统一清理
Platform.setImplicitExit(false);
stage.setOnCloseRequest(event -> {
    // 清理所有服务
    ServiceManager.getInstance().shutdown();
    // 清理所有UI组件
    mainContentArea.dispose();
    // 退出应用
    Platform.exit();
});
```

---

## 七、验证方法

修复后建议使用以下方法验证：

1. **内存分析**: 使用 VisualVM 或 JProfiler 监控内存使用
2. **压力测试**: 频繁切换账户，观察内存是否持续增长
3. **代码审查**: 确保所有 addListener 都有对应的 removeListener
4. **静态分析**: 使用 IDE 的代码检查工具查找未关闭的资源

---

*报告生成时间: 2026-03-22*
*审计范围: JavaFX 桌面版完整代码库*
