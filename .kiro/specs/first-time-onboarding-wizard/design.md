# 首次启动配置向导 - 设计文档

## 1. 架构设计

### 1.1 整体架构

```
┌─────────────────────────────────────────┐
│         Application Startup             │
└──────────────┬──────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────┐
│    Check isConfigured()                 │
│    (SettingsService/Repository)         │
└──────────────┬──────────────────────────┘
               │
         ┌─────┴─────┐
         │           │
    false│           │true
         │           │
         ▼           ▼
┌──────────────┐  ┌──────────────┐
│ Navigate to  │  │ Show Main    │
│ Settings     │  │ Interface    │
└──────┬───────┘  └──────────────┘
       │
       ▼
┌──────────────────────────────────────────┐
│     Start Onboarding Wizard              │
│  ┌────────────────────────────────────┐  │
│  │  WizardManager                     │  │
│  │  - currentStep: Property/State     │  │
│  │  - wizardActive: Property/State    │  │
│  │  - steps: List<WizardStep>         │  │
│  └────────────────────────────────────┘  │
└──────────────────────────────────────────┘
```

### 1.2 核心组件

#### 1.2.1 WizardStep 数据结构

```kotlin
// 跨平台统一的数据结构（伪代码）
data class WizardStep {
    val fieldId: String          // 字段标识符
    val title: String            // 标题（如"服务器地址"）
    val description: String      // 描述文本
    val isRequired: Boolean      // 是否必填
}
```

#### 1.2.2 WizardManager 组件

负责管理向导的状态和流程：
- 维护当前步骤索引
- 控制向导的显示/隐藏
- 处理"下一步"和"跳过"操作
- 监听配置变化，自动完成向导

## 2. 平台特定设计

### 2.1 JavaFX 桌面端设计

#### 2.1.1 组件结构

```
SettingsView
├── SettingsWizard (新增)
│   ├── LightweightPopOver (自定义组件)
│   │   ├── Arrow (箭头)
│   │   ├── ContentContainer
│   │   │   ├── Title Label
│   │   │   ├── Description Label
│   │   │   └── Button Box
│   │   │       ├── Next Button
│   │   │       └── Skip Button
│   └── WizardManager
│       ├── currentStepProperty: IntegerProperty
│       ├── wizardActiveProperty: BooleanProperty
│       └── steps: List<WizardStep>
└── Settings Form Fields
    ├── Endpoint Field
    ├── Token Field
    └── Encryption Password Field
```

#### 2.1.2 LightweightPopOver 实现

**核心功能：**
- 继承自 `javafx.stage.Popup`
- 支持 8 个箭头方向（上下左右 + 各自的左中右/上中下）
- 自动计算位置，相对于目标节点显示
- 淡入淡出动画
- 阴影效果

**关键方法：**
```java
public class LightweightPopOver extends Popup {
    public enum ArrowLocation {
        LEFT_CENTER, RIGHT_CENTER, 
        TOP_CENTER, BOTTOM_CENTER
        // ... 其他方向
    }
    
    public void setTitle(String title)
    public void setContentNode(Node content)
    public void setArrowLocation(ArrowLocation location)
    public void show(Node owner)
}
```

#### 2.1.3 SettingsWizard 实现

**响应式设计：**
```java
public class SettingsWizard {
    private final IntegerProperty currentStep = new SimpleIntegerProperty(0);
    private final BooleanProperty wizardActive = new SimpleBooleanProperty(false);
    private final List<WizardStep> steps;
    private final SettingsService settingsService;
    
    // 监听步骤变化
    currentStep.addListener((obs, oldVal, newVal) -> {
        if (newVal.intValue() < steps.size()) {
            showStepPopOver(steps.get(newVal.intValue()));
        } else {
            wizardActive.set(false);
        }
    });
    
    // 监听配置变化，自动完成
    settingsService.getToken().addListener((obs, oldVal, newVal) -> {
        checkCompletion();
    });
}
```

