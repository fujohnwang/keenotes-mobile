# 首次启动配置向导 - 任务清单

## 阶段 1: JavaFX 桌面端实现

### 1.1 创建 LightweightPopOver 组件
- [ ] 1.1.1 创建 LightweightPopOver 类，继承 Popup
- [ ] 1.1.2 实现箭头绘制逻辑（Polygon）
- [ ] 1.1.3 实现内容容器布局（标题、描述、按钮）
- [ ] 1.1.4 实现位置计算逻辑（相对于目标节点）
- [ ] 1.1.5 实现箭头方向和位置更新
- [ ] 1.1.6 添加淡入淡出动画
- [ ] 1.1.7 添加阴影效果
- [ ] 1.1.8 测试 PopOver 在不同位置的显示效果

### 1.2 创建 WizardStep 数据类
- [ ] 1.2.1 定义 WizardStep 类（字段：targetNode, title, description, isRequired）
- [ ] 1.2.2 添加必要的 getter 方法

### 1.3 创建 SettingsWizard 管理器
- [ ] 1.3.1 创建 SettingsWizard 类
- [ ] 1.3.2 添加 currentStepProperty (IntegerProperty)
- [ ] 1.3.3 添加 wizardActiveProperty (BooleanProperty)
- [ ] 1.3.4 定义向导步骤列表（endpoint, token, encryptionPassword）
- [ ] 1.3.5 实现步骤监听器（currentStep 变化时显示对应 PopOver）
- [ ] 1.3.6 实现"下一步"按钮逻辑
- [ ] 1.3.7 实现"跳过引导"按钮逻辑
- [ ] 1.3.8 实现字段高亮效果（蓝色边框）
- [ ] 1.3.9 监听 SettingsService 配置变化，自动完成向导
- [ ] 1.3.10 实现 start() 方法启动向导

### 1.4 集成到 SettingsView
- [ ] 1.4.1 在 SettingsView 中创建 SettingsWizard 实例
- [ ] 1.4.2 在 SettingsView 初始化时检查配置状态
- [ ] 1.4.3 如果未配置，启动向导
- [ ] 1.4.4 测试向导在 SettingsView 中的显示

### 1.5 集成到应用启动流程
- [ ] 1.5.1 在 Main 或启动类中检查 isConfigured()
- [ ] 1.5.2 如果未配置，导航到 SettingsView
- [ ] 1.5.3 测试完整的首次启动流程

### 1.6 样式和优化
- [ ] 1.6.1 调整 PopOver 样式（颜色、字体、间距）
- [ ] 1.6.2 优化动画效果
- [ ] 1.6.3 确保支持深色主题
- [ ] 1.6.4 性能测试和优化

## 阶段 2: iOS (SwiftUI) 实现

### 2.1 创建 WizardStep 数据结构
- [ ] 2.1.1 定义 WizardStep 结构体（fieldId, title, description, isRequired）

### 2.2 创建 WizardCard 组件
- [ ] 2.2.1 创建 WizardCard SwiftUI View
- [ ] 2.2.2 实现卡片布局（标题、描述、按钮）
- [ ] 2.2.3 添加圆角和阴影效果
- [ ] 2.2.4 实现"下一步"和"跳过"按钮
- [ ] 2.2.5 支持深色模式

### 2.3 创建 ArrowIndicator 组件
- [ ] 2.3.1 创建 ArrowIndicator SwiftUI View
- [ ] 2.3.2 实现箭头绘制（使用 Path 或 Image）
- [ ] 2.3.3 实现箭头位置计算（指向目标字段）
- [ ] 2.3.4 添加动画效果

### 2.4 创建 OnboardingWizardOverlay 组件
- [ ] 2.4.1 创建 OnboardingWizardOverlay SwiftUI View
- [ ] 2.4.2 添加 @State 管理当前步骤
- [ ] 2.4.3 定义向导步骤列表
- [ ] 2.4.4 实现半透明遮罩层
- [ ] 2.4.5 集成 ArrowIndicator 和 WizardCard
- [ ] 2.4.6 实现步骤切换逻辑
- [ ] 2.4.7 监听 SettingsService.isConfigured 变化
- [ ] 2.4.8 实现卡片滑入动画

### 2.5 集成到 SettingsView
- [ ] 2.5.1 在 SettingsView 中添加 @State showWizard
- [ ] 2.5.2 使用 ZStack 叠加 OnboardingWizardOverlay
- [ ] 2.5.3 在 onAppear 中检查配置状态
- [ ] 2.5.4 测试向导在 SettingsView 中的显示

### 2.6 集成到应用启动流程
- [ ] 2.6.1 在 KeeNotesApp 中检查 isConfigured
- [ ] 2.6.2 如果未配置，显示 SettingsView
- [ ] 2.6.3 测试完整的首次启动流程

### 2.7 样式和优化
- [ ] 2.7.1 调整卡片样式（颜色、字体、间距）
- [ ] 2.7.2 优化动画效果
- [ ] 2.7.3 确保符合 iOS Human Interface Guidelines
- [ ] 2.7.4 性能测试和优化

## 阶段 3: Android (Kotlin) 实现

