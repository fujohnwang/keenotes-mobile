import SwiftUI

@main
struct KeeNotesApp: App {
    @StateObject private var appState = AppState()
    
    var body: some Scene {
        WindowGroup {
            MainTabView()
                .environmentObject(appState)
                .onAppear {
                    appState.initialize()
                }
        }
    }
}

/// Global app state managing all services
@MainActor
class AppState: ObservableObject {
    @Published var isInitialized = false
    
    // Services
    let settingsService = SettingsService()
    lazy var cryptoService = CryptoService(passwordProvider: { [weak self] in
        self?.settingsService.encryptionPassword
    })
    lazy var databaseService = DatabaseService()
    lazy var apiService = ApiService(
        settingsService: settingsService,
        cryptoService: cryptoService
    )
    lazy var webSocketService = WebSocketService(
        settingsService: settingsService,
        cryptoService: cryptoService,
        databaseService: databaseService
    )
    
    func initialize() {
        guard !isInitialized else { return }
        
        do {
            try databaseService.initialize()
            
            // Connect WebSocket if configured
            if !settingsService.endpointUrl.isEmpty && !settingsService.token.isEmpty {
                webSocketService.connect()
            }
            
            isInitialized = true
        } catch {
            print("Failed to initialize: \(error)")
        }
    }
    
    func reconnect() {
        webSocketService.disconnect()
        webSocketService.resetState()
        
        Task {
            try? await databaseService.clearSyncState()
            
            if !settingsService.endpointUrl.isEmpty && !settingsService.token.isEmpty {
                try? await Task.sleep(nanoseconds: 500_000_000)
                webSocketService.connect()
            }
        }
    }
}
