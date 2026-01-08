# KeeNotes iOS Native 开发 Checklist

## 项目初始化
- [x] 创建 Xcode 项目结构
- [x] 配置 Package.swift (SPM 依赖管理)
- [x] 复制图标资源

## 核心服务层
- [x] SettingsService - 设置存储
- [x] CryptoService - Argon2+HKDF+AES-GCM 加密
- [x] ApiService - REST API 调用
- [x] WebSocketService - 实时同步
- [x] DatabaseService - SQLite 本地缓存

## 数据模型
- [x] Note 模型
- [x] SyncState 模型

## UI 界面
- [x] MainTabView - 主 Tab 导航
- [x] NoteView - 笔记输入
- [x] ReviewView - 笔记回顾列表
- [x] SettingsView - 设置页面 (含彩蛋)
- [x] DebugView - Debug 面板
- [x] SearchBar - 搜索功能 (集成在 ReviewView)

## 集成与测试
- [x] App 入口配置
- [x] 服务依赖注入
- [ ] 端到端功能验证 (需要在真机/模拟器测试)

## CI/CD
- [x] 创建 ios-native-build.yml
- [x] 更新 release.yml 添加 iOS 构建

## 清理
- [x] 移除 src/ios 目录
- [x] 移除 pom.xml 中 iOS profile
