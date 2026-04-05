import SwiftUI

/// Review view for browsing notes history
struct ReviewView: View {
    @EnvironmentObject var appState: AppState
    @State private var notes: [Note] = []
    @State private var isLoading = false
    @State private var isLoadingMore = false
    @State private var selectedPeriod = 0  // 0: 7 days, 1: 30 days, 2: 90 days, 3: All
    @State private var totalCount = 0
    @State private var hasMoreData = true
    @State private var enlargedNote: Note? = nil
    @Environment(\.colorScheme) private var colorScheme

    // Adaptive layout based on device
    private var isPad: Bool { DeviceType.isPad }
    private var horizontalPadding: CGFloat { DeviceType.horizontalPadding }

    private let periods = ["7 days", "30 days", "90 days", "All"]
    private let periodDays = [7, 30, 90, 0]  // 0 means all
    private let pageSize = 20

    var body: some View {
        NavigationView {
            ZStack {
                Theme.pageBackground(colorScheme).ignoresSafeArea()

                VStack(spacing: 0) {
                    // Period selector - custom tab style
                    HStack(spacing: isPad ? 24 : 16) {
                        ForEach(0..<periods.count, id: \.self) { index in
                            Button(action: { selectedPeriod = index }) {
                                VStack(spacing: 6) {
                                    Text(periods[index])
                                        .font(.system(size: isPad ? 15 : 14, weight: selectedPeriod == index ? .semibold : .regular))
                                        .foregroundColor(selectedPeriod == index ? Theme.brandColor : .secondary)

                                    Rectangle()
                                        .fill(selectedPeriod == index ? Theme.brandColor : Color.clear)
                                        .frame(height: 2)
                                        .cornerRadius(1)
                                }
                            }
                            .buttonStyle(.plain)
                        }
                        Spacer()
                    }
                    .padding(EdgeInsets(top: 12, leading: horizontalPadding, bottom: 4, trailing: horizontalPadding))

                    // Header row: Notes count (left) + Sync Channel status (right)
                    HStack {
                        // Notes count with period info (left) - number highlighted
                        notesCountView
                            .font(.caption)

                        Spacer()

                        // Sync Channel status (right) - conditionally visible
                        if appState.settingsService.showSyncChannelStatus {
                            HStack(spacing: 6) {
                                Circle()
                                    .fill(syncChannelColor)
                                    .frame(width: 8, height: 8)

                                Text("Sync Channel:")
                                    .font(.caption)
                                    .foregroundColor(.secondary)

                                Text(syncChannelText)
                                    .font(.caption)
                                    .fontWeight(.medium)
                                    .foregroundColor(syncChannelColor)
                            }
                        }
                    }
                    .padding(EdgeInsets(top: 8, leading: horizontalPadding, bottom: 8, trailing: horizontalPadding))

                    // Notes list or enlarged note view (overlay preserves scroll position)
                    if isLoading {
                        Spacer()
                        ProgressView("Loading...")
                        Spacer()
                    } else if notes.isEmpty {
                        Spacer()
                        VStack(spacing: 12) {
                            Image(systemName: "doc.text")
                                .font(.system(size: 48))
                                .foregroundColor(.gray)
                            Text("No notes yet")
                                .foregroundColor(.gray)
                        }
                        Spacer()
                    } else {
                        ZStack {
                            List {
                                ForEach(notes) { note in
                                    NoteRow(note: note, onEnlarge: {
                                        withAnimation(.easeInOut(duration: 0.25)) {
                                            enlargedNote = note
                                        }
                                    })
                                        .listRowInsets(EdgeInsets(top: 0, leading: horizontalPadding, bottom: 0, trailing: horizontalPadding))
                                        .listRowSeparator(.hidden)
                                        .listRowBackground(Color.clear)
                                        .onAppear {
                                            // Load more when approaching the end
                                            if note.id == notes.last?.id && hasMoreData && !isLoadingMore {
                                                Task {
                                                    await loadMoreNotes()
                                                }
                                            }
                                        }
                                }

                                // Loading indicator at bottom
                                if isLoadingMore {
                                    HStack {
                                        Spacer()
                                        ProgressView()
                                            .padding()
                                        Spacer()
                                    }
                                    .listRowInsets(EdgeInsets())
                                    .listRowSeparator(.hidden)
                                    .listRowBackground(Color.clear)
                                }

                                // Bottom safe area spacer
                                Color.clear
                                    .frame(height: 80)
                                    .listRowInsets(EdgeInsets())
                                    .listRowSeparator(.hidden)
                                    .listRowBackground(Color.clear)
                            }
                            .listStyle(.plain)
                            .modifier(ListBackgroundModifier())
                            .refreshable {
                                await loadNotes()
                            }
                            .opacity(enlargedNote == nil ? 1 : 0)
                            .allowsHitTesting(enlargedNote == nil)

                            // Enlarged note overlay (List stays alive underneath, preserving scroll position)
                            if let enlarged = enlargedNote {
                                EnlargedNoteView(note: enlarged) {
                                    withAnimation(.easeInOut(duration: 0.25)) {
                                        enlargedNote = nil
                                    }
                                }
                            }
                        }
                    }
                }

                // Centered sync spinner (transient, shown during sync)
                if appState.webSocketService.syncStatus == .syncing {
                    VStack(spacing: 12) {
                        ProgressView()
                            .scaleEffect(1.5)
                            .progressViewStyle(CircularProgressViewStyle(tint: .white))
                        Text("Syncing...")
                            .font(.subheadline)
                            .foregroundColor(.white)
                    }
                    .padding(24)
                    .background(Color.black.opacity(0.7))
                    .cornerRadius(16)
                }
            }
            .navigationTitle("KeeNotes Review")
            .navigationBarTitleDisplayMode(.inline)
        }
        .navigationViewStyle(.stack)
        .task {
            // Only load on first appearance (notes is empty)
            if notes.isEmpty {
                await loadNotes()
            }
        }
        .task(id: appState.databaseService.noteCount) {
            // Reload when noteCount changes, but only if we already have data
            // (avoids resetting scroll position on app resume with no actual changes)
            guard !notes.isEmpty else { return }
            let currentCount = try? await appState.databaseService.getNotesCountByPeriod(days: periodDays[selectedPeriod])
            if let currentCount = currentCount, currentCount != totalCount {
                await loadNotes()
            }
        }
        .task(id: appState.webSocketService.syncStatus) {
            // Reload when sync completes
            if appState.webSocketService.syncStatus == .completed {
                await loadNotes()
            }
        }
        .onChange(of: selectedPeriod) { _ in
            Task { await loadNotes() }
        }
    }

