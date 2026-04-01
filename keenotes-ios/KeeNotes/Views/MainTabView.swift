import SwiftUI

/// Main view with custom floating dock navigation
struct MainTabView: View {
    @EnvironmentObject var appState: AppState
    @Environment(\.colorScheme) private var colorScheme

    /// Tracks the swipe direction for slide transition
    @State private var swipeDirection: SwipeDirection = .none

    private let tabCount = 3
    /// Minimum horizontal distance to trigger a swipe
    private let swipeThreshold: CGFloat = 50

    var body: some View {
        ZStack(alignment: .bottom) {
            // Layer 1: Content views with swipe gesture
            Group {
                switch appState.selectedTab {
                case 0:
                    NoteView()
                case 1:
                    ReviewView()
                case 2:
                    SettingsView()
                default:
                    NoteView()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .id(appState.selectedTab) // force view identity change for transition
            .transition(.asymmetric(
                insertion: .move(edge: swipeDirection == .left ? .trailing : .leading),
                removal: .move(edge: swipeDirection == .left ? .leading : .trailing)
            ))
            .simultaneousGesture(
                DragGesture(minimumDistance: swipeThreshold)
                    .onEnded { value in
                        let horizontal = value.translation.width
                        let vertical = value.translation.height
                        // Only trigger if horizontal movement clearly dominates
                        guard abs(horizontal) > abs(vertical) * 1.5 else { return }

                        if horizontal < 0, appState.selectedTab < tabCount - 1 {
                            // Swipe left → next tab
                            swipeDirection = .left
                            withAnimation(.easeInOut(duration: 0.25)) {
                                appState.selectedTab += 1
                            }
                        } else if horizontal > 0, appState.selectedTab > 0 {
                            // Swipe right → previous tab
                            swipeDirection = .right
                            withAnimation(.easeInOut(duration: 0.25)) {
                                appState.selectedTab -= 1
                            }
                        }
                    }
            )

            // Layer 2: Fade-out gradient mask above dock
            VStack(spacing: 0) {
                Spacer()
                LinearGradient(
                    colors: [
                        Theme.pageBackground(colorScheme).opacity(0),
                        Theme.pageBackground(colorScheme).opacity(0.85),
                        Theme.pageBackground(colorScheme)
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )
                .frame(height: 80)
                .allowsHitTesting(false)
            }

            // Layer 3: Floating Dock
            FloatingDockView(selectedTab: $appState.selectedTab)
                .padding(.bottom, 16)
        }
        .ignoresSafeArea(.container, edges: .bottom)
        .ignoresSafeArea(.keyboard, edges: .bottom)
    }
}

// MARK: - Swipe Direction

private enum SwipeDirection {
    case none, left, right
}
