import SwiftUI

/// Main view with custom floating dock navigation
struct MainTabView: View {
    @EnvironmentObject var appState: AppState
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        ZStack(alignment: .bottom) {
            // Layer 1: Content views
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