    private func loadNotes() async {
        isLoading = true
        hasMoreData = true
        defer { isLoading = false }

        do {
            // Get total count
            totalCount = try await appState.databaseService.getNotesCountByPeriod(days: periodDays[selectedPeriod])

            // Load first page
            let loadedNotes = try await appState.databaseService.getNotesByPeriodPaged(
                days: periodDays[selectedPeriod],
                limit: pageSize,
                offset: 0
            )

            print("[ReviewView] Loaded \(loadedNotes.count) of \(totalCount) notes from database")
            await MainActor.run {
                notes = loadedNotes
                hasMoreData = notes.count < totalCount
            }
        } catch {
            print("[ReviewView] Failed to load notes: \(error)")
        }
    }

    private func loadMoreNotes() async {
        guard !isLoadingMore && hasMoreData else { return }

        isLoadingMore = true
        defer { isLoadingMore = false }

        do {
            let loadedNotes = try await appState.databaseService.getNotesByPeriodPaged(
                days: periodDays[selectedPeriod],
                limit: pageSize,
                offset: notes.count
            )

            print("[ReviewView] Loaded \(loadedNotes.count) more notes (total: \(notes.count + loadedNotes.count)/\(totalCount))")

            await MainActor.run {
                notes.append(contentsOf: loadedNotes)
                hasMoreData = notes.count < totalCount
            }
        } catch {
            print("[ReviewView] Failed to load more notes: \(error)")
        }
    }

    // MARK: - Sync Channel Status

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

    // MARK: - Notes Count Text

    private var notesCountText: String {
        let count = totalCount > 0 ? totalCount : notes.count
        let periodInfo: String

        // Show period info
        switch selectedPeriod {
        case 0: periodInfo = " - Last 7 days"
        case 1: periodInfo = " - Last 30 days"
        case 2: periodInfo = " - Last 90 days"
        case 3: periodInfo = " - All"
        default: periodInfo = ""
        }

        return "\(count) note(s)\(periodInfo)"
    }

    /// Notes count view with highlighted number
    @ViewBuilder
    private var notesCountView: some View {
        let count = totalCount > 0 ? totalCount : notes.count
        let periodInfo = periodSuffix
        (Text("\(count)").foregroundColor(Theme.brandColor).fontWeight(.semibold) +
         Text(periodInfo).foregroundColor(.secondary))
    }

    private var periodSuffix: String {
        switch selectedPeriod {
        case 0: return " note(s) - Last 7 days"
        case 1: return " note(s) - Last 30 days"
        case 2: return " note(s) - Last 90 days"
        case 3: return " note(s) - All"
        default: return " note(s)"
        }
    }
}

