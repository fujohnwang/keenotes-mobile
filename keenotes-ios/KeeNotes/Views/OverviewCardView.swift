import SwiftUI

/// Overview Card showing total notes and days using KeeNotes
struct OverviewCardView: View {
    @EnvironmentObject var appState: AppState
    @State private var daysUsing: Int = 0

    // Adaptive layout based on device
    private var isPad: Bool { DeviceType.isPad }
    private var cardHeight: CGFloat { isPad ? 112 : 88 }
    private var cardPadding: CGFloat { isPad ? 16 : 12 }
    private var numberFontSize: CGFloat { isPad ? 42 : 32 }
    private var labelFontSize: CGFloat { isPad ? 10 : 8 }
    
    var body: some View {
        HStack(spacing: 0) {
            // Total Notes
            VStack(spacing: 1) {
                Text("\(appState.databaseService.noteCount)")
                    .font(.system(size: numberFontSize, weight: .bold))
                    .foregroundColor(.blue)

                Text("Total Notes")
                    .font(.system(size: labelFontSize))
                    .foregroundColor(.secondary)
            }
            .frame(maxWidth: .infinity)

            // Divider
            Rectangle()
                .fill(Color.secondary.opacity(0.2))
                .frame(width: 1)
                .padding(.vertical, 2)

            // Days Using
            VStack(spacing: 1) {
                Text("\(daysUsing)")
                    .font(.system(size: numberFontSize, weight: .bold))
                    .foregroundColor(.blue)

                Text("Days Using")
                    .font(.system(size: labelFontSize))
                    .foregroundColor(.secondary)
            }
            .frame(maxWidth: .infinity)
        }
        .frame(height: cardHeight)
        .padding(.horizontal, cardPadding)
        .background(Color(.systemBackground))
        .cornerRadius(DeviceType.cornerRadius)
        .overlay(
            RoundedRectangle(cornerRadius: DeviceType.cornerRadius)
                .stroke(Color(.systemGray4), lineWidth: 1)
        )
        .onAppear {
            updateDaysUsing()
            // Initialize first note date if needed and there are notes
            if appState.databaseService.noteCount > 0 && appState.settingsService.firstNoteDate == nil {
                Task {
                    if let oldestDate = try? await getOldestNoteDate() {
                        appState.settingsService.firstNoteDate = oldestDate
                        updateDaysUsing()
                    }
                }
            }
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
            print("[OverviewCard] firstNoteDate is nil, setting daysUsing to 0")
            daysUsing = 0
            return
        }
        
        print("[OverviewCard] Calculating days using from: \(firstDateStr)")
        
        // Use DateFormatter to parse database date format: "2026-01-04T12:03:27"
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone.current
        
        guard let firstDate = formatter.date(from: firstDateStr) else {
            print("[OverviewCard] Failed to parse date: \(firstDateStr)")
            daysUsing = 0
            return
        }
        
        print("[OverviewCard] Parsed firstDate: \(firstDate)")
        
        // Calculate days using the same logic as Android/Desktop
        let calendar = Calendar.current
        let firstDateStart = calendar.startOfDay(for: firstDate)
        let todayStart = calendar.startOfDay(for: Date())
        let days = calendar.dateComponents([.day], from: firstDateStart, to: todayStart).day ?? 0
        let calculatedDays = days + 1
        
        print("[OverviewCard] Calculated days: \(calculatedDays) (raw: \(days))")
        daysUsing = calculatedDays
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
