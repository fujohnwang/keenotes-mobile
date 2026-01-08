import SwiftUI

/// Debug view for development and troubleshooting
struct DebugView: View {
    @EnvironmentObject var appState: AppState
    @Environment(\.dismiss) var dismiss
    
    @State private var statusText = ""
    
    var body: some View {
        NavigationView {
            VStack(alignment: .leading, spacing: 16) {
                // Status text
                if !statusText.isEmpty {
                    Text(statusText)
                        .font(.system(.caption, design: .monospaced))
                        .foregroundColor(.secondary)
                        .padding()
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color(.systemGray6))
                        .cornerRadius(8)
                }
                
                // Debug buttons
                VStack(spacing: 12) {
                    DebugButton(title: "Check DB Count", action: checkDbCount)
                    DebugButton(title: "Dump All Notes", action: dumpAllNotes)
                    DebugButton(title: "Reset Sync State", action: resetSyncState)
                    DebugButton(title: "Clear All Notes", action: clearAllNotes)
                    DebugButton(title: "Test WebSocket", action: testWebSocket)
                }
                
                Spacer()
            }
            .padding()
            .navigationTitle("Debug View")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Back") {
                        dismiss()
                    }
                }
            }
        }
    }
    
    private func checkDbCount() {
        Task {
            do {
                let count = try await appState.databaseService.getNoteCount()
                let syncState = try await appState.databaseService.getSyncState()
                let msg = """
                DB has \(count) notes
                Last sync ID: \(syncState?.lastSyncId ?? -1)
                Last sync time: \(syncState?.lastSyncTime ?? "Never")
                """
                await MainActor.run {
                    statusText = msg
                }
                print("[Debug] \(msg)")
            } catch {
                await MainActor.run {
                    statusText = "Error: \(error.localizedDescription)"
                }
            }
        }
    }
    
    private func dumpAllNotes() {
        Task {
            do {
                let notes = try await appState.databaseService.getRecentNotes(limit: 20)
                var sb = "=== Recent 20 Notes ===\n"
                for note in notes {
                    let preview = String(note.content.prefix(50))
                    sb += "ID: \(note.id), Content: \(preview)...\n"
                }
                await MainActor.run {
                    statusText = sb
                }
                print("[Debug] \(sb)")
            } catch {
                await MainActor.run {
                    statusText = "Error: \(error.localizedDescription)"
                }
            }
        }
    }
    
    private func resetSyncState() {
        Task {
            do {
                try await appState.databaseService.clearSyncState()
                await MainActor.run {
                    statusText = "Sync state reset, reconnecting..."
                }
                print("[Debug] === SYNC STATE RESET ===")
                
                appState.webSocketService.disconnect()
                try? await Task.sleep(nanoseconds: 500_000_000)
                appState.webSocketService.connect()
                
                await MainActor.run {
                    statusText = "Sync state reset, reconnected"
                }
            } catch {
                await MainActor.run {
                    statusText = "Error: \(error.localizedDescription)"
                }
            }
        }
    }
    
    private func clearAllNotes() {
        Task {
            do {
                try await appState.databaseService.clearAllData()
                await MainActor.run {
                    statusText = "All notes cleared, reconnecting..."
                }
                print("[Debug] === ALL NOTES CLEARED ===")
                
                appState.webSocketService.disconnect()
                try? await Task.sleep(nanoseconds: 500_000_000)
                appState.webSocketService.connect()
                
                await MainActor.run {
                    statusText = "All notes cleared, reconnected"
                }
            } catch {
                await MainActor.run {
                    statusText = "Error: \(error.localizedDescription)"
                }
            }
        }
    }
    
    private func testWebSocket() {
        let state = appState.webSocketService.connectionState
        statusText = "WebSocket state: \(state)"
        
        switch state {
        case .disconnected:
            appState.webSocketService.connect()
            statusText = "Attempting to connect..."
        case .connected:
            appState.webSocketService.disconnect()
            statusText = "Disconnected, reconnecting in 1s..."
            Task {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                appState.webSocketService.connect()
                await MainActor.run {
                    statusText = "Reconnecting..."
                }
            }
        case .connecting:
            statusText = "Already connecting..."
        }
    }
}

/// Styled debug button
struct DebugButton: View {
    let title: String
    let action: () -> Void
    
    var body: some View {
        Button(action: action) {
            Text(title)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(Color.blue)
                .foregroundColor(.white)
                .cornerRadius(8)
        }
    }
}
