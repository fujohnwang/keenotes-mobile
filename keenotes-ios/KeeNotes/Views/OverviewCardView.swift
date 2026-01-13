import SwiftUI

/// Overview Card showing total notes and days using KeeNotes
struct OverviewCardView: View {
    @EnvironmentObject var appState: AppState
    @State private var daysUsing: Int = 0
    
    var body: some View {
        HStack(spacing: 0) {
            // Total Notes
            VStack(spacing: 8) {
                Text("\(appState.databaseService.noteCount)")
                    .font(.system(size: 32, weight: .bold))
                    .foregroundColor(.blue)
                
                Text("Total Notes")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            .frame(maxWidth: .infinity)
            
            // Divider
            Rectangle()
                .fill(Color.secondary.opacity(0.2))
                .frame(width: 1)
                .padding(.vertical, 8)
            
            // Days Using
            VStack(spacing: 8) {
                Text("\(daysUsing)")
                    .font(.system(size: 32, weight: .bold))
                    .foregroundColor(.blue)
                
                Text("Days Using")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            .frame(maxWidth: .infinity)
        }
        .padding()
        .background(Color(.systemGray6))
        .cornerRadius(12)
        .onAppear {
            updateDaysUsing()
        }
        .onChange(of: appState.databaseService.noteCount) { newCount in
            // Initialize first note date if needed
            if newCount > 0 && appState.settingsService.firstNoteDate == nil {
                Task {
                    if let oldestDate = try? await getOldestNoteDate() {
                        appState.settingsService.firstNoteDate = oldestDate
                        updateDaysUsing()
                    }
                }
            }
        }
    }
    
    private func updateDaysUsing() {
        guard let firstDateStr = appState.settingsService.firstNoteDate else {
            daysUsing = 0
            return
        }
        
        let formatter = ISO8601DateFormatter()
        guard let firstDate = formatter.date(from: firstDateStr) else {
            daysUsing = 0
            return
        }
        
        let calendar = Calendar.current
        let days = calendar.dateComponents([.day], from: firstDate, to: Date()).day ?? 0
        daysUsing = days + 1
    }
    
    private func getOldestNoteDate() async throws -> String? {
        guard let dbQueue = appState.databaseService.dbQueue else {
            return nil
        }
        
        return try await dbQueue.read { db in
            try String.fetchOne(db, sql: "SELECT MIN(createdAt) FROM notes")
        }
    }
}