**步骤定义：**
```java
private void initSteps(TextField endpointField, 
                       TextField tokenField, 
                       PasswordField passwordField) {
    // 检测系统语言
    boolean isChinese = isChinese();
    
    // 只引导 token 和 password 字段（endpoint 有默认值，不引导）
    steps = List.of(
        new WizardStep(tokenField, 
            isChinese ? "访问令牌" : "Access Token",
            isChinese ? "请输入访问令牌，用于安全连接到服务器" : 
                       "Please enter your access token for secure server connection",
            true),
        new WizardStep(passwordField, 
            isChinese ? "加密密码" : "Encryption Password",
            isChinese ? "请输入加密密码，用于端到端加密保护您的笔记数据" : 
                       "Please enter encryption password for end-to-end encryption of your notes",
            true)
    );
}
```

#### 2.1.4 集成点

在 `SettingsView` 中：
```java
public class SettingsView extends VBox {
    private SettingsWizard wizard;
    
    public SettingsView() {
        // ... 创建 UI 组件
        
        // 初始化向导
        wizard = new SettingsWizard(
            endpointField, 
            tokenField, 
            passwordField,
            SettingsService.getInstance()
        );
        
        // 检查是否需要显示向导
        Platform.runLater(() -> {
            if (!SettingsService.getInstance().isConfigured()) {
                wizard.start();
            }
        });
    }
}
```

在 `Main` 或启动类中：
```java
@Override
public void start(Stage primaryStage) {
    // ... 初始化
    
    if (!SettingsService.getInstance().isConfigured()) {
        // 导航到设置界面
        navigationController.navigateToSettings();
    }
}
```

### 2.2 iOS (SwiftUI) 设计

#### 2.2.1 组件结构

```
SettingsView
└── ZStack
    ├── Settings Form (底层)
    └── OnboardingWizardOverlay (覆盖层)
        ├── Semi-transparent Background
        ├── Arrow Indicator
        └── WizardCard (底部卡片)
            ├── Title
            ├── Description
            ├── Next Button
            └── Skip Button
```

#### 2.2.2 WizardCard 实现

```swift
struct WizardCard: View {
    let step: WizardStep
    let onNext: () -> Void
    let onSkip: () -> Void
    
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Text(step.title)
                    .font(.headline)
                Spacer()
                Button("跳过") { onSkip() }
                    .foregroundColor(.secondary)
            }
            
            Text(step.description)
                .font(.body)
                .foregroundColor(.secondary)
            
            Button(action: onNext) {
                Text("下一步")
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(Color.accentColor)
                    .foregroundColor(.white)
                    .cornerRadius(10)
            }
        }
        .padding()
        .background(Color(.systemBackground))
        .cornerRadius(20)
        .shadow(radius: 10)
        .padding()
    }
}
```

#### 2.2.3 OnboardingWizardOverlay 实现

```swift
struct OnboardingWizardOverlay: View {
    @Binding var showWizard: Bool
    @State private var currentStep = 0
    @ObservedObject var settings: SettingsService
    
    let steps = [
        WizardStep(
            fieldId: "token",
            title: isChinese() ? "访问令牌" : "Access Token",
            description: isChinese() ? "输入您的访问令牌，用于安全连接到服务器" : 
                                      "Enter your access token for secure server connection",
            isRequired: true
        ),
        WizardStep(
            fieldId: "encryptionPassword",
            title: isChinese() ? "加密密码" : "Encryption Password",
            description: isChinese() ? "输入加密密码，用于端到端加密保护您的笔记数据" : 
                                      "Enter encryption password for end-to-end encryption of your notes",
            isRequired: true
        )
    ]
    
    var body: some View {
        if showWizard && currentStep < steps.count {
            ZStack {
                // 半透明遮罩
                Color.black.opacity(0.3)
                    .allowsHitTesting(false)
                
                VStack {
                    Spacer()
                    
                    // 箭头指示器
                    ArrowIndicator(pointingTo: steps[currentStep].fieldId)
                    
                    // 提示卡片
                    WizardCard(
                        step: steps[currentStep],
                        onNext: { 
                            withAnimation {
                                currentStep += 1
                                if currentStep >= steps.count {
                                    showWizard = false
                                }
                            }
                        },
                        onSkip: {
                            withAnimation {
                                showWizard = false
                            }
                        }
                    )
                    .transition(.move(edge: .bottom))
                }
            }
            .onChange(of: settings.isConfigured) { isConfigured in
                if isConfigured {
                    withAnimation {
                        showWizard = false
                    }
                }
            }
        }
    }
}
```

