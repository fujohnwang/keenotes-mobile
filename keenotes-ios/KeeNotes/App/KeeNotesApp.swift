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
        
        print("[AppState] Starting initialization...")
        
        do {
            try databaseService.initialize()
            print("[AppState] Database initialized")
            
            // Initialize firstNoteDate if needed - delay to ensure DB is ready
            Task {
                // Small delay to ensure database is fully ready
                try? await Task.sleep(nanoseconds: 500_000_000)
                await initializeFirstNoteDate()
            }
            
            // Connect WebSocket if configured
            if !settingsService.endpointUrl.isEmpty && !settingsService.token.isEmpty {
                webSocketService.connect()
            }
            
            isInitialized = true
        } catch {
            print("Failed to initialize: \(error)")
        }
    }
    
    private func initializeFirstNoteDate() async {
        print("[AppState] Checking firstNoteDate initialization...")
        print("[AppState] Current firstNoteDate: \(settingsService.firstNoteDate ?? "nil")")
        
        // Only initialize if we have notes but no firstNoteDate set
        guard settingsService.firstNoteDate == nil else {
            print("[AppState] firstNoteDate already set, skipping")
            return
        }
        
        do {
            let count = try await databaseService.getNoteCount()
            print("[AppState] Note count: \(count)")
            
            if count > 0 {
                // Get oldest note date
                if let dbQueue = databaseService.dbQueue {
                    let oldestDate = try await dbQueue.read { db in
                        try String.fetchOne(db, sql: "SELECT MIN(createdAt) FROM notes")
                    }
                    
                    print("[AppState] Oldest note date from DB: \(oldestDate ?? "nil")")
                    
                    if let oldestDate = oldestDate {
                        settingsService.firstNoteDate = oldestDate
                        print("[AppState] ✓ Initialized firstNoteDate: \(oldestDate)")
                    }
                } else {
                    print("[AppState] dbQueue is nil")
                }
            } else {
                print("[AppState] No notes in database yet")
            }
        } catch {
            print("[AppState] Failed to initialize firstNoteDate: \(error)")
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
