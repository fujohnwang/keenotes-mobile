# [KeeNotes](https://afoo.me/knotes.html)

Keenotes helps you catch and consolidate your flashing ideas, it's a cross-platform short-note-taking app with E2EE for privacy. 

[https://keenotes.afoo.me](https://keenotes.afoo.me)



## Tech Stack 

- Desktop apps for win/linux/macos are built with JavaFX
- Anroid app is built with kotlin and android native tech stack
- iOS app is built with swift and proper dependencies(native c for cryption)


## Project Structure

- The root dir is a maven project of JavaFX for desktop apps
- The subfolder `keenotes-android` is a standalone android project with android native tech stack
- The subfolder `keenotes-ios` is a standalone iOS xcode project


## Consistency

Although the implementations are different for desktop and mobile apps, they share consistency:

- UI is consistent (only iOS has some style differences)
- Functions & logic are consistent
- Communication prototols are consistent

## 功能与规范

1. **主要功能**
   - 笔记输入与发送
   - 笔记回顾（Review）
   - 笔记搜索
   - 设置页面（Endpoint URL、Token、加密密码）
   - WebSocket 实时同步
   - 本地缓存（SQLite）

2. **通信协议** 
	- 遵循 @api-spec.md
	   - REST API：POST 笔记
	   - WebSocket：handshake、sync_batch、sync_complete、realtime_update
	   - Bearer Token 认证

3. **加密算法** 
	- Argon2id + HKDF-SHA256 + AES-256-GCM






## 通知规范

每个平台都使用了最符合其设计规范的方式：

Desktop (JavaFX): 内嵌popup with fade animation
iOS: 系统overlay或自定义popup
Android: Material Design Snackbar

现在三个平台的行为完全一致：

| 平台 | 交互方式 | 反馈方式 | 显示内容 |
|------|---------|---------|---------|
| **Desktop** | 点击 | 内嵌popup淡入淡出 | 完整内容 + channel |
| **Android** | 点击 | Snackbar（底部） | 完整内容 + channel |
| **iOS** | 点击 | Overlay提示（底部） | 完整内容 + channel |

用户体验统一且符合各平台规范！









