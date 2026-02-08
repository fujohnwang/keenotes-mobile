# 首次启动配置向导 - 需求文档

## 1. 功能概述

为 KeeNotes 应用的三个平台（JavaFX 桌面端、iOS、Android）添加首次启动配置向导功能，帮助新用户快速完成必要的配置，使应用能够正常使用。

## 2. 用户故事

### 2.1 首次安装用户
**作为** 首次安装 KeeNotes 的用户  
**我希望** 在启动应用时看到清晰的配置引导  
**以便** 我能快速了解需要配置哪些内容并完成设置

**验收标准：**
- 应用启动时自动检测配置状态
- 如果配置未完成，自动导航到设置界面
- 显示友好的引导提示，说明每个配置项的作用
- 提示应该是非侵入式的，不阻止用户直接操作

### 2.2 跳过向导的用户
**作为** 想要稍后配置的用户  
**我希望** 能够跳过当前的引导  
**以便** 我可以先浏览应用或稍后再配置

**验收标准：**
- 向导提供"跳过引导"按钮
- 点击跳过后，向导立即消失
- 下次启动时，如果配置仍未完成，向导会再次显示
- 用户可以随时忽略向导直接操作设置界面

### 2.3 按步骤完成配置的用户
**作为** 按照向导步骤操作的用户  
**我希望** 向导能引导我逐步完成每个配置项  
**以便** 我不会遗漏重要的配置

**验收标准：**
- 向导按顺序提示每个配置项（endpoint → token → encryptionPassword）
- 提供"下一步"按钮进入下一个配置项
- 每个提示包含字段名称和简短说明
- 当必填字段填写完成后，向导自动消失

## 3. 功能需求

### 3.1 配置检测
- **FR-1.1**: 应用启动时调用现有的 `isConfigured()` 方法检测配置状态
- **FR-1.2**: 如果 `isConfigured()` 返回 false，触发向导流程
- **FR-1.3**: 检测逻辑应在应用主界面加载之前完成

### 3.2 自动导航
- **FR-2.1**: 检测到配置未完成时，自动导航到设置界面
- **FR-2.2**: 导航应该是平滑的，符合各平台的导航模式
- **FR-2.3**: 导航完成后立即启动向导

### 3.3 向导引导流程
- **FR-3.1**: 向导按顺序引导用户填写以下字段：
  1. **服务器地址** (endpoint) - 必填
  2. **访问令牌** (token) - 必填
  3. **加密密码** (encryptionPassword) - 可选但推荐
- **FR-3.2**: 每个步骤显示：
  - 字段名称（中文）
  - 字段说明（简短描述用途）
  - "下一步"按钮
  - "跳过引导"按钮
- **FR-3.3**: 点击"下一步"进入下一个字段的引导
- **FR-3.4**: 点击"跳过引导"立即关闭向导

### 3.4 向导完成
- **FR-4.1**: 当用户填写完必填字段（endpoint 和 token）后，向导自动消失
- **FR-4.2**: 不需要显式的"完成"按钮
- **FR-4.3**: 不存储单独的"向导已完成"状态，完全依赖 `isConfigured()` 的结果

### 3.5 持久性
- **FR-5.1**: 如果用户跳过向导或未完成配置就关闭应用，下次启动时向导会再次显示
- **FR-5.2**: 只有当 `isConfigured()` 返回 true 时，向导才不会显示

## 4. 平台特定需求

### 4.1 JavaFX 桌面端
- **FR-6.1**: 使用自定义的轻量级 PopOver 组件（不依赖 ControlsFX）
- **FR-6.2**: PopOver 显示在目标字段旁边，带有指向字段的箭头
- **FR-6.3**: 目标字段应有视觉高亮（如蓝色边框）
- **FR-6.4**: 使用 JavaFX Property 实现响应式状态管理
- **FR-6.5**: PopOver 应有淡入淡出动画
- **FR-6.6**: 集成现有的 `SettingsService` 单例

### 4.2 iOS (SwiftUI)
- **FR-7.1**: 使用底部卡片样式的提示
- **FR-7.2**: 显示箭头指示器指向当前目标字段
- **FR-7.3**: 使用半透明遮罩层（但不阻止用户交互）
- **FR-7.4**: 卡片从底部滑入，带有动画效果
- **FR-7.5**: 使用 SwiftUI 的 @State 管理向导状态
- **FR-7.6**: 支持深色模式
- **FR-7.7**: 集成现有的 `SettingsService` (ObservableObject)
- **FR-7.8**: 符合 iOS Human Interface Guidelines

### 4.3 Android (Kotlin)
- **FR-8.1**: 使用 Spotlight 聚光灯效果高亮目标字段
- **FR-8.2**: 全屏半透明遮罩，只有目标字段区域透明
- **FR-8.3**: 显示浮动提示卡片，包含引导内容
- **FR-8.4**: 使用 Material Design 3 设计规范
- **FR-8.5**: 如果使用传统 View 系统，可以使用 TapTargetView 库
- **FR-8.6**: 如果使用 Jetpack Compose，使用 Canvas 自绘 Spotlight
- **FR-8.7**: 使用 Kotlin State 或 Flow 管理向导状态
- **FR-8.8**: 集成现有的 `SettingsRepository` (DataStore)
- **FR-8.9**: 符合 Material Design 动画规范

