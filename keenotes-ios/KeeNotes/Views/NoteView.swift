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
    @State private var keyboardVisible = false
    
    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                // Overview Card (conditionally shown - hide when keyboard is visible)
                if appState.settingsService.showOverviewCard && !keyboardVisible {
                    OverviewCardView()
                        .environmentObject(appState)
                        .padding(EdgeInsets(top: 8, leading: 16, bottom: 4, trailing: 16))
                        .transition(.move(edge: .top).combined(with: .opacity))
                }
                
                // Main content
                VStack(spacing: 16) {
                    // Unified input container with embedded Send Channel and Send button
                    ZStack(alignment: .bottom) {
                        // Note input area (keep border, remove fill background)
                        TextEditor(text: $noteText)
                            .focused($isTextFieldFocused)
                            .frame(minHeight: 150)
                            .padding(.top, 12)
                            .padding(.horizontal, 12)
                            .padding(.bottom, 48) // Space for bottom row
                            .onAppear {
                                // Hide default TextEditor background for iOS 15 compatibility
                                UITextView.appearance().backgroundColor = .clear
                            }
                            .overlay(alignment: .topLeading) {
                                if noteText.isEmpty {
                                    Text("Write your note here...")
                                        .foregroundColor(.gray)
                                        .padding(.horizontal, 16)
                                        .padding(.vertical, 20)
                                        .allowsHitTesting(false)
                                }
                            }
                        
                        // Bottom row: Send Channel (left) + Send button (right)
                        HStack {
                            // Send Channel status (left)
                            SendChannelStatus()
                            
                            Spacer()
                            
                            // Send button (right) - shows "Sending..." when posting
                            Button(action: postNote) {
                                HStack(spacing: 6) {
                                    if isPosting {
                                        ProgressView()
                                            .progressViewStyle(CircularProgressViewStyle(tint: .white))
                                            .scaleEffect(0.8)
                                        Text("Sending...")
                                            .fontWeight(.semibold)
                                    } else {
                                        Image(systemName: "paperplane.fill")
                                            .font(.system(size: 14))
                                        Text("Send")
                                            .fontWeight(.semibold)
                                    }
                                }
                                .padding(.horizontal, 16)
                                .padding(.vertical, 8)
                                .background(buttonBackgroundColor)
                                .foregroundColor(.white)
                                .cornerRadius(8)
                            }
                            .disabled(!canPost || isPosting)
                        }
                        .padding(.horizontal, 12)
                        .padding(.bottom, 10)
                    }
                    .background(Color.clear)
                    .cornerRadius(12)
                    .overlay(
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(Color(.systemGray4), lineWidth: 1)
                    )
                    
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
            .onAppear {
                setupKeyboardObservers()
            }
            .onDisappear {
                removeKeyboardObservers()
            }
            .toolbar {
                // Search button (right)
                ToolbarItem(placement: .navigationBarTrailing) {
                    NavigationLink(destination: SearchView().environmentObject(appState)) {
                        Image(systemName: "magnifyingglass")
                            .font(.system(size: 17))
                    }
                }
                
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
    
    private var buttonBackgroundColor: Color {
        // Keep blue color, use opacity to indicate posting state
        return canPost ? Color.blue : Color.gray
    }
    
    private func postNote() {
        guard canPost else { return }
        
        isPosting = true
        
        Task {
            let result = await appState.apiService.postNote(content: noteText)
            
            await MainActor.run {
                isPosting = false
                
                if result.success {
                    // Hide keyboard on success
                    isTextFieldFocused = false
                    
                    let sentContent = noteText  // Save before clearing
                    noteText = ""
                    
                    // Copy to clipboard if enabled
                    if appState.settingsService.copyToClipboardOnPost {
                        UIPasteboard.general.string = sentContent
                    }
                    
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
                    // Keep keyboard visible on failure so user can edit and retry
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
    
    private func setupKeyboardObservers() {
        NotificationCenter.default.addObserver(
            forName: UIResponder.keyboardWillShowNotification,
            object: nil,
            queue: .main
        ) { _ in
            withAnimation(.easeOut(duration: 0.25)) {
                keyboardVisible = true
            }
        }
        
        NotificationCenter.default.addObserver(
            forName: UIResponder.keyboardWillHideNotification,
            object: nil,
            queue: .main
        ) { _ in
            withAnimation(.easeOut(duration: 0.25)) {
                keyboardVisible = false
            }
        }
    }
    
    private func removeKeyboardObservers() {
        NotificationCenter.default.removeObserver(self, name: UIResponder.keyboardWillShowNotification, object: nil)
        NotificationCenter.default.removeObserver(self, name: UIResponder.keyboardWillHideNotification, object: nil)
    }
}

/// Send Channel status indicator (embedded in input area)
struct SendChannelStatus: View {
    @EnvironmentObject var appState: AppState
    @StateObject private var networkMonitor = NetworkMonitor()
    
    var body: some View {
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
    }
    
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
        return networkMonitor.isConnected ? "✓" : "No Network"
    }
}

/// Sync Channel status indicator (for Review view toolbar)
struct SyncChannelStatus: View {
    @EnvironmentObject var appState: AppState
    
    var body: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(syncChannelColor)
                .frame(width: 8, height: 8)
            
            Text("Sync:")
                .font(.caption)
                .foregroundColor(.secondary)
            
            Text(syncChannelText)
                .font(.caption)
                .fontWeight(.medium)
                .foregroundColor(syncChannelColor)
        }
    }
    
    private var syncChannelColor: Color {
        switch appState.webSocketService.connectionState {
        case .connected: return .green
        case .connecting: return .orange
        case .disconnected: return .gray
        }
    }
    
    private var syncChannelText: String {
        switch appState.webSocketService.connectionState {
        case .connected: return "✓"
        case .connecting: return "..."
        case .disconnected: return "✗"
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
