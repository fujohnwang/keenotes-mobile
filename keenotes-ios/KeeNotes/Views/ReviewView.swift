import SwiftUI

/// Review view for browsing notes history
struct ReviewView: View {
    @EnvironmentObject var appState: AppState
    @State private var notes: [Note] = []
    @State private var isLoading = false
    @State private var selectedPeriod = 0  // 0: 7 days, 1: 30 days, 2: 90 days, 3: All
    
    private let periods = ["7 days", "30 days", "90 days", "All"]
    private let periodDays = [7, 30, 90, 0]  // 0 means all
    
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
                    .padding(.horizontal)
                    .padding(.top, 8)
                    .padding(.bottom, 8)
                    
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
                    .padding(.horizontal)
                    .padding(.vertical, 8)
                    
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
                                    .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
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
        defer { isLoading = false }
        
        do {
            // Load by period
            let loadedNotes = try await appState.databaseService.getNotesByPeriod(days: periodDays[selectedPeriod])
            
            print("[ReviewView] Loaded \(loadedNotes.count) notes from database")
            await MainActor.run {
                notes = loadedNotes
            }
        } catch {
            print("[ReviewView] Failed to load notes: \(error)")
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
        let count = notes.count
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
        VStack(alignment: .leading, spacing: 12) {
            // Header: Date and Channel
            HStack(spacing: 8) {
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
            
            // Note content (full text with auto wrap)
            // Long press to select text fragments
            Text(note.content)
                .font(.body)
                .foregroundColor(.primary)
                .multilineTextAlignment(.leading)
                .frame(maxWidth: .infinity, alignment: .leading)
                .textSelection(.enabled)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(Color(.systemBackground))
                .shadow(color: Color.black.opacity(0.05), radius: 8, x: 0, y: 2)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(Color(.systemGray5), lineWidth: 1)
        )
        .contentShape(Rectangle())
        .onTapGesture {
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
