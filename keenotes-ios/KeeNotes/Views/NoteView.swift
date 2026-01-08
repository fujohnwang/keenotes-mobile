import SwiftUI
import Network

/// Note input view for creating new notes
struct NoteView: View {
    @EnvironmentObject var appState: AppState
    @State private var noteText = ""
    @State private var showSuccessToast = false
    @State private var showErrorToast = false
    @State private var errorMessage = ""
    @State private var isPosting = false
    @FocusState private var isTextFieldFocused: Bool
    
    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                // Connection status bar
                ConnectionStatusBar()
                
                // Main content
                VStack(spacing: 16) {
                    // Note input area
                    TextEditor(text: $noteText)
                        .focused($isTextFieldFocused)
                        .frame(minHeight: 150)
                        .padding(12)
                        .background(Color(.systemGray6))
                        .cornerRadius(12)
                        .overlay(
                            RoundedRectangle(cornerRadius: 12)
                                .stroke(Color(.systemGray4), lineWidth: 1)
                        )
                        .overlay(alignment: .topLeading) {
                            if noteText.isEmpty {
                                Text("Write your note here...")
                                    .foregroundColor(.gray)
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 20)
                                    .allowsHitTesting(false)
                            }
                        }
                    
                    // Send button
                    Button(action: postNote) {
                        HStack {
                            if isPosting {
                                ProgressView()
                                    .progressViewStyle(CircularProgressViewStyle(tint: .white))
                                    .scaleEffect(0.8)
                            }
                            Text(isPosting ? "Sending..." : "Send")
                                .fontWeight(.semibold)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(canPost ? Color.blue : Color.gray)
                        .foregroundColor(.white)
                        .cornerRadius(10)
                    }
                    .disabled(!canPost || isPosting)
                    
                    Spacer()
                        .contentShape(Rectangle())
                        .onTapGesture {
                            // Tap empty space to dismiss keyboard
                            isTextFieldFocused = false
                        }
                }
                .padding()
                .contentShape(Rectangle())
                .onTapGesture {
                    // Tap outside to dismiss keyboard
                    isTextFieldFocused = false
                }
            }
            .navigationTitle("KeeNotes")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItemGroup(placement: .keyboard) {
                    // Keyboard hint
                    Text("Tap outside or")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    
                    Spacer()
                    
                    Button("Done") {
                        isTextFieldFocused = false
                    }
                }
            }
            .overlay(alignment: .center) {
                // Success checkmark overlay (center of screen)
                if showSuccessToast {
                    ZStack {
                        Circle()
                            .fill(Color.green)
                            .frame(width: 80, height: 80)
                        
                        Image(systemName: "checkmark")
                            .font(.system(size: 40, weight: .bold))
                            .foregroundColor(.white)
                    }
                    .transition(.scale.combined(with: .opacity))
                }
            }
            .overlay(alignment: .top) {
                // Error message overlay (top right)
                if showErrorToast {
                    HStack(spacing: 8) {
                        Image(systemName: "exclamationmark.circle.fill")
                            .foregroundColor(.white)
                        Text(errorMessage)
                            .foregroundColor(.white)
                            .font(.subheadline)
                            .lineLimit(2)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Color.red)
                    .cornerRadius(10)
                    .padding(.top, 60)
                    .padding(.horizontal, 16)
                    .transition(.move(edge: .top).combined(with: .opacity))
                }
            }
        }
    }
    
    private var canPost: Bool {
        !noteText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        appState.settingsService.isConfigured &&
        appState.settingsService.isEncryptionEnabled
    }
    
    private func postNote() {
        guard canPost else { return }
        
        // 收起键盘
        isTextFieldFocused = false
        
        isPosting = true
        
        Task {
            let result = await appState.apiService.postNote(content: noteText)
            
            await MainActor.run {
                isPosting = false
                
                if result.success {
                    noteText = ""
                    
                    // Show success checkmark
                    withAnimation(.spring()) {
                        showSuccessToast = true
                    }
                    
                    // Hide after 1 second
                    Task {
                        try? await Task.sleep(nanoseconds: 1_000_000_000)
                        await MainActor.run {
                            withAnimation(.spring()) {
                                showSuccessToast = false
                            }
                        }
                    }
                } else {
                    // Show error toast
                    errorMessage = result.message
                    withAnimation(.spring()) {
                        showErrorToast = true
                    }
                    
                    // Hide after 3 seconds
                    Task {
                        try? await Task.sleep(nanoseconds: 3_000_000_000)
                        await MainActor.run {
                            withAnimation(.spring()) {
                                showErrorToast = false
                            }
                        }
                    }
                }
            }
        }
    }
}

/// Connection status indicator bar with two separate channels
struct ConnectionStatusBar: View {
    @EnvironmentObject var appState: AppState
    @StateObject private var networkMonitor = NetworkMonitor()
    
    var body: some View {
        HStack(spacing: 12) {
            // Send Channel Status (HTTP API) - Left
            HStack(spacing: 6) {
                Circle()
                    .fill(sendChannelColor)
                    .frame(width: 8, height: 8)
                
                Text("Send Channel:")
                    .font(.caption)
                    .foregroundColor(.secondary)
                
                Text(sendChannelText)
                    .font(.caption)
                    .fontWeight(.medium)
                    .foregroundColor(sendChannelColor)
            }
            
            Spacer()
            
            // Sync Channel Status (WebSocket) - Right
            HStack(spacing: 6) {
                if appState.webSocketService.syncStatus == .syncing {
                    ProgressView()
                        .scaleEffect(0.6)
                } else {
                    Circle()
                        .fill(syncChannelColor)
                        .frame(width: 8, height: 8)
                }
                
                Text("Sync Channel:")
                    .font(.caption)
                    .foregroundColor(.secondary)
                
                Text(syncChannelText)
                    .font(.caption)
                    .fontWeight(.medium)
                    .foregroundColor(syncChannelColor)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(Color(.systemGray6))
    }
    
    // MARK: - Send Channel (HTTP API)
    
    private var sendChannelColor: Color {
        if !appState.settingsService.isConfigured {
            return .orange
        }
        return networkMonitor.isConnected ? .green : .red
    }
    
    private var sendChannelText: String {
        if !appState.settingsService.isConfigured {
            return "Not Configured"
        }
        return networkMonitor.isConnected ? "Ready" : "No Network"
    }
    
    // MARK: - Sync Channel (WebSocket)
    
    private var syncChannelColor: Color {
        switch appState.webSocketService.connectionState {
        case .connected: return .green
        case .connecting: return .orange
        case .disconnected: return .gray
        }
    }
    
    private var syncChannelText: String {
        if appState.webSocketService.syncStatus == .syncing {
            return "Syncing..."
        }
        
        switch appState.webSocketService.connectionState {
        case .connected: return "Connected"
        case .connecting: return "Connecting..."
        case .disconnected: return "Disconnected"
        }
    }
}

/// Network connectivity monitor using NWPathMonitor
class NetworkMonitor: ObservableObject {
    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "NetworkMonitor")
    
    @Published var isConnected = true
    @Published var connectionType: NWInterface.InterfaceType?
    
    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            DispatchQueue.main.async {
                self?.isConnected = path.status == .satisfied
                self?.connectionType = path.availableInterfaces.first?.type
            }
        }
        monitor.start(queue: queue)
    }
    
    deinit {
        monitor.cancel()
    }
}
