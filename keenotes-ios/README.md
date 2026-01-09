# KeeNotes iOS

iOS native version of KeeNotes - a secure note-taking app with end-to-end encryption.

## Requirements

- iOS 15.0+
- Xcode 15.0+
- Swift 5.9+

## Features

- **Note Input**: Quick note creation with E2E encryption
- **Review**: Browse and search your notes history
- **Settings**: Configure server endpoint, token, and encryption password
- **Debug Mode**: Hidden debug panel (tap copyright 7 times)
- **Real-time Sync**: WebSocket-based synchronization
- **Local Cache**: SQLite database using GRDB

## Tech Stack

- **UI**: SwiftUI
- **Database**: GRDB.swift (SQLite)
- **Networking**: URLSession + URLSessionWebSocketTask
- **Encryption**: Argon2id + HKDF-SHA256 + AES-256-GCM

## Encryption Compatibility

✅ **Fully Compatible**: The encryption implementation uses the official Argon2 C library, ensuring full compatibility with:
- JavaFX desktop version (Bouncy Castle)
- Android native version (Bouncy Castle)

All platforms use the same encryption format:
- **Key Derivation**: Argon2id (64MB memory, 3 iterations) + HKDF-SHA256
- **Encryption**: AES-256-GCM with 128-bit authentication tag
- **Format**: `Base64(version[1] + salt[16] + iv[12] + timestamp[8] + ciphertext + tag[16])`

Notes created on any platform can be decrypted on all other platforms.

## Building

1. Open `KeeNotes.xcodeproj` in Xcode
2. Wait for Swift Package Manager to resolve dependencies
3. Select your target device/simulator
4. Build and run (⌘R)

## Project Structure

```
keenotes-ios/
├── KeeNotes.xcodeproj/
├── KeeNotes/
│   ├── App/
│   │   └── KeeNotesApp.swift      # App entry point
│   ├── Models/
│   │   └── Note.swift             # Data models
│   ├── Services/
│   │   ├── SettingsService.swift  # UserDefaults storage
│   │   ├── CryptoService.swift    # E2E encryption
│   │   ├── Argon2Swift.swift      # Argon2id implementation
│   │   ├── DatabaseService.swift  # SQLite operations
│   │   ├── ApiService.swift       # REST API client
│   │   └── WebSocketService.swift # Real-time sync
│   ├── Views/
│   │   ├── MainTabView.swift      # Tab navigation
│   │   ├── NoteView.swift         # Note input
│   │   ├── ReviewView.swift       # Notes list
│   │   ├── SettingsView.swift     # Settings + easter egg
│   │   └── DebugView.swift        # Debug panel
│   └── Resources/
│       └── Assets.xcassets/       # App icons
└── README.md
```

## License

Copyright © 2025 Keevol. All rights reserved.
