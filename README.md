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


















