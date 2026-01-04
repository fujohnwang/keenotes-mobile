# KeeNotes Android

Native Android application for KeeNotes - a secure note-taking app with end-to-end encryption.

## Features

- 📝 Quick note input with E2E encryption
- 🔍 Local search through cached notes
- 📖 Review notes by time period
- 🔄 Real-time sync via WebSocket
- 🔐 Argon2 + HKDF + AES-GCM encryption (compatible with desktop version)
- 🌙 Dark theme

## Tech Stack

- **Language**: Kotlin
- **UI**: Android Views with ViewBinding
- **Architecture**: MVVM with Repository pattern
- **Database**: Room (SQLite)
- **Networking**: OkHttp + WebSocket
- **Crypto**: BouncyCastle (Argon2, HKDF)
- **Async**: Kotlin Coroutines + Flow
- **Navigation**: Jetpack Navigation

## Building

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease
```

## Project Structure

```
app/src/main/
├── java/cn/keevol/keenotes/
│   ├── KeeNotesApp.kt          # Application class
│   ├── crypto/
│   │   └── CryptoService.kt    # E2E encryption
│   ├── data/
│   │   ├── dao/                # Room DAOs
│   │   ├── database/           # Room database
│   │   ├── entity/             # Room entities
│   │   └── repository/         # Data repositories
│   ├── network/
│   │   ├── ApiService.kt       # REST API
│   │   └── WebSocketService.kt # Real-time sync
│   └── ui/
│       ├── MainActivity.kt
│       ├── note/               # Note input
│       ├── review/             # Review notes
│       └── settings/           # Settings
└── res/
    ├── layout/                 # XML layouts
    ├── navigation/             # Navigation graph
    ├── drawable/               # Icons & shapes
    └── values/                 # Colors, strings, themes
```

## License

©2025 王福强(Fuqiang Wang) All Rights Reserved
