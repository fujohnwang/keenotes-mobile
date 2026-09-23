import Foundation
import Combine

@MainActor
final class PendingNoteService: ObservableObject {
    private let databaseService: DatabaseService
    private let apiService: ApiService
    private let webSocketService: WebSocketService
    private let access: ConfigurationAccess
    private var retryTimer: Timer?
    private var retryTask: Task<Void, Never>?
    private var cancellables = Set<AnyCancellable>()
    private var sendingRequestIDs = Set<String>()
    @Published private(set) var queuedNotes: [PendingNote] = []

    init(databaseService: DatabaseService, apiService: ApiService, webSocketService: WebSocketService, access: ConfigurationAccess) {
        self.databaseService = databaseService
        self.apiService = apiService
        self.webSocketService = webSocketService
        self.access = access
        databaseService.$pendingNotes
            .sink { [weak self] in self?.updateQueuedNotes($0) }.store(in: &cancellables)
        webSocketService.$connectionState.removeDuplicates().filter { $0 == .connected }
            .sink { [weak self] _ in self?.retryPendingNotes() }.store(in: &cancellables)
    }

    deinit { retryTimer?.invalidate(); retryTask?.cancel() }
    func startRetryScheduler() {
        guard retryTimer == nil else { return }
        retryTimer = Timer.scheduledTimer(withTimeInterval: 30 * 60, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.retryPendingNotes() }
        }
    }
    func stopRetryScheduler() { retryTimer?.invalidate(); retryTimer = nil; retryTask?.cancel() }

    /// Caller retains a configuration lease through this entire method. Persist before
    /// HTTP so termination or an uncertain response leaves a retryable request_id.
    enum DeliveryResult { case sent, queuedOffline, queued, sentAwaitingCleanup }

    func deliver(_ note: PreparedNote) async throws -> DeliveryResult {
        guard note.configurationGeneration == access.generation else { throw ConfigurationError.staleOperation }
        // Register before the first await: persistence must not expose this request to UI/retry.
        sendingRequestIDs.insert(note.requestId)
        defer {
            sendingRequestIDs.remove(note.requestId)
            updateQueuedNotes(databaseService.pendingNotes)
        }
        let id = try await databaseService.insertPendingNote(note)
        // WebSocket is the sync channel, not an HTTP reachability check.
        let result = await apiService.postPreparedNote(note)
        guard note.configurationGeneration == access.generation else { return .queued }
        if result.success {
            do { try await databaseService.deletePendingNote(id: id) }
            catch { return .sentAwaitingCleanup }
            return .sent
        }
        if result.networkError && access.isReady { webSocketService.markConnectionSuspect(reason: "http-post") }
        return result.isOffline ? .queuedOffline : .queued
    }

    private func updateQueuedNotes(_ notes: [PendingNote]) {
        queuedNotes = notes.filter { !sendingRequestIDs.contains($0.requestId ?? "") }
    }

    func retryPendingNotes() {
        guard retryTask == nil, let lease = try? access.begin() else { return }
        let generation = access.generation
        retryTask = Task { [weak self] in
            guard let self else { return }
            defer { access.end(lease); retryTask = nil }
            do {
                // Same projection as outbox: first attempts only become eligible after failure.
                for note in queuedNotes {
                    guard !Task.isCancelled, access.isReady, generation == access.generation else { return }
                    let prepared: PreparedNote
                    if let encrypted = note.encryptedContent, let request = note.requestId, !encrypted.isEmpty, !request.isEmpty {
                        prepared = PreparedNote(content: note.content, encryptedContent: encrypted, channel: note.channel,
                                                createdAt: note.createdAt, requestId: request, configurationGeneration: generation)
                    } else {
                        prepared = try apiService.prepareNote(content: note.content, channel: note.channel)
                    }
                    let result = await apiService.postPreparedNote(prepared)
                    guard generation == access.generation else { return }
                    if result.success, let id = note.id { try await databaseService.deletePendingNote(id: id) }
                    else {
                        if result.networkError && access.isReady { webSocketService.markConnectionSuspect(reason: "pending-retry") }
                        return
                    }
                }
            } catch { /* Keep durable pending data; next connection/manual retry can recover. */ }
        }
    }
}
