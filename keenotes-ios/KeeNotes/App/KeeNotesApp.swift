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
                    // 检查配置状态，如果未配置则跳转到设置页面
                    if !appState.settingsService.isConfigured {
                        appState.selectedTab = 2  // 跳转到设置页面
                    }
                    if scenePhase == .active {
                        handleScenePhaseChange(newPhase: .active)
                    }
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
            appState.purchaseService.start()
            appState.purchaseService.refresh()
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
    @Published var isInSubPage = false  // true when a sub-page (Search, Analytics, etc.) is active
    /// Incremented to signal sub-pages to dismiss themselves
    @Published var subPageDismissTrigger = 0
    @Published var onThisDayNotes: [Note] = []
    @Published private(set) var configurationRevision = UUID()
    /// Draft text in NoteView input — survives tab switches
    @Published var noteDraftText = ""
    
    // Services
    let settingsService: SettingsService
    let settingsDraft = SettingsDraft()
    private var purchaseOverride: StoreKitPurchaseService?
    lazy var purchaseService: StoreKitPurchaseService = {
        let service = purchaseOverride ?? StoreKitPurchaseService()
        service.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }.store(in: &cancellables)
        return service
    }()
    lazy var configurationCoordinator: ConnectionConfigurationCoordinator = {
        let coordinator = ConnectionConfigurationCoordinator(settings: settingsService, database: databaseService,
            disconnect: { [weak self] in self?.webSocketService.disconnect() },
            connect: { [weak self] in self?.webSocketService.connect() },
            didChange: { [weak self] in
                self?.onThisDayNotes = []
                self?.configurationRevision = UUID()
                self?.subPageDismissTrigger += 1
            })
        coordinator.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }.store(in: &cancellables)
        return coordinator
    }()
    lazy var cryptoService = CryptoService(passwordProvider: { [weak self] in
        self?.settingsService.encryptionPassword
    })
    let databaseService: DatabaseService
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
    
    lazy var pendingNoteService: PendingNoteService = {
        let service = PendingNoteService(
            databaseService: databaseService,
            apiService: apiService,
            webSocketService: webSocketService,
            access: settingsService.access
        )
        service.objectWillChange.sink { [weak self] _ in
            self?.objectWillChange.send()
        }.store(in: &cancellables)
        return service
    }()
    
    private var cancellables = Set<AnyCancellable>()
    
    init(settings: SettingsService? = nil, database: DatabaseService = DatabaseService()) {
        #if DEBUG
        if let index = ProcessInfo.processInfo.arguments.firstIndex(of: "--ui-test-fixture"),
           ProcessInfo.processInfo.arguments.indices.contains(index + 1),
           let fixture = try? UITestFixture.make(mode: ProcessInfo.processInfo.arguments[index + 1]) {
            settingsService = fixture.settings
            databaseService = fixture.database
            purchaseOverride = fixture.purchase
            selectedTab = 2
        } else {
            settingsService = settings ?? SettingsService()
            databaseService = database
        }
        #else
        settingsService = settings ?? SettingsService()
        databaseService = database
        #endif
        // Forward settings changes
        settingsService.objectWillChange.sink { [weak self] _ in
            self?.objectWillChange.send()
        }.store(in: &cancellables)

        settingsService.$showOnThisDayInYearsPast
            .dropFirst()
            .removeDuplicates()
            .sink { [weak self] isEnabled in
                guard let self else { return }
                Task { @MainActor in
                    if isEnabled {
                        await self.loadOnThisDayNotes()
                    } else {
                        self.onThisDayNotes = []
                    }
                }
            }
            .store(in: &cancellables)
        
        // Forward database changes
        databaseService.objectWillChange.sink { [weak self] _ in
            self?.objectWillChange.send()
        }.store(in: &cancellables)
    }
    
    func initialize() {
        guard !isInitialized else { return }
        
        print("[AppState] Starting initialization...")
        purchaseService.start()
        
        do {
            try databaseService.initialize()
            print("[AppState] Database initialized")
            
            Task {
                do {
                    try await configurationCoordinator.recover()
                    settingsDraft.loadOnce(settingsService.configuration)
                    await initializeFirstNoteDate()
                    pendingNoteService.startRetryScheduler()
                    await databaseService.refreshPendingNoteCount()
                    await loadOnThisDayNotes()
                    webSocketService.connect()
                } catch { /* Coordinator exposes recoverable error in Settings. */ }
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
        
        let generation = settingsService.access.generation
        do {
            let count = try await databaseService.getNoteCount()
            guard settingsService.access.isReady, settingsService.access.generation == generation else { return }
            print("[AppState] Note count: \(count)")
            
            if count > 0 {
                // Get oldest note date
                if let dbQueue = databaseService.dbQueue {
                    let oldestDate = try await dbQueue.read { db in
                        try String.fetchOne(db, sql: "SELECT MIN(createdAt) FROM notes")
                    }
                    
                    print("[AppState] Oldest note date from DB: \(oldestDate ?? "nil")")
                    
                    if let oldestDate = oldestDate, settingsService.access.isReady, settingsService.access.generation == generation, settingsService.firstNoteDate == nil {
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
    
    func loadOnThisDayNotes(query: (() async throws -> [Note])? = nil) async {
        guard settingsService.showOnThisDayInYearsPast else {
            onThisDayNotes = []
            return
        }

        let generation = settingsService.access.generation
        guard settingsService.access.isReady else { return }
        do {
            let notes = try await (query ?? { try await self.databaseService.getNotesOnThisDay() })()
            guard settingsService.access.isReady, settingsService.access.generation == generation, settingsService.showOnThisDayInYearsPast else { return }
            print("[AppState] On this day: \(notes.count) note(s)")
            onThisDayNotes = notes
        } catch {
            print("[AppState] Failed to load on-this-day notes: \(error)")
        }
    }

    func reconnect() {
        guard settingsService.access.isReady else { return }
        webSocketService.disconnect()
        webSocketService.connect()
    }
}
