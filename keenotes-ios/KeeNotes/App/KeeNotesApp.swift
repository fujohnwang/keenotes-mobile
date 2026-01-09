import SwiftUI
import Combine

@main
struct KeeNotesApp: App {
    @StateObject private var appState = AppState()
    @Environment(\.scenePhase) private var scenePhase
    
    var body: some Scene {
        WindowGroup {
            MainTabView()
                .environmentObject(appState)
                .onAppear {
                    appState.initialize()
                }
                .onChange(of: scenePhase, perform: { newPhase in
                    handleScenePhaseChange(newPhase: newPhase)
                })
        }
    }
    
    private func handleScenePhaseChange(newPhase: ScenePhase) {
        switch newPhase {
        case .active:
            // App became active (foreground)
            print("[App] Became active, reconnecting WebSocket if needed")
            if appState.settingsService.isConfigured {
                appState.webSocketService.connect()
            }
            
        case .inactive:
            // App became inactive (transitioning)
            print("[App] Became inactive")
            
        case .background:
            // App went to background
            print("[App] Went to background, disconnecting WebSocket")
            appState.webSocketService.disconnect()
            
        @unknown default:
            break
        }
    }
}

/// Global app state managing all services
@MainActor
class AppState: ObservableObject {
    @Published var isInitialized = false
    @Published var selectedTab = 0  // 0: Note, 1: Review, 2: Settings
    
    // Services
    let settingsService = SettingsService()
    lazy var cryptoService = CryptoService(passwordProvider: { [weak self] in
        self?.settingsService.encryptionPassword
    })
    let databaseService = DatabaseService()
    lazy var apiService = ApiService(
        settingsService: settingsService,
        cryptoService: cryptoService
    )
    lazy var webSocketService: WebSocketService = {
        let service = WebSocketService(
            settingsService: settingsService,
            cryptoService: cryptoService,
            databaseService: databaseService
        )
        // Forward changes from nested ObservableObjects
        service.objectWillChange.sink { [weak self] _ in
            self?.objectWillChange.send()
        }.store(in: &cancellables)
        return service
    }()
    
    private var cancellables = Set<AnyCancellable>()
    
    init() {
        // Forward database changes
        databaseService.objectWillChange.sink { [weak self] _ in
            self?.objectWillChange.send()
        }.store(in: &cancellables)
    }
    
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
