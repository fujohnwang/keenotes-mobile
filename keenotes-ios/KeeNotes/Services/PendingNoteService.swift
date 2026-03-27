import Foundation
import Combine

/// 离线暂存笔记的调度服务
/// - 暂存发送失败的笔记
/// - 定时重试发送（30分钟间隔）
/// - WebSocket 重连时立即触发重试
class PendingNoteService: ObservableObject {
    private let databaseService: DatabaseService
    private let apiService: ApiService
    private let webSocketService: WebSocketService
    
    private var retryTimer: Timer?
    private var cancellables = Set<AnyCancellable>()
    private var isRetrying = false
    
    private static let retryIntervalSeconds: TimeInterval = 30 * 60 // 30 minutes
    
    init(databaseService: DatabaseService, apiService: ApiService, webSocketService: WebSocketService) {
        self.databaseService = databaseService
        self.apiService = apiService
        self.webSocketService = webSocketService
        
        // WebSocket 重连成功时触发重试
        webSocketService.$connectionState
            .removeDuplicates()
            .filter { $0 == .connected }
            .sink { [weak self] _ in
                self?.onNetworkRestored()
            }
            .store(in: &cancellables)
    }
    
    func startRetryScheduler() {
        guard retryTimer == nil else { return }
        retryTimer = Timer.scheduledTimer(
            withTimeInterval: Self.retryIntervalSeconds,
            repeats: true
        ) { [weak self] _ in
            self?.retryPendingNotes()
        }
        print("[PendingNoteService] Retry scheduler started (interval: 30 min)")
    }
    
    func stopRetryScheduler() {
        retryTimer?.invalidate()
        retryTimer = nil
    }
    
    /// 暂存一条笔记到本地
    func savePendingNote(content: String, channel: String = "mobile-ios") {
        Task {
            do {
                try await databaseService.insertPendingNote(content: content, channel: channel)
                print("[PendingNoteService] Note saved to pending")
            } catch {
                print("[PendingNoteService] Failed to save pending note: \(error)")
            }
        }
    }
    
    /// 网络恢复时立即触发一次重试
    private func onNetworkRestored() {
        Task {
            let count = (try? await databaseService.getPendingNoteCount()) ?? 0
            if count > 0 {
                print("[PendingNoteService] Network restored, retrying \(count) pending notes")
                retryPendingNotes()
            }
        }
    }
    
    /// 逐条重试发送 pending notes
    private func retryPendingNotes() {
        guard !isRetrying else { return }
        isRetrying = true
        
        Task {
            defer { isRetrying = false }
            
            guard let pendingNotes = try? await databaseService.getPendingNotes(),
                  !pendingNotes.isEmpty else { return }
            
            print("[PendingNoteService] Retrying \(pendingNotes.count) pending notes...")
            
            for note in pendingNotes {
                let result = await apiService.postNote(content: note.content)
                
                if result.success {
                    if let noteId = note.id {
                        try? await databaseService.deletePendingNote(id: noteId)
                        print("[PendingNoteService] Pending note sent, id=\(noteId)")
                    }
                } else {
                    print("[PendingNoteService] Retry failed: \(result.message), stopping")
                    break
                }
            }
        }
    }
    
    /// 检查网络是否可用
    var isNetworkAvailable: Bool {
        webSocketService.connectionState == .connected
    }
}
