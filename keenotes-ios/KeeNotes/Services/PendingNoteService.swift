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

    init(databaseService: DatabaseService, apiService: ApiService, webSocketService: WebSocketService, access: ConfigurationAccess) {
        self.databaseService = databaseService
        self.apiService = apiService
        self.webSocketService = webSocketService
        self.access = access
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
    enum DeliveryResult { case sent, queued, sentAwaitingCleanup }

    func deliver(_ note: PreparedNote, online: Bool) async throws -> DeliveryResult {
        guard note.configurationGeneration == access.generation else { throw ConfigurationError.staleOperation }
        let id = try await databaseService.insertPendingNote(note)
        guard online else { return .queued }
        let result = await apiService.postPreparedNote(note)
        guard note.configurationGeneration == access.generation else { return .queued }
        if result.success {
            do { try await databaseService.deletePendingNote(id: id) }
            catch { return .sentAwaitingCleanup }
            return .sent
        }
        if result.networkError && access.isReady { webSocketService.markConnectionSuspect(reason: "http-post") }
        return .queued
    }

    func retryPendingNotes() {
        guard retryTask == nil, let lease = try? access.begin() else { return }
        let generation = access.generation
        retryTask = Task { [weak self] in
            guard let self else { return }
            defer { access.end(lease); retryTask = nil }
            do {
                for note in try await databaseService.getPendingNotes() {
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
    var isNetworkAvailable: Bool { webSocketService.connectionState == .connected }
}