## 5. 非功能需求

### 5.1 性能
- **NFR-1.1**: 配置检测应在 100ms 内完成
- **NFR-1.2**: 向导显示动画应流畅（60fps）
- **NFR-1.3**: 向导组件应轻量，不影响应用启动速度

### 5.2 可维护性
- **NFR-2.1**: 向导步骤应通过数据结构定义，易于扩展
- **NFR-2.2**: 各平台应有统一的 WizardStep 数据结构
- **NFR-2.3**: 代码应遵循各平台的最佳实践（响应式设计）

### 5.3 用户体验
- **NFR-3.1**: 向导应该是非侵入式的，不强制用户按步骤操作
- **NFR-3.2**: 提示文本应简洁明了，使用中文
- **NFR-3.3**: 视觉设计应符合各平台的设计规范
- **NFR-3.4**: 动画应自然流畅，不突兀

### 5.4 依赖管理
- **NFR-4.1**: JavaFX 版本不引入 ControlsFX 依赖
- **NFR-4.2**: Android 版本如果使用第三方库，应选择轻量且维护良好的库
- **NFR-4.3**: iOS 版本尽量使用 SwiftUI 原生能力

## 6. 约束条件

### 6.1 技术约束
- 必须集成现有的 SettingsService/SettingsRepository，不重新设计
- 必须使用现有的 `isConfigured()` 方法判断配置状态
- 不存储单独的"向导状态"，完全依赖配置状态

### 6.2 UI 约束
- 所有文本使用中文（暂不考虑国际化）
- 必须符合各平台的设计规范
- 必须支持各平台的主题（如 iOS 深色模式）

### 6.3 业务约束
- 向导只在配置未完成时显示
- 用户可以随时跳过向导
- 向导不阻止用户直接操作设置界面

## 7. 配置字段说明

### 7.1 服务器地址 (endpoint)
- **类型**: 字符串
- **必填**: 是
- **说明**: KeeNotes 服务器的 URL 地址
- **默认值**: "https://kns.afoo.me"
- **向导引导**: 否（因为有默认值，优先级较低，不在向导中提示）
- **提示文本**: N/A

### 7.2 访问令牌 (token)
- **类型**: 字符串
- **必填**: 是
- **说明**: 用于访问服务器的身份验证令牌
- **向导引导**: 是（第一步）
- **提示文本（中文）**: "请输入访问令牌，用于安全连接到服务器"
- **提示文本（英文）**: "Please enter your access token for secure server connection"

### 7.3 加密密码 (encryptionPassword)
- **类型**: 字符串
- **必填**: 是
- **说明**: 用于端到端加密笔记内容的密码
- **向导引导**: 是（第二步）
- **提示文本（中文）**: "请输入加密密码，用于端到端加密保护您的笔记数据"
- **提示文本（英文）**: "Please enter encryption password for end-to-end encryption of your notes"

### 7.4 配置检测逻辑 (isConfigured)

**实现思路**：
```
检查所有必填字段是否都已配置（不为空）
使用底层存储的 getProperty() 方法，避免默认值干扰判断

伪代码：
endpoint = storage.getProperty("endpoint")  // 不使用 getter，避免默认值
token = storage.getProperty("token")
encryptionPassword = storage.getProperty("encryptionPassword")

return !(endpoint == null || endpoint.isBlank() || 
         token == null || token.isBlank() ||
         encryptionPassword == null || encryptionPassword.isBlank())
```

**关键点**：
- 必须检查底层存储的原始值，而不是通过 getter 获取（getter 可能返回默认值）
- 三个字段都必须非空才认为已配置
- 任何一个字段为空都返回 false

### 7.5 国际化支持

**语言检测逻辑**：
- 检查系统语言属性（user.language）或国家/地区（user.country）
- 如果是中文环境（zh 或 CN/TW/HK），显示中文文本
- 其他情况显示英文文本

**实现示例**（JavaFX）：
```java
private boolean isChinese() {
    String language = System.getProperty("user.language");
    String country = System.getProperty("user.country");
    return "zh".equalsIgnoreCase(language) || 
           "CN".equalsIgnoreCase(country) || 
           "TW".equalsIgnoreCase(country) || 
           "HK".equalsIgnoreCase(country);
}
```

**iOS 实现**：
```swift
private func isChinese() -> Bool {
    let language = Locale.current.language.languageCode?.identifier ?? ""
    let region = Locale.current.region?.identifier ?? ""
    return language == "zh" || region == "CN" || region == "TW" || region == "HK"
}
```

**Android 实现**：
```kotlin
private fun isChinese(): Boolean {
    val language = Locale.getDefault().language
    val country = Locale.getDefault().country
    return language == "zh" || country == "CN" || country == "TW" || country == "HK"
}
```

## 8. 成功指标

- 新用户能在 2 分钟内完成配置
- 90% 的新用户能理解每个配置项的作用
- 向导不会对已配置用户造成干扰
- 向导代码易于维护和扩展

## 9. 未来扩展

- 支持多语言（国际化）
- 支持更多配置项的引导
- 支持配置导入/导出
- 支持配置验证（如测试服务器连接）
