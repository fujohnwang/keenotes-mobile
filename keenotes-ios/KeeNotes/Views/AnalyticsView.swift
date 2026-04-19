import SwiftUI

/// Data analytics view showing notes statistics
struct AnalyticsView: View {
    @EnvironmentObject var appState: AppState
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var colorScheme
    @State private var yearlyData: [(year: Int, count: Int)] = []
    @State private var isLoading = false

    private var isPad: Bool { DeviceType.isPad }
    private var horizontalPadding: CGFloat { DeviceType.horizontalPadding }

    var body: some View {
        ZStack {
            Theme.pageBackground(colorScheme).ignoresSafeArea()

            VStack(spacing: 0) {
                topHeader
                    .padding(.horizontal, horizontalPadding)
                    .padding(.top, 6)
                    .padding(.bottom, 2)

                if isLoading {
                    Spacer()
                    ProgressView("Loading...")
                    Spacer()
                } else if yearlyData.isEmpty {
                    Spacer()
                    VStack(spacing: 12) {
                        Image(systemName: "chart.bar.xaxis")
                            .font(.system(size: 48))
                            .foregroundColor(.gray)
                        Text("No data available")
                            .foregroundColor(.gray)
                    }
                    Spacer()
                } else {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 16) {
                            // Section header
                            Text("Notes per Year")
                                .font(.system(size: isPad ? 17 : 15, weight: .semibold))
                                .foregroundColor(.primary)

                            // Horizontal bar chart
                            YearlyBarChart(data: yearlyData, colorScheme: colorScheme, isPad: isPad)
                                .padding(.vertical, 8)
                        }
                        .padding(.horizontal, horizontalPadding)
                        .padding(.top, 16)

                        // Bottom spacer for dock
                        Color.clear.frame(height: 80)
                    }
                }
            }
        }
        .navigationBarHidden(true)
        .onAppear { appState.isInSubPage = true }
        .onDisappear { appState.isInSubPage = false }
        .onChange(of: appState.subPageDismissTrigger) { _ in
            dismiss()
        }
        .task {
            await loadData()
        }
    }

    private func loadData() async {
        isLoading = true
        defer { isLoading = false }
        do {
            yearlyData = try await appState.databaseService.getNotesCountByYear()
        } catch {
            print("[AnalyticsView] Failed to load data: \(error)")
        }
    }

    private var topHeader: some View {
        TopHeaderView(
            title: "Analytics",
            leftButton: .back { dismiss() }
        )
    }
}

// MARK: - Yearly Bar Chart (Horizontal)

private struct YearlyBarChart: View {
    let data: [(year: Int, count: Int)]
    let colorScheme: ColorScheme
    let isPad: Bool

    private var maxCount: Int { data.map(\.count).max() ?? 1 }
    private var rowHeight: CGFloat { isPad ? 36 : 30 }
    private var rowSpacing: CGFloat { isPad ? 10 : 8 }
    private var yearLabelWidth: CGFloat { isPad ? 48 : 42 }

    var body: some View {
        VStack(spacing: rowSpacing) {
            ForEach(Array(data.enumerated()), id: \.offset) { _, item in
                HStack(spacing: isPad ? 10 : 8) {
                    // Year label (fixed width, right-aligned)
                    Text(String(item.year))
                        .font(.system(size: isPad ? 13 : 12, weight: .medium, design: .rounded))
                        .foregroundColor(.secondary)
                        .frame(width: yearLabelWidth, alignment: .trailing)

                    // Bar
                    GeometryReader { geometry in
                        let barWidth = max(4, geometry.size.width * CGFloat(item.count) / CGFloat(maxCount))

                        RoundedRectangle(cornerRadius: isPad ? 5 : 4)
                            .fill(
                                LinearGradient(
                                    colors: [Theme.brandColor, Theme.brandColor.opacity(0.7)],
                                    startPoint: .leading,
                                    endPoint: .trailing
                                )
                            )
                            .frame(width: barWidth, height: geometry.size.height)
                    }
                    .frame(height: rowHeight)

                    // Count label
                    Text(formatCount(item.count))
                        .font(.system(size: isPad ? 13 : 12, weight: .semibold, design: .rounded))
                        .foregroundColor(Theme.brandColor)
                        .frame(minWidth: isPad ? 44 : 38, alignment: .leading)
                }
            }
        }
        .padding(isPad ? 20 : 16)
        .background(Theme.cardBackground(colorScheme))
        .cornerRadius(isPad ? 16 : 12)
        .shadow(
            color: Theme.cardShadow(colorScheme).color,
            radius: Theme.cardShadow(colorScheme).radius,
            x: Theme.cardShadow(colorScheme).x,
            y: Theme.cardShadow(colorScheme).y
        )
    }

    private func formatCount(_ count: Int) -> String {
        if count >= 10_000 {
            let k = Double(count) / 1_000.0
            return String(format: "%.1fk", k)
        }
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        return formatter.string(from: NSNumber(value: count)) ?? "\(count)"
    }
}
