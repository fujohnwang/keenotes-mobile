import SwiftUI

/// Main tab navigation view
struct MainTabView: View {
    @EnvironmentObject var appState: AppState
    
    var body: some View {
        TabView(selection: $appState.selectedTab) {
            NoteView()
                .tabItem {
                    Image(systemName: "square.and.pencil")
                    Text("Note")
                }
                .tag(0)
            
            ReviewView()
                .tabItem {
                    Image(systemName: "clock.arrow.circlepath")
                    Text("Review")
                }
                .tag(1)
            
            SettingsViewControllerWrapper(
                showCoachMarks: !appState.settingsService.isConfigured
            )
                .tabItem {
                    Image(systemName: "gearshape")
                    Text("Settings")
                }
                .tag(2)
        }
        .accentColor(.blue)
    }
}
