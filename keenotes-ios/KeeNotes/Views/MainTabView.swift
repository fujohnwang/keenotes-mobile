import SwiftUI

/// Main tab navigation view
struct MainTabView: View {
    @EnvironmentObject var appState: AppState
    @State private var selectedTab = 0
    
    var body: some View {
        TabView(selection: $selectedTab) {
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
            
            SettingsView()
                .tabItem {
                    Image(systemName: "gearshape")
                    Text("Settings")
                }
                .tag(2)
        }
        .accentColor(.blue)
    }
}
