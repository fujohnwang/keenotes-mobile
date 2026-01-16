import SwiftUI

/// Search view for searching notes
struct SearchView: View {
    @EnvironmentObject var appState: AppState
    @Environment(\.dismiss) var dismiss
    @State private var searchText = ""
    @State private var notes: [Note] = []
    @State private var isLoading = false
    @State private var searchTask: Task<Void, Never>?
    @FocusState private var isSearchFocused: Bool

    // Adaptive layout based on device
    private var isPad: Bool { DeviceType.isPad }
    private var horizontalPadding: CGFloat { DeviceType.horizontalPadding }
    
    var body: some View {
        VStack(spacing: 0) {
            // Search bar
            SearchBar(text: $searchText, isPad: isPad)
                .focused($isSearchFocused)
                .padding(EdgeInsets(top: 8, leading: horizontalPadding, bottom: 4, trailing: horizontalPadding))

            // Header row: Notes count (left) + Sync Channel status (right)
            if !searchText.isEmpty {
                HStack {
                    // Notes count
                    Text("\(notes.count) note(s) - Search results")
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
            }
            
            // Search results
            if searchText.isEmpty {
                Spacer()
                VStack(spacing: 12) {
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 48))
                        .foregroundColor(.gray)
                    Text("Enter keywords to search notes")
                        .foregroundColor(.gray)
                }
                Spacer()
            } else if isLoading {
                Spacer()
                ProgressView("Searching...")
                Spacer()
            } else if notes.isEmpty {
                Spacer()
                VStack(spacing: 12) {
                    Image(systemName: "doc.text")
                        .font(.system(size: 48))
                        .foregroundColor(.gray)
                    Text("No results found")
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
                    }
                }
                .listStyle(.plain)
            }
        }
        .navigationTitle("Search")
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(false)
        .onAppear {
            // Auto-focus search input
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                isSearchFocused = true
            }
        }
        .onChange(of: searchText) { _ in
            // Debounce search - cancel previous task and start new one
            searchTask?.cancel()
            
            if searchText.isEmpty {
                notes = []
                return
            }
            
            searchTask = Task {
                try? await Task.sleep(nanoseconds: 500_000_000)  // 500ms debounce
                guard !Task.isCancelled else { return }
                await performSearch()
            }
        }
    }
    
    private func performSearch() async {
        isLoading = true
        defer { isLoading = false }
        
        do {
            let searchResults = try await appState.databaseService.searchNotes(query: searchText)
            print("[SearchView] Found \(searchResults.count) notes")
            await MainActor.run {
                notes = searchResults
            }
        } catch {
            print("[SearchView] Search failed: \(error)")
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
}

/// Search bar component with focus support
struct SearchBar: View {
    @Binding var text: String
    var focused: FocusState<Bool>.Binding
    private let isPad: Bool

    init(text: Binding<String>) {
        self._text = text
        self.focused = FocusState<Bool>().projectedValue
        self.isPad = false
    }

    init(text: Binding<String>, focused: FocusState<Bool>.Binding) {
        self._text = text
        self.focused = focused
        self.isPad = false
    }

    init(text: Binding<String>, isPad: Bool) {
        self._text = text
        self.focused = FocusState<Bool>().projectedValue
        self.isPad = isPad
    }

    init(text: Binding<String>, focused: FocusState<Bool>.Binding, isPad: Bool) {
        self._text = text
        self.focused = focused
        self.isPad = isPad
    }

    private var fontSize: CGFloat { isPad ? 18 : 17 }
    private var paddingSize: CGFloat { isPad ? 14 : 10 }
    private var cornerRadius: CGFloat { isPad ? 12 : 10 }
    private var iconSize: CGFloat { isPad ? 20 : 17 }
    private var clearIconSize: CGFloat { isPad ? 22 : 17 }

    var body: some View {
        HStack(spacing: isPad ? 12 : 8) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: iconSize))
                .foregroundColor(.gray)

            TextField("Search notes...", text: $text)
                .textFieldStyle(.plain)
                .font(.system(size: fontSize))
                .focused(focused)

            if !text.isEmpty {
                Button(action: {
                    text = ""
                }) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: clearIconSize))
                        .foregroundColor(.gray)
                }
            }
        }
        .padding(paddingSize)
        .background(Color(.systemGray6))
        .cornerRadius(cornerRadius)
    }
}
