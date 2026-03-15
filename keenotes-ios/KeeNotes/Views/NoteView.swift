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
    @State private var showingPendingList = false
    @FocusState private var isTextFieldFocused: Bool
    @State private var keyboardVisible = false
    @StateObject private var speechService = SpeechRecognitionService()
    /// Current keyboard language for speech recognition
    @State private var currentKeyboardLocale: Locale?

    // Adaptive layout based on device
    private var isPad: Bool { DeviceType.isPad }
    private var horizontalPadding: CGFloat { DeviceType.horizontalPadding }
    private var cardCornerRadius: CGFloat { DeviceType.cornerRadius }

    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                // Overview Card (conditionally shown - hide when keyboard is visible)
                if appState.settingsService.showOverviewCard && !keyboardVisible {
                    OverviewCardView()
                        .environmentObject(appState)
                        .padding(EdgeInsets(top: 8, leading: horizontalPadding, bottom: 4, trailing: horizontalPadding))
                        .transition(.move(edge: .top).combined(with: .opacity))
                }

                // Main content
                if showingPendingList {
                    PendingNotesListView(showingPendingList: $showingPendingList)
                        .environmentObject(appState)
                } else {
                VStack(spacing: isPad ? 24 : 16) {
                    // Pending notes banner
                    if appState.databaseService.pendingNoteCount > 0 {
                        HStack {
                            Text("📤 \(appState.databaseService.pendingNoteCount) note(s) pending")
                                .font(.system(size: 13))
                                .foregroundColor(.orange)
                            Spacer()
                            Button("View") { showingPendingList = true }
                                .font(.system(size: 13))
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                        .background(Color.orange.opacity(0.12))
                        .cornerRadius(6)
                    }
                    // Unified input container with embedded Send Channel and Send button
                    ZStack(alignment: .bottom) {
                        // Note input area (keep border, remove fill background)
                        TextEditor(text: $noteText)
                            .focused($isTextFieldFocused)
                            .frame(minHeight: isPad ? 200 : 150)
                            .padding(.top, isPad ? 16 : 12)
                            .padding(.horizontal, isPad ? 16 : 12)
                            .padding(.bottom, isPad ? 60 : 48) // Space for bottom row
                            .font(.system(size: isPad ? 18 : 17))
                            .onAppear {
                                // Hide default TextEditor background for iOS 15 compatibility
                                UITextView.appearance().backgroundColor = .clear
                            }
                            .overlay(alignment: .topLeading) {
                                if noteText.isEmpty {
                                    Text("Write your note here...")
                                        .foregroundColor(.gray)
                                        .padding(.horizontal, isPad ? 20 : 16)
                                        .padding(.vertical, isPad ? 24 : 20)
                                        .allowsHitTesting(false)
                                }
                            }

                        // Bottom area: live speech preview + controls row
                        VStack(spacing: 4) {
                            // Live speech recognition preview
                            if speechService.isRecording, let partial = speechService.partialText, !partial.isEmpty {
                                HStack(spacing: 4) {
                                    Image(systemName: "waveform")
                                        .font(.system(size: 10))
                                        .foregroundColor(.red)
                                    Text(partial)
                                        .font(.system(size: isPad ? 13 : 12))
                                        .foregroundColor(.secondary)
                                        .lineLimit(1)
                                        .truncationMode(.tail)
                                }
                                .padding(.horizontal, isPad ? 16 : 12)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            }
                            
                            // Controls row: Send Channel (left) + mic + Send button (right)
                            HStack {
                            // Send Channel status (left)
                            SendChannelStatus()
                                .font(.caption)

                            Spacer()

                            // Microphone button for voice input (only show when feature is enabled)
                            if appState.settingsService.autoStartDictation || speechService.isRecording {
                                Button(action: {
                                    speechService.toggleRecording()
                                }) {
                                    Image(systemName: speechService.isRecording ? "mic.fill" : "mic")
                                        .font(.system(size: isPad ? 20 : 16))
                                        .foregroundColor(speechService.isRecording ? .red : .blue)
                                }
                                .padding(.trailing, isPad ? 8 : 4)
                            }

                            // Send button (right) - shows "Sending..." when posting
                            Button(action: postNote) {
                                HStack(spacing: isPad ? 8 : 6) {
                                    if isPosting {
                                        ProgressView()
                                            .progressViewStyle(CircularProgressViewStyle(tint: .white))
                                            .scaleEffect(isPad ? 1.0 : 0.8)
                                        Text("Sending...")
                                            .fontWeight(.semibold)
                                    } else {
                                        Image(systemName: "paperplane.fill")
                                            .font(.system(size: isPad ? 18 : 14))
                                        Text("Send")
                                            .fontWeight(.semibold)
                                    }
                                }
                                .padding(.horizontal, isPad ? 24 : 16)
                                .padding(.vertical, isPad ? 12 : 8)
                                .background(buttonBackgroundColor)
                                .foregroundColor(.white)
                                .cornerRadius(isPad ? 12 : 8)
                            }
                            .disabled(!canPost || isPosting)
                        }
                        .padding(.horizontal, isPad ? 16 : 12)
                        .padding(.bottom, isPad ? 12 : 10)
                        }
                    }
                    .background(Color.clear)
                    .cornerRadius(cardCornerRadius)
                    .overlay(
                        RoundedRectangle(cornerRadius: cardCornerRadius)
                            .stroke(Color(.systemGray4), lineWidth: 1)
                    )

                    if !keyboardVisible {
                        Spacer()
                            .contentShape(Rectangle())
                            .onTapGesture {
                                // Tap empty space to dismiss keyboard
                                isTextFieldFocused = false
                            }
                    }
                }
                .padding(EdgeInsets(top: 8, leading: horizontalPadding, bottom: keyboardVisible ? 4 : 16, trailing: horizontalPadding))
                } // end else (not showing pending list)
            }
            .navigationTitle("KeeNotes")
            .navigationBarTitleDisplayMode(.inline)
            .onAppear {
                setupKeyboardObservers()
                // Setup speech recognition callbacks
                speechService.onPartialResult = { _ in
                    // partialText is updated via @Published, UI refreshes automatically
                }
                speechService.onFinalResult = { finalText in
                    // Append final result to editor content
                    if noteText.isEmpty {
                        noteText = finalText
                    } else {
                        let separator = noteText.hasSuffix(" ") || noteText.hasSuffix("\n") ? "" : " "
                        noteText += separator + finalText
                    }
                }
                // Auto-focus input if enabled
                if appState.settingsService.autoFocusInputOnLaunch {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                        isTextFieldFocused = true
                    }
                }
                // Auto-start dictation if enabled (independent of auto-focus)
                if appState.settingsService.autoStartDictation {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) {
                        speechService.startRecordingWithAuthCheck()
                    }
                }
            }
            .onDisappear {
                removeKeyboardObservers()
                speechService.stopRecording()
            }
            .toolbar {
                // Search button (right)
                ToolbarItem(placement: .navigationBarTrailing) {
                    NavigationLink(destination: SearchView().environmentObject(appState)) {
                        Image(systemName: "magnifyingglass")
                            .font(.system(size: 17))
                    }
                }
            }
            .safeAreaInset(edge: .bottom) {
                if keyboardVisible && appState.settingsService.showKeyboardToolbar {
                    HStack {
                        Text("To dismiss keyboard, tap outside or click Done →")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        Spacer()
                        Button(action: {
                            isTextFieldFocused = false
                        }) {
                            Text("Done")
                                .font(.callout.weight(.medium))
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .background(Color(.systemBackground))
                }
            }
            .overlay {
                // Confetti overlay on successful send
                ConfettiView(isActive: $showSuccessToast)
            }
            .overlay(alignment: .center) {
                // Error message overlay (center of screen)
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
                    .padding(.horizontal, 16)
                    .transition(.scale.combined(with: .opacity))
                }
            }
        }
        .navigationViewStyle(.stack)
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

        // Stop voice input if active
        speechService.stopRecording()
        
        // 网络不可用：直接暂存到本地
        if appState.webSocketService.connectionState != .connected {
            let sentContent = noteText
            appState.pendingNoteService.savePendingNote(content: sentContent)
            noteText = ""
            isTextFieldFocused = false
            
            errorMessage = "📤 Saved locally, will auto-send when network restores"
            withAnimation(.spring()) { showErrorToast = true }
            Task {
                try? await Task.sleep(nanoseconds: 3_000_000_000)
                await MainActor.run {
                    withAnimation(.spring()) { showErrorToast = false }
                }
            }
            return
        }

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

                    // Trigger confetti if enabled (ConfettiView resets isActive automatically)
                    if appState.settingsService.confettiOnPostSuccess {
                        showSuccessToast = true
                    }
                } else {
                    // 发送失败：暂存到本地
                    let sentContent = noteText
                    appState.pendingNoteService.savePendingNote(content: sentContent)
                    noteText = ""
                    isTextFieldFocused = false
                    
                    errorMessage = "📤 Send failed, saved locally"
                    withAnimation(.spring()) { showErrorToast = true }
                    Task {
                        try? await Task.sleep(nanoseconds: 3_000_000_000)
                        await MainActor.run {
                            withAnimation(.spring()) { showErrorToast = false }
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
            updateKeyboardLocale()
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
        
        NotificationCenter.default.addObserver(
            forName: UITextInputMode.currentInputModeDidChangeNotification,
            object: nil,
            queue: .main
        ) { _ in
            updateKeyboardLocale()
        }
    }

    private func removeKeyboardObservers() {
        NotificationCenter.default.removeObserver(self, name: UIResponder.keyboardWillShowNotification, object: nil)
        NotificationCenter.default.removeObserver(self, name: UIResponder.keyboardWillHideNotification, object: nil)
        NotificationCenter.default.removeObserver(self, name: UITextInputMode.currentInputModeDidChangeNotification, object: nil)
    }
    
    private func updateKeyboardLocale() {
        // Find the current first responder and get its keyboard language
        UIResponder._currentFirstResponder = nil
        UIApplication.shared.sendAction(#selector(UIResponder.captureFirstResponder(_:)), to: nil, from: nil, for: nil)
        if let responder = UIResponder._currentFirstResponder,
           let textInputMode = responder.textInputMode,
           let lang = textInputMode.primaryLanguage {
            let locale = Locale(identifier: lang)
            currentKeyboardLocale = locale
            speechService.currentLocale = locale
        }
    }
}

/// Send Channel status indicator (embedded in input area)
struct SendChannelStatus: View {
    @EnvironmentObject var appState: AppState
    @StateObject private var networkMonitor = NetworkMonitor()

    private var isPad: Bool { DeviceType.isPad }
    private var statusSpacing: CGFloat { isPad ? 10 : 6 }
    private var indicatorSize: CGFloat { isPad ? 10 : 8 }
    private var fontScale: CGFloat { DeviceType.fontScale }

    var body: some View {
        HStack(spacing: statusSpacing) {
            Circle()
                .fill(sendChannelColor)
                .frame(width: indicatorSize, height: indicatorSize)

            Text("Send Channel:")
                .font(.system(size: 12 * fontScale))
                .foregroundColor(.secondary)

            Text(sendChannelText)
                .font(.system(size: 12 * fontScale))
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

// MARK: - UIResponder extension to find current first responder
extension UIResponder {
    static weak var _currentFirstResponder: UIResponder?
    
    @objc func captureFirstResponder(_ sender: Any?) {
        UIResponder._currentFirstResponder = self
    }
}

// MARK: - Pending Notes List View
struct PendingNotesListView: View {
    @EnvironmentObject var appState: AppState
    @Binding var showingPendingList: Bool
    @State private var pendingNotes: [PendingNote] = []

    private var horizontalPadding: CGFloat { DeviceType.horizontalPadding }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Header with back button
            HStack {
                Button(action: { showingPendingList = false }) {
                    HStack(spacing: 4) {
                        Image(systemName: "chevron.left")
                        Text("Back")
                    }
                    .font(.system(size: 14))
                }
                
                Text("Pending Notes")
                    .font(.headline)
                    .padding(.leading, 8)
                
                Spacer()
            }
            .padding(.horizontal, horizontalPadding)
            .padding(.top, 8)
            .padding(.bottom, 12)
            
            if pendingNotes.isEmpty {
                Spacer()
                Text("No pending notes")
                    .foregroundColor(.secondary)
                    .frame(maxWidth: .infinity)
                Spacer()
            } else {
                List {
                    ForEach(pendingNotes) { pendingNote in
                        NoteRow(note: pendingNote.toNote())
                            .listRowInsets(EdgeInsets(top: 8, leading: horizontalPadding, bottom: 8, trailing: horizontalPadding))
                            .listRowSeparator(.hidden)
                            .listRowBackground(Color.clear)
                    }
                }
                .listStyle(.plain)
            }
        }
        .onAppear { loadPendingNotes() }
    }
    
    private func loadPendingNotes() {
        Task {
            pendingNotes = (try? await appState.databaseService.getPendingNotes()) ?? []
        }
    }
}
