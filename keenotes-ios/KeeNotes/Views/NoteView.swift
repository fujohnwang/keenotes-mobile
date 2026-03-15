import SwiftUI
import Network
import AudioToolbox

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
    @Environment(\.colorScheme) private var colorScheme
    /// Send animation progress (0 = idle, 1 = fully flown away)
    @State private var sendAnimationProgress: CGFloat = 0
    /// Whether send animation is in flight
    @State private var sendAnimating = false

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
                            .padding(.bottom, noteText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !speechService.isRecording ? (isPad ? 16 : 12) : (isPad ? 60 : 48))
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
                        // Only show when there's content or speech is active
                        if !noteText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || speechService.isRecording {
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

                            // Send button (right) - capsule with paperplane + text
                            Button(action: postNote) {
                                HStack(spacing: isPad ? 8 : 6) {
                                    if isPosting {
                                        ProgressView()
                                            .progressViewStyle(CircularProgressViewStyle(tint: .white))
                                            .scaleEffect(isPad ? 1.0 : 0.85)
                                    } else {
                                        Image(systemName: "paperplane.fill")
                                            .font(.system(size: isPad ? 16 : 14, weight: .medium))
                                    }
                                    Text(isPosting ? "Keeping..." : "Keep it")
                                        .font(.system(size: isPad ? 16 : 14, weight: .semibold))
                                }
                                .padding(.horizontal, isPad ? 20 : 16)
                                .padding(.vertical, isPad ? 10 : 8)
                                .background(
                                    canPost
                                        ? LinearGradient(
                                            colors: [Theme.brandColor, Theme.brandColor.opacity(0.78)],
                                            startPoint: .topLeading,
                                            endPoint: .bottomTrailing
                                          )
                                        : LinearGradient(
                                            colors: [Color(.systemGray4), Color(.systemGray4)],
                                            startPoint: .topLeading,
                                            endPoint: .bottomTrailing
                                          )
                                )
                                .foregroundColor(.white)
                                .clipShape(Capsule())
                                .shadow(color: canPost ? Theme.brandColor.opacity(0.3) : .clear, radius: 6, x: 0, y: 3)
                            }
                            .disabled(!canPost || isPosting)
                            .animation(.easeInOut(duration: 0.2), value: canPost)
                        }
                        .padding(.horizontal, isPad ? 16 : 12)
                        .padding(.bottom, isPad ? 12 : 10)
                        }
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                        }
                    }
                    .background(Theme.cardBackground(colorScheme))
                    .cornerRadius(cardCornerRadius)
                    .shadow(
                        color: Theme.cardShadow(colorScheme).color,
                        radius: Theme.cardShadow(colorScheme).radius,
                        x: Theme.cardShadow(colorScheme).x,
                        y: Theme.cardShadow(colorScheme).y
                    )
                    .modifier(BezierFlyEffect(progress: sendAnimationProgress))
                    .opacity(sendAnimating ? max(1.0 - sendAnimationProgress * 1.5, 0) : 1.0)

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
                if keyboardVisible {
                    HStack {
                        Spacer()
                        Button(action: {
                            isTextFieldFocused = false
                        }) {
                            Image(systemName: "chevron.down")
                                .font(.system(size: 16, weight: .medium))
                                .foregroundColor(.secondary)
                                .frame(width: 44, height: 32)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 4)
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
        return canPost ? Theme.brandColor : Color(.systemGray4)
    }

    private func postNote() {
        guard canPost else { return }

        // Stop voice input if active
        speechService.stopRecording()
        // Hide keyboard immediately
        isTextFieldFocused = false

        // Trigger bezier curve fly-away animation
        sendAnimating = true
        withAnimation(.easeInOut(duration: 0.65)) {
            sendAnimationProgress = 1.0
        }

        // After fly-away completes, perform actual send
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.7) {
            performSend()
        }
    }

    private func performSend() {
        let sentContent = noteText
        // Clear text immediately (card is invisible at this point)
        noteText = ""

        // Reset animation state without animation (card reappears empty)
        sendAnimationProgress = 0
        sendAnimating = false

        // Trigger confetti + sound + haptic immediately for seamless feel (optimistic UI)
        if appState.settingsService.confettiOnPostSuccess {
            showSuccessToast = true
            // Haptic feedback
            let generator = UINotificationFeedbackGenerator()
            generator.notificationOccurred(.success)
            // System sound (short chime)
            AudioServicesPlaySystemSound(1001)
        }

        // Copy to clipboard eagerly
        if appState.settingsService.copyToClipboardOnPost {
            UIPasteboard.general.string = ZeroWidthSteganography.embedIfNeeded(
                content: sentContent,
                hiddenMessage: appState.settingsService.hiddenMessage
            )
        }

        // 网络不可用：直接暂存到本地
        if appState.webSocketService.connectionState != .connected {
            appState.pendingNoteService.savePendingNote(content: sentContent)
            
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

        // Send in background silently
        Task {
            let result = await appState.apiService.postNote(content: sentContent)

            await MainActor.run {
                if !result.success {
                    // Send failed: save locally and show error
                    appState.pendingNoteService.savePendingNote(content: sentContent)
                    
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


// MARK: - Bezier Curve Fly-Away Animation

/// GeometryEffect that moves a view along a cubic bezier curve path.
/// Path: starts at origin (Send button area), swings left, then curves up and right to exit top-right.
struct BezierFlyEffect: GeometryEffect {
    var progress: CGFloat

    var animatableData: CGFloat {
        get { progress }
        set { progress = newValue }
    }

    func effectValue(size: CGSize) -> ProjectionTransform {
        // Bezier control points (relative to view's original position)
        // Start: (0, 0) — card's normal position
        // CP1: swing far left
        // CP2: curve up-left
        // End: exit top-right off screen
        let start = CGPoint(x: 0, y: 0)
        let cp1 = CGPoint(x: -size.width * 0.8, y: size.height * 0.1)
        let cp2 = CGPoint(x: -size.width * 0.4, y: -size.height * 1.2)
        let end = CGPoint(x: size.width * 0.5, y: -size.height * 2.0)

        let t = progress
        let oneMinusT = 1.0 - t

        // Cubic bezier formula: B(t) = (1-t)³P0 + 3(1-t)²tP1 + 3(1-t)t²P2 + t³P3
        let x = oneMinusT * oneMinusT * oneMinusT * start.x
            + 3 * oneMinusT * oneMinusT * t * cp1.x
            + 3 * oneMinusT * t * t * cp2.x
            + t * t * t * end.x

        let y = oneMinusT * oneMinusT * oneMinusT * start.y
            + 3 * oneMinusT * oneMinusT * t * cp1.y
            + 3 * oneMinusT * t * t * cp2.y
            + t * t * t * end.y

        // Scale down as it flies (1.0 → 0.3)
        let scale = 1.0 - progress * 0.7

        // Build transform: translate to center, scale, translate back, then offset along path
        var transform = CGAffineTransform.identity
        transform = transform.translatedBy(x: size.width / 2, y: size.height / 2)
        transform = transform.scaledBy(x: scale, y: scale)
        transform = transform.translatedBy(x: -size.width / 2, y: -size.height / 2)
        transform = transform.translatedBy(x: x, y: y)

        return ProjectionTransform(transform)
    }
}