/// Single note card in the list
struct NoteRow: View {
    let note: Note
    var onEnlarge: (() -> Void)? = nil
    @EnvironmentObject var appState: AppState
    @State private var showCopiedAlert = false
    @State private var textViewHeight: CGFloat?
    @Environment(\.colorScheme) private var colorScheme

    private var isPad: Bool { DeviceType.isPad }
    private var horizontalPadding: CGFloat { DeviceType.horizontalPadding }
    private var messageFontSize: CGFloat { isPad ? 18 : 17 }

    private var formattedDate: String {
        Theme.formatNoteDate(note.createdAt, compact: appState.settingsService.compactDateFormat)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: isPad ? 16 : 12) {
            // Header: Date and Channel
            HStack(spacing: isPad ? 10 : 8) {
                HStack(spacing: isPad ? 10 : 8) {
                    Text(formattedDate)
                        .font(.system(size: 12))
                        .foregroundColor(Color(.systemGray2))

                    // Channel info - more subtle
                    if !note.channel.isEmpty {
                        Text("·")
                            .font(.system(size: 10))
                            .foregroundColor(Color(.systemGray3))
                        Text(note.channel)
                            .font(.system(size: 11))
                            .foregroundColor(Color(.systemGray2))
                    }
                }
                .contentShape(Rectangle())
                .onTapGesture {
                    copyToClipboard()
                }
                
                Spacer()
                
                if let onEnlarge = onEnlarge {
                    Button(action: onEnlarge) {
                        Image(systemName: "arrow.down.left.and.arrow.up.right")
                            .font(.system(size: isPad ? 14 : 12))
                            .foregroundColor(Color(.systemGray2))
                            .padding(6)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }

            // Note content with selectable text using UITextView
            GeometryReader { geo in
                SelectableTextView(
                    text: note.content,
                    fontSize: messageFontSize,
                    availableWidth: geo.size.width,
                    onTap: copyToClipboard,
                    onCopyMenuAction: showCopiedNotification,
                    onHeightChange: { height in
                        textViewHeight = height
                    }
                )
            }
            .frame(height: textViewHeight ?? SelectableTextView.estimatedHeight(
                text: note.content,
                fontSize: messageFontSize,
                width: UIScreen.main.bounds.width - (isPad ? 48 : 32)
            ))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, isPad ? 20 : 16)
        .overlay(
            Rectangle()
                .fill(Theme.separatorColor(colorScheme))
                .frame(height: 1),
            alignment: .bottom
        )
        .overlay(
            Group {
                if showCopiedAlert {
                    VStack {
                        Spacer()
                        HStack {
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundColor(.white)
                            Text("Copied")
                                .foregroundColor(.white)
                                .font(.subheadline)
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                        .background(Color.black.opacity(0.7))
                        .cornerRadius(20)
                        .padding(.bottom, 20)
                    }
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
        )
    }

    private func copyToClipboard() {
        UIPasteboard.general.string = ZeroWidthSteganography.embedIfNeeded(
            content: note.content,
            hiddenMessage: appState.settingsService.hiddenMessage
        )
        showCopiedNotification()
    }
    
    private func showCopiedNotification() {
        // Haptic feedback
        let generator = UIImpactFeedbackGenerator(style: .medium)
        generator.impactOccurred()

        // Show copied feedback
        withAnimation {
            showCopiedAlert = true
        }

        // Hide after 1.5 seconds
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            withAnimation {
                showCopiedAlert = false
            }
        }
    }
    
    private func calculateHeight(for text: String, width: CGFloat?, fontSize: CGFloat) -> CGFloat {
        // Use a reasonable default width for height calculation
        // The actual width will be constrained by GeometryReader
        let calculationWidth = width ?? (UIScreen.main.bounds.width - (isPad ? 48 : 32) - (horizontalPadding * 2))
        
        let font = UIFont.systemFont(ofSize: fontSize)
        let textStorage = NSTextStorage(string: text)
        let textContainer = NSTextContainer(size: CGSize(width: calculationWidth, height: .greatestFiniteMagnitude))
        let layoutManager = NSLayoutManager()
        
        layoutManager.addTextContainer(textContainer)
        textStorage.addLayoutManager(layoutManager)
        textStorage.addAttribute(.font, value: font, range: NSRange(location: 0, length: text.count))
        
        textContainer.lineFragmentPadding = 0
        layoutManager.glyphRange(for: textContainer)
        
        let rect = layoutManager.usedRect(for: textContainer)
        return ceil(rect.height)
    }
}

/// UITextView wrapper that supports both tap-to-copy and long-press-to-select
struct SelectableTextView: UIViewRepresentable {
    let text: String
    let fontSize: CGFloat
    var availableWidth: CGFloat = 0
    let onTap: () -> Void
    let onCopyMenuAction: () -> Void
    let onHeightChange: (CGFloat) -> Void

    /// Synchronous height estimate using NSLayoutManager — used for first-frame sizing
    static func estimatedHeight(text: String, fontSize: CGFloat, width: CGFloat) -> CGFloat {
        calculateTextHeight(text: text, font: .systemFont(ofSize: fontSize), width: width)
    }

    func makeUIView(context: Context) -> CustomUITextView {
        let textView = CustomUITextView()
        textView.isEditable = false
        textView.isSelectable = true
        textView.isScrollEnabled = true  // Keep scrolling enabled but frame will always be large enough
        textView.backgroundColor = .clear
        textView.textContainerInset = .zero
        textView.textContainer.lineFragmentPadding = 0
        textView.font = .systemFont(ofSize: fontSize)
        textView.textColor = .label
        textView.bounces = false  // Disable bouncing to hide scrolling behavior
        textView.copyActionCallback = context.coordinator.handleCopyAction

        // Add tap gesture for copy
        let tapGesture = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handleTap))
        tapGesture.delegate = context.coordinator
        textView.addGestureRecognizer(tapGesture)

        return textView
    }
    
    func updateUIView(_ uiView: CustomUITextView, context: Context) {
        uiView.text = text
        uiView.font = .systemFont(ofSize: fontSize)
        uiView.copyActionCallback = context.coordinator.handleCopyAction
        
        // Force layout so contentSize reflects the new text immediately,
        // preventing stale heights when List recycles rows at pagination boundaries.
        uiView.setNeedsLayout()
        uiView.layoutIfNeeded()
        
        let height = uiView.contentSize.height
        if height > 0 {
            DispatchQueue.main.async {
                onHeightChange(height)
            }
        } else {
            // Fallback: use availableWidth if provided, otherwise estimate from screen width
            let width = availableWidth > 0 ? availableWidth
                      : (uiView.bounds.width > 0 ? uiView.bounds.width : UIScreen.main.bounds.width - 80)
            DispatchQueue.main.async {
                let fallback = Self.calculateTextHeight(
                    text: uiView.text ?? "",
                    font: uiView.font ?? .systemFont(ofSize: fontSize),
                    width: width
                )
                onHeightChange(max(fallback, 20))
            }
        }
    }
    
    /// Standalone text height calculation using NSLayoutManager (no UITextView dependency)
    private static func calculateTextHeight(text: String, font: UIFont, width: CGFloat) -> CGFloat {
        let textStorage = NSTextStorage(string: text, attributes: [.font: font])
        let textContainer = NSTextContainer(size: CGSize(width: width, height: .greatestFiniteMagnitude))
        let layoutManager = NSLayoutManager()
        textContainer.lineFragmentPadding = 0
        layoutManager.addTextContainer(textContainer)
        textStorage.addLayoutManager(layoutManager)
        layoutManager.glyphRange(for: textContainer)
        return ceil(layoutManager.usedRect(for: textContainer).height)
    }
    
    func makeCoordinator() -> Coordinator {
        Coordinator(onTap: onTap, onCopyMenuAction: onCopyMenuAction)
    }
    
    class Coordinator: NSObject, UIGestureRecognizerDelegate {
        let onTap: () -> Void
        let onCopyMenuAction: () -> Void
        
        init(onTap: @escaping () -> Void, onCopyMenuAction: @escaping () -> Void) {
            self.onTap = onTap
            self.onCopyMenuAction = onCopyMenuAction
        }
        
        @objc func handleTap(_ gesture: UITapGestureRecognizer) {
            guard let textView = gesture.view as? UITextView else { return }
            
            // Only trigger copy if no text is selected
            if textView.selectedRange.length == 0 {
                onTap()
            }
        }
        
        func handleCopyAction() {
            onCopyMenuAction()
        }
        
        // Allow tap gesture to work alongside text selection
        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
            return true
        }
    }
}

/// Custom UITextView that handles copy menu action
class CustomUITextView: UITextView {
    var copyActionCallback: (() -> Void)?
    
    override func copy(_ sender: Any?) {
        // Let system handle the copy
        super.copy(sender)
        
        // Embed hidden message if configured
        let hiddenMessage = UserDefaults.standard.string(forKey: "hidden_message") ?? ""
        if !hiddenMessage.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
           let copied = UIPasteboard.general.string {
            UIPasteboard.general.string = ZeroWidthSteganography.embedIfNeeded(
                content: copied, hiddenMessage: hiddenMessage
            )
        }
        
        // Deselect text
        selectedTextRange = nil
        
        // Notify SwiftUI to show copied alert
        copyActionCallback?()
    }
}

/// Modifier to hide List default background (iOS 16+) with fallback
struct ListBackgroundModifier: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 16.0, *) {
            content.scrollContentBackground(.hidden)
        } else {
            content
        }
    }
}
