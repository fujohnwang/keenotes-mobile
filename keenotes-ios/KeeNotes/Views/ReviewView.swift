import SwiftUI

/// Review view for browsing notes history
struct ReviewView: View {
    @EnvironmentObject var appState: AppState
    @State private var notes: [Note] = []
    @State private var searchText = ""
    @State private var isLoading = false
    @State private var selectedPeriod = 0  // 0: 7 days, 1: 30 days, 2: 90 days, 3: All
    @State private var searchTask: Task<Void, Never>?
    
    private let periods = ["7 days", "30 days", "90 days", "All"]
    private let periodDays = [7, 30, 90, 0]  // 0 means all
    
    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                // Search bar (moved to top)
                SearchBar(text: $searchText)
                    .padding(.horizontal)
                    .padding(.top, 8)
                    .padding(.bottom, 4)
                
                // Period selector
                Picker("Period", selection: $selectedPeriod) {
                    ForEach(0..<periods.count, id: \.self) { index in
                        Text(periods[index]).tag(index)
                    }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal)
                .padding(.vertical, 8)
                
                // Notes list
                if isLoading {
                    Spacer()
                    ProgressView("Loading...")
                    Spacer()
                } else if appState.webSocketService.syncStatus == .syncing && notes.isEmpty {
                    // Syncing state - show when actively syncing and no local notes yet
                    Spacer()
                    VStack(spacing: 12) {
                        ProgressView()
                            .scaleEffect(1.2)
                        Text("Syncing notes...")
                            .foregroundColor(.secondary)
                    }
                    Spacer()
                } else if notes.isEmpty {
                    Spacer()
                    VStack(spacing: 12) {
                        Image(systemName: "doc.text")
                            .font(.system(size: 48))
                            .foregroundColor(.gray)
                        Text(searchText.isEmpty ? "No notes yet" : "No results found")
                            .foregroundColor(.gray)
                    }
                    Spacer()
                } else {
                    List {
                        ForEach(notes) { note in
                            NoteRow(note: note)
                        }
                    }
                    .listStyle(.plain)
                    .refreshable {
                        await loadNotes()
                    }
                }
            }
            .navigationTitle("Review")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(action: { Task { await loadNotes() } }) {
                        Image(systemName: "arrow.clockwise")
                    }
                }
            }
        }
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
        .onChange(of: searchText) { _ in
            // Debounce search - cancel previous task and start new one
            searchTask?.cancel()
            searchTask = Task {
                try? await Task.sleep(nanoseconds: 500_000_000)  // 500ms debounce
                guard !Task.isCancelled else { return }
                await loadNotes()
            }
        }
    }
    
    private func loadNotes() async {
        isLoading = true
        defer { isLoading = false }
        
        do {
            let loadedNotes: [Note]
            
            if searchText.isEmpty {
                // Load by period
                loadedNotes = try await appState.databaseService.getNotesByPeriod(days: periodDays[selectedPeriod])
            } else {
                // Search
                loadedNotes = try await appState.databaseService.searchNotes(query: searchText)
            }
            
            print("[ReviewView] Loaded \(loadedNotes.count) notes from database")
            await MainActor.run {
                notes = loadedNotes
            }
        } catch {
            print("[ReviewView] Failed to load notes: \(error)")
        }
    }
}

/// Search bar component
struct SearchBar: View {
    @Binding var text: String
    
    var body: some View {
        HStack {
            Image(systemName: "magnifyingglass")
                .foregroundColor(.gray)
            
            TextField("Search notes...", text: $text)
                .textFieldStyle(.plain)
            
            if !text.isEmpty {
                Button(action: {
                    text = ""
                }) {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundColor(.gray)
                }
            }
        }
        .padding(10)
        .background(Color(.systemGray6))
        .cornerRadius(10)
    }
}

/// Single note row in the list
struct NoteRow: View {
    let note: Note
    @State private var showCopiedAlert = false
    
    private var formattedDate: String {
        // Parse ISO date or custom format
        let dateFormatter = DateFormatter()
        dateFormatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        
        if let date = dateFormatter.date(from: note.createdAt) {
            let displayFormatter = DateFormatter()
            displayFormatter.dateStyle = .medium
            displayFormatter.timeStyle = .short
            return displayFormatter.string(from: date)
        }
        
        // Try alternative format
        dateFormatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
        if let date = dateFormatter.date(from: note.createdAt) {
            let displayFormatter = DateFormatter()
            displayFormatter.dateStyle = .medium
            displayFormatter.timeStyle = .short
            return displayFormatter.string(from: date)
        }
        
        return note.createdAt
    }
    
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(note.content)
                .font(.body)
                .lineLimit(3)
            
            Text(formattedDate)
                .font(.caption)
                .foregroundColor(.secondary)
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .onLongPressGesture(minimumDuration: 0.5) {
            copyToClipboard()
        }
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
