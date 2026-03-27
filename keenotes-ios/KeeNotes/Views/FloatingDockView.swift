import SwiftUI

/// Floating dock item data
struct DockItem {
    let index: Int
    let icon: String        // SF Symbol name
    let label: String
}

/// A single dock tab button with indicator dot
struct DockTabButton: View {
    let item: DockItem
    let isSelected: Bool
    let action: () -> Void

    private let dotSize: CGFloat = 4

    var body: some View {
        Button(action: action) {
            VStack(spacing: 3) {
                Image(systemName: item.icon)
                    .font(.system(size: 18, weight: isSelected ? .semibold : .regular))
                    .foregroundColor(isSelected ? Theme.brandColor : .gray)

                Text(item.label)
                    .font(.system(size: 10, weight: isSelected ? .semibold : .regular))
                    .foregroundColor(isSelected ? Theme.brandColor : .gray)

                // Indicator dot — only visible when selected
                Circle()
                    .fill(isSelected ? Theme.brandColor : Color.clear)
                    .frame(width: dotSize, height: dotSize)
            }
            .frame(width: 72, height: 52)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// Floating capsule dock that hovers above content (Layer 3)
struct FloatingDockView: View {
    @Binding var selectedTab: Int
    @Environment(\.colorScheme) private var colorScheme

    private let items: [DockItem] = [
        DockItem(index: 0, icon: "square.and.pencil", label: "Note"),
        DockItem(index: 1, icon: "clock.arrow.circlepath", label: "Review"),
        DockItem(index: 2, icon: "gearshape", label: "Settings"),
    ]

    var body: some View {
        HStack(spacing: 0) {
            ForEach(items, id: \.index) { item in
                DockTabButton(item: item, isSelected: selectedTab == item.index) {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        selectedTab = item.index
                    }
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(
            Capsule()
                .fill(.ultraThinMaterial)
                .shadow(color: Color.black.opacity(colorScheme == .dark ? 0.4 : 0.12), radius: 12, x: 0, y: 4)
        )
    }
}
