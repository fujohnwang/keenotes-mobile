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
            if appState.databaseService.noteCount > 0 {
                Task {
                    if let oldestDate = try? await getOldestNoteDate() {
                        appState.settingsService.firstNoteDate = oldestDate
                    }
                }
            }
        }
        .onChange(of: appState.databaseService.noteCount) { newCount in
            print("[OverviewCard] noteCount changed to: \(newCount)")
            // Update first note date whenever note count changes
            if newCount > 0 {
                Task {
                    print("[OverviewCard] Querying oldest note date...")
                    if let oldestDate = try? await getOldestNoteDate() {
                        print("[OverviewCard] Found oldest date: \(oldestDate), updating firstNoteDate")
                        appState.settingsService.firstNoteDate = oldestDate
                    } else {
                        print("[OverviewCard] Failed to get oldest date")
                    }
                }
            }
        }
        .onChange(of: appState.settingsService.firstNoteDate) { newValue in
            print("[OverviewCard] firstNoteDate changed to: \(newValue ?? "nil")")
            // Automatically update days using when firstNoteDate changes
            updateDaysUsing()
        }
    }
    
    private func updateDaysUsing() {
        guard let firstDateStr = appState.settingsService.firstNoteDate else {
            print("[OverviewCard] firstNoteDate is nil, setting daysUsing to 0")
            daysUsing = 0
            return
        }
        
        print("[OverviewCard] Calculating days using from: \(firstDateStr)")
        
        // Try multiple date formats to handle both "yyyy-MM-dd HH:mm:ss" and "yyyy-MM-dd'T'HH:mm:ss"
        let formatters = [
            { () -> DateFormatter in
                let f = DateFormatter()
                f.dateFormat = "yyyy-MM-dd HH:mm:ss"
                f.locale = Locale(identifier: "en_US_POSIX")
                f.timeZone = TimeZone.current
                return f
            }(),
            { () -> DateFormatter in
                let f = DateFormatter()
                f.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
                f.locale = Locale(identifier: "en_US_POSIX")
                f.timeZone = TimeZone.current
                return f
            }()
        ]
        
        var firstDate: Date?
        for formatter in formatters {
            if let date = formatter.date(from: firstDateStr) {
                firstDate = date
                break
            }
        }
        
        guard let firstDate = firstDate else {
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
