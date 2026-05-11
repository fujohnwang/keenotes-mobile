import SwiftUI

/// Review view for browsing notes history
struct ReviewView: View {
    @EnvironmentObject var appState: AppState
    @State private var notes: [Note] = []
    @State private var isLoading = false
    @State private var isRefreshingNotes = false
    @State private var isLoadingMore = false
    @State private var selectedPeriod = 0  // 0: 7 days, 1: 30 days, 2: 90 days, 3: All
    @State private var totalCount = 0
    @State private var hasMoreData = true
    @State private var enlargedNote: Note? = nil
    @State private var showingAnalytics = false
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
                    // Custom top header with analytics button
                    topHeader
                        .padding(.horizontal, horizontalPadding)
                        .padding(.top, 6)
                        .padding(.bottom, 2)

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
            .navigationBarHidden(true)
            .background(
                NavigationLink(destination: AnalyticsView().environmentObject(appState), isActive: $showingAnalytics) { EmptyView() }
                    .hidden()
            )
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
        guard !isRefreshingNotes else { return }
        isRefreshingNotes = true
        defer { isRefreshingNotes = false }

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

    // MARK: - Top Header

    private var topHeader: some View {
        TopHeaderView(
            title: "KeeNotes Review",
            rightButton: HeaderButton(
                systemName: "chart.bar.xaxis",
                action: { showingAnalytics = true }
            )
        )
    }
}