#### 2.2.4 集成点

在 `SettingsView` 中：
```swift
struct SettingsView: View {
    @ObservedObject var settings: SettingsService
    @State private var showWizard = false
    
    var body: some View {
        ZStack {
            // 设置表单
            Form {
                // ... 设置项
            }
            
            // 向导覆盖层
            OnboardingWizardOverlay(
                showWizard: $showWizard,
                settings: settings
            )
        }
        .onAppear {
            showWizard = !settings.isConfigured
        }
    }
}
```

在 `KeeNotesApp` 中：
```swift
@main
struct KeeNotesApp: App {
    @StateObject private var settings = SettingsService()
    @State private var showSettings = false
    
    var body: some Scene {
        WindowGroup {
            if !settings.isConfigured {
                SettingsView(settings: settings)
            } else {
                MainTabView(settings: settings)
            }
        }
    }
}
```

### 2.3 Android (Kotlin) 设计

#### 2.3.1 组件结构（Jetpack Compose）

```
SettingsScreen
└── Box
    ├── SettingsContent (底层)
    └── OnboardingWizard (覆盖层)
        ├── Spotlight Overlay (Canvas)
        └── WizardTooltip (浮动卡片)
            ├── Title
            ├── Description
            ├── Next Button
            └── Skip Button
```

#### 2.3.2 WizardTooltip 实现

```kotlin
@Composable
fun WizardTooltip(
    step: WizardStep,
    onNext: () -> Unit,
    onSkip: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        elevation = CardDefaults.cardElevation(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = step.title,
                    style = MaterialTheme.typography.titleMedium
                )
                TextButton(onClick = onSkip) {
                    Text("跳过")
                }
            }
            
            Text(
                text = step.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Button(
                onClick = onNext,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("下一步")
            }
        }
    }
}
```

#### 2.3.3 SpotlightOverlay 实现

```kotlin
@Composable
fun SpotlightOverlay(
    targetBounds: Rect,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        // 绘制全屏半透明遮罩
        drawRect(
            color = Color.Black.copy(alpha = 0.7f),
            size = size
        )
        
        // 在目标区域"挖洞"（使用 BlendMode）
        drawCircle(
            color = Color.Transparent,
            radius = targetBounds.width / 2 + 20.dp.toPx(),
            center = Offset(
                targetBounds.center.x,
                targetBounds.center.y
            ),
            blendMode = BlendMode.Clear
        )
    }
}
```

#### 2.3.4 OnboardingWizard 实现

```kotlin
@Composable
fun OnboardingWizard(
    settingsRepository: SettingsRepository,
    modifier: Modifier = Modifier
) {
    var showWizard by remember { mutableStateOf(true) }
    var currentStep by remember { mutableStateOf(0) }
    
    val steps = remember {
        val isChinese = isChinese()
        listOf(
            WizardStep(
                fieldId = "token",
                title = if (isChinese) "访问令牌" else "Access Token",
                description = if (isChinese) "输入您的访问令牌，用于安全连接到服务器" 
                             else "Enter your access token for secure server connection",
                isRequired = true
            ),
            WizardStep(
                fieldId = "encryptionPassword",
                title = if (isChinese) "加密密码" else "Encryption Password",
                description = if (isChinese) "输入加密密码，用于端到端加密保护您的笔记数据" 
                             else "Enter encryption password for end-to-end encryption of your notes",
                isRequired = true
            )
        )
    }
    
    // 监听配置状态
    val isConfigured by settingsRepository.isConfigured
        .collectAsState(initial = false)
    
    LaunchedEffect(isConfigured) {
        if (isConfigured) {
            showWizard = false
        }
    }
    
    if (showWizard && currentStep < steps.size) {
        Box(modifier = modifier.fillMaxSize()) {
            // 全屏遮罩
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .pointerInput(Unit) {
                        detectTapGestures { /* 阻止点击穿透 */ }
                    }
            )
            
            // Spotlight 高亮
            SpotlightOverlay(
                targetBounds = getTargetBounds(steps[currentStep].fieldId)
            )
            
            // 提示卡片
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
            ) {
                WizardTooltip(
                    step = steps[currentStep],
                    onNext = {
                        currentStep++
                        if (currentStep >= steps.count) {
                            showWizard = false
                        }
                    },
                    onSkip = { showWizard = false }
                )
            }
        }
    }
}
```

