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

    // Adaptive layout based on device
    private var isPad: Bool { DeviceType.isPad }
    private var horizontalPadding: CGFloat { DeviceType.horizontalPadding }

    private let periods = ["7 days", "30 days", "90 days", "All"]
    private let periodDays = [7, 30, 90, 0]  // 0 means all
    private let pageSize = 20

    var body: some View {
        NavigationView {
            ZStack {
                VStack(spacing: 0) {
                    // Period selector
                    Picker("Period", selection: $selectedPeriod) {
                        ForEach(0..<periods.count, id: \.self) { index in
                            Text(periods[index]).tag(index)
                        }
                    }
                    .pickerStyle(.segmented)
                    .padding(EdgeInsets(top: 8, leading: horizontalPadding, bottom: 8, trailing: horizontalPadding))

                    // Header row: Notes count (left) + Sync Channel status (right)
                    HStack {
                        // Notes count with period info (left)
                        Text(notesCountText)
                            .font(.caption)
                            .foregroundColor(.secondary)

                        Spacer()

                        // Sync Channel status (right)
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
                    .padding(EdgeInsets(top: 8, leading: horizontalPadding, bottom: 8, trailing: horizontalPadding))

                    // Notes list
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
                        List {
                            ForEach(notes) { note in
                                NoteRow(note: note)
                                    .listRowInsets(EdgeInsets(top: 8, leading: horizontalPadding, bottom: 8, trailing: horizontalPadding))
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
                        }
                        .listStyle(.plain)
                        .refreshable {
                            await loadNotes()
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
        .onAppear {
            Task { await loadNotes() }
        }
        .task(id: appState.databaseService.noteCount) {
            // Reload when noteCount changes
            await loadNotes()
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
}

/// Single note card in the list
struct NoteRow: View {
    let note: Note
    @State private var showCopiedAlert = false

    private var isPad: Bool { DeviceType.isPad }
    private var cardPadding: CGFloat { isPad ? 24 : 16 }
    private var messageFontSize: CGFloat { isPad ? 18 : 17 }

    private var formattedDate: String {
        // Simply return the first 19 characters (yyyy-MM-dd HH:mm:ss)
        // Most notes already have this format from the server
        if note.createdAt.count >= 19 {
            return String(note.createdAt.prefix(19))
        }

        // Fallback: try to parse and format
        let dateFormatter = DateFormatter()
        dateFormatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"

        if let date = dateFormatter.date(from: note.createdAt) {
            let displayFormatter = DateFormatter()
            displayFormatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
            return displayFormatter.string(from: date)
        }

        // If all else fails, return as-is
        return note.createdAt
    }

    var body: some View {
        VStack(alignment: .leading, spacing: isPad ? 16 : 12) {
            // Header: Date and Channel
            HStack(spacing: isPad ? 10 : 8) {
                HStack(spacing: 4) {
                    Image(systemName: "clock")
                        .font(.caption2)
                    Text(formattedDate)
                        .font(.caption)
                }
                .foregroundColor(.secondary)

                // Channel info
                if !note.channel.isEmpty {
                    Text("•")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Text(note.channel)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
            .contentShape(Rectangle())
            .onTapGesture {
                copyToClipboard()
            }

            // Note content with selectable text using UITextView
            SelectableTextView(
                text: note.content,
                fontSize: messageFontSize,
                onTap: copyToClipboard
            )
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(cardPadding)
        .background(
            RoundedRectangle(cornerRadius: DeviceType.cornerRadius)
                .fill(Color(.systemBackground))
                .shadow(color: Color.black.opacity(0.05), radius: 8, x: 0, y: 2)
        )
        .overlay(
            RoundedRectangle(cornerRadius: DeviceType.cornerRadius)
                .stroke(Color(.systemGray5), lineWidth: 1)
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
        UIPasteboard.general.string = note.content

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
}

/// UITextView wrapper that supports both tap-to-copy and long-press-to-select
struct SelectableTextView: UIViewRepresentable {
    let text: String
    let fontSize: CGFloat
    let onTap: () -> Void
    
    func makeUIView(context: Context) -> UITextView {
        let textView = UITextView()
        textView.isEditable = false
        textView.isSelectable = true
        textView.isScrollEnabled = false
        textView.backgroundColor = .clear
        textView.textContainerInset = .zero
        textView.textContainer.lineFragmentPadding = 0
        textView.font = .systemFont(ofSize: fontSize)
        textView.textColor = .label
        textView.delegate = context.coordinator
        
        // Ensure text wraps and doesn't expand horizontally
        textView.textContainer.lineBreakMode = .byWordWrapping
        textView.textContainer.widthTracksTextView = true
        textView.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        textView.setContentHuggingPriority(.defaultHigh, for: .vertical)
        
        // Add tap gesture for copy
        let tapGesture = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handleTap))
        tapGesture.delegate = context.coordinator
        textView.addGestureRecognizer(tapGesture)
        
        return textView
    }
    
    func updateUIView(_ uiView: UITextView, context: Context) {
        uiView.text = text
        uiView.font = .systemFont(ofSize: fontSize)
        
        // Force layout to calculate correct height
        DispatchQueue.main.async {
            uiView.invalidateIntrinsicContentSize()
        }
    }
    
    func makeCoordinator() -> Coordinator {
        Coordinator(onTap: onTap)
    }
    
    class Coordinator: NSObject, UITextViewDelegate, UIGestureRecognizerDelegate {
        let onTap: () -> Void
        
        init(onTap: @escaping () -> Void) {
            self.onTap = onTap
        }
        
        @objc func handleTap(_ gesture: UITapGestureRecognizer) {
            guard let textView = gesture.view as? UITextView else { return }
            
            // Only trigger copy if no text is selected
            if textView.selectedRange.length == 0 {
                onTap()
            }
        }
        
        // Allow tap gesture to work alongside text selection
        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
            return true
        }
    }
}