### 3.1 创建 WizardStep 数据类
- [ ] 3.1.1 定义 WizardStep data class（fieldId, title, description, isRequired）

### 3.2 创建 WizardTooltip 组件
- [ ] 3.2.1 创建 WizardTooltip Composable
- [ ] 3.2.2 实现 Card 布局（标题、描述、按钮）
- [ ] 3.2.3 使用 Material Design 3 样式
- [ ] 3.2.4 实现"下一步"和"跳过"按钮
- [ ] 3.2.5 添加阴影和圆角效果

### 3.3 创建 SpotlightOverlay 组件
- [ ] 3.3.1 创建 SpotlightOverlay Composable
- [ ] 3.3.2 使用 Canvas 绘制全屏遮罩
- [ ] 3.3.3 实现圆形高亮区域（BlendMode.Clear）
- [ ] 3.3.4 实现目标区域位置计算
- [ ] 3.3.5 添加淡入动画

### 3.4 创建 OnboardingWizard 组件
- [ ] 3.4.1 创建 OnboardingWizard Composable
- [ ] 3.4.2 添加 State 管理当前步骤和显示状态
- [ ] 3.4.3 定义向导步骤列表
- [ ] 3.4.4 集成 SpotlightOverlay 和 WizardTooltip
- [ ] 3.4.5 实现步骤切换逻辑
- [ ] 3.4.6 使用 collectAsState 监听 SettingsRepository.isConfigured
- [ ] 3.4.7 实现自动完成逻辑
- [ ] 3.4.8 添加步骤切换动画

### 3.5 集成到 SettingsScreen
- [ ] 3.5.1 在 SettingsScreen 中使用 Box 布局
- [ ] 3.5.2 叠加 OnboardingWizard 组件
- [ ] 3.5.3 检查配置状态决定是否显示向导
- [ ] 3.5.4 测试向导在 SettingsScreen 中的显示

### 3.6 集成到应用启动流程
- [ ] 3.6.1 在 MainActivity 中检查 isConfigured
- [ ] 3.6.2 如果未配置，导航到 SettingsScreen
- [ ] 3.6.3 测试完整的首次启动流程

### 3.7 样式和优化
- [ ] 3.7.1 调整 Material Design 3 样式
- [ ] 3.7.2 优化动画效果
- [ ] 3.7.3 确保符合 Material Design 规范
- [ ] 3.7.4 性能测试和优化

## 阶段 4: 测试和优化

### 4.1 功能测试
- [ ] 4.1.1 测试首次启动时向导自动显示
- [ ] 4.1.2 测试"下一步"按钮功能
- [ ] 4.1.3 测试"跳过引导"按钮功能
- [ ] 4.1.4 测试配置完成后向导自动消失
- [ ] 4.1.5 测试跳过后再次启动仍显示向导
- [ ] 4.1.6 测试配置完成后再次启动不显示向导

### 4.2 UI 测试
- [ ] 4.2.1 测试各平台的视觉效果
- [ ] 4.2.2 测试动画流畅度
- [ ] 4.2.3 测试深色模式支持（JavaFX, iOS）
- [ ] 4.2.4 测试不同屏幕尺寸的适配

### 4.3 性能测试
- [ ] 4.3.1 测试应用启动速度（向导不应影响启动）
- [ ] 4.3.2 测试动画性能（60fps）
- [ ] 4.3.3 测试内存占用

### 4.4 边界情况测试
- [ ] 4.4.1 测试快速点击"下一步"按钮
- [ ] 4.4.2 测试在向导显示时直接填写字段
- [ ] 4.4.3 测试在向导显示时切换到其他界面
- [ ] 4.4.4 测试配置检测失败的降级处理

### 4.5 代码审查和优化
- [ ] 4.5.1 代码审查（遵循各平台最佳实践）
- [ ] 4.5.2 重构重复代码
- [ ] 4.5.3 添加必要的注释
- [ ] 4.5.4 优化性能瓶颈

## 阶段 5: 文档和发布

### 5.1 文档编写
- [ ] 5.1.1 更新用户文档（如何使用向导）
- [ ] 5.1.2 更新开发者文档（如何扩展向导）
- [ ] 5.1.3 编写 CHANGELOG

### 5.2 发布准备
- [ ] 5.2.1 最终测试
- [ ] 5.2.2 准备发布说明
- [ ] 5.2.3 版本号更新

## 优先级说明

- **P0 (最高优先级)**: 阶段 1 (JavaFX 实现)
- **P1 (高优先级)**: 阶段 2 (iOS 实现)
- **P2 (中优先级)**: 阶段 3 (Android 实现)
- **P3 (低优先级)**: 阶段 4 和 5 (测试、优化、文档)

## 预估工作量

- 阶段 1 (JavaFX): 8-10 小时
- 阶段 2 (iOS): 6-8 小时
- 阶段 3 (Android): 6-8 小时
- 阶段 4 (测试): 4-6 小时
- 阶段 5 (文档): 2-3 小时

**总计**: 26-35 小时

## 依赖关系

- 阶段 2 和 3 可以并行开发
- 阶段 4 依赖阶段 1、2、3 完成
- 阶段 5 依赖阶段 4 完成