#### 2.3.5 集成点

在 `SettingsFragment` 或 `SettingsScreen` 中：
```kotlin
@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository
) {
    val isConfigured by settingsRepository.isConfigured
        .collectAsState(initial = true)
    
    Box(modifier = Modifier.fillMaxSize()) {
        // 设置内容
        SettingsContent(settingsRepository)
        
        // 向导覆盖层
        if (!isConfigured) {
            OnboardingWizard(settingsRepository)
        }
    }
}
```

在 `MainActivity` 中：
```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val settingsRepository = SettingsRepository(applicationContext)
        
        lifecycleScope.launch {
            val isConfigured = settingsRepository.isConfigured().first()
            
            setContent {
                KeeNotesTheme {
                    if (!isConfigured) {
                        // 导航到设置界面
                        SettingsScreen(settingsRepository)
                    } else {
                        MainScreen()
                    }
                }
            }
        }
    }
}
```

## 3. 数据流设计

### 3.1 状态管理

#### JavaFX
```
SettingsService (单例)
    ↓ Property binding
SettingsWizard
    ↓ currentStepProperty
LightweightPopOver (UI)
```

#### iOS
```
SettingsService (@ObservableObject)
    ↓ @Published properties
OnboardingWizardOverlay
    ↓ @State
WizardCard (UI)
```

#### Android
```
SettingsRepository (DataStore)
    ↓ Flow
OnboardingWizard
    ↓ State/MutableState
WizardTooltip (UI)
```

### 3.2 事件流

```
用户操作 → 向导组件 → 更新状态 → UI 响应
    ↓
配置变化 → SettingsService/Repository → 检查完成 → 关闭向导
```

## 4. 动画设计

### 4.1 JavaFX 动画
- PopOver 淡入：FadeTransition (200ms)
- PopOver 缩放：ScaleTransition (200ms, 0.9 → 1.0)
- 字段高亮：边框颜色过渡

### 4.2 iOS 动画
- 卡片滑入：`.transition(.move(edge: .bottom))`
- 遮罩淡入：`withAnimation { opacity 0 → 0.3 }`
- 步骤切换：`withAnimation(.easeInOut(duration: 0.3))`

### 4.3 Android 动画
- Spotlight 淡入：`animateFloatAsState`
- 卡片滑入：`slideInVertically` + `fadeIn`
- 步骤切换：`AnimatedContent`

## 5. 错误处理

### 5.1 配置检测失败
- 降级处理：假设未配置，显示向导
- 记录日志，便于调试

### 5.2 UI 组件创建失败
- JavaFX: 捕获异常，不显示向导，不影响主功能
- iOS/Android: 同样降级处理

### 5.3 导航失败
- 确保设置界面始终可访问
- 提供手动导航选项

## 6. 测试策略

### 6.1 单元测试
- WizardStep 数据结构
- WizardManager 状态管理逻辑
- 配置检测逻辑

### 6.2 UI 测试
- 向导显示/隐藏
- 步骤切换
- 按钮交互

### 6.3 集成测试
- 完整的首次启动流程
- 配置完成后向导消失
- 跳过后再次启动仍显示

## 7. 性能优化

### 7.1 延迟加载
- 向导组件只在需要时创建
- 避免影响应用启动速度

### 7.2 动画优化
- 使用硬件加速
- 避免复杂的布局计算

### 7.3 内存管理
- 向导关闭后释放资源
- 避免内存泄漏

## 8. 可扩展性设计

### 8.1 步骤定义
- 使用数据驱动的方式定义步骤
- 易于添加新的配置项引导

### 8.2 自定义样式
- 支持主题切换
- 支持自定义颜色和字体

### 8.3 国际化准备
- 文本与代码分离
- 预留多语言支持接口
