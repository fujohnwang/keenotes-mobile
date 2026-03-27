# Android UI 升级 Checklist

## 约束
- 不 broken 任何功能，只调整 UI 和风格
- Note 页面：只做 Send 按钮圆角升级（不动 Overview Card 和输入区域）
- 能用 shadow 解决的不要用硬分隔线

## 任务列表

### 配色优化
- [x] 1. `surface_elevated` 提亮到 #282E36，拉开层级对比
- [x] 2. 新增 `primary_muted` 色值（#4D00D4FF），用于弱强调场景

### Header 统一升级（shadow 替代硬分隔）
- [x] 3. 所有 fragment header 加 elevation="4dp" shadow

### Note 页面
- [x] 4. Send 按钮圆角从 8dp 改为 20dp（胶囊形）

### Review 页面
- [x] 5. 自定义 Period Toggle 样式，覆盖 Material3 默认高饱和度取色
- [x] 6. Note 卡片加 elevation 2dp + 细边框 0.5dp
- [x] 7. Sync 状态行降低视觉权重（10sp + alpha 0.7）

### Settings 页面
- [x] 8. 表单分组卡片化（Connection / Preferences / Hidden Watermark）
- [x] 9. SwitchCompat 自定义 track/thumb 颜色

### 通用
- [x] 10. 统一圆角语言（小组件 8dp / 卡片 12dp / 胶囊 20dp）
- [x] 11. Toast 背景改为深色系胶囊 + 文字颜色适配
