import SwiftUI

/// Settings view with configuration options and easter egg
struct SettingsView: View {
    @EnvironmentObject var appState: AppState
    
    @State private var endpointUrl = ""
    @State private var token = ""
    @State private var password = ""
    @State private var confirmPassword = ""
    
    @State private var statusMessage = ""
    @State private var isSuccess = true
    
    // Easter egg state
    @State private var copyrightTapCount = 0
    @State private var lastTapTime: Date = .distantPast
    @State private var showDebugSection = false
    @State private var showDebugView = false
    
    var body: some View {
        NavigationView {
            Form {
                // Server configuration
                Section(header: Text("Server Configuration")) {
                    TextField("Endpoint URL", text: $endpointUrl)
                        .textContentType(.URL)
                        .autocapitalization(.none)
                        .keyboardType(.URL)
                    
                    SecureField("Token", text: $token)
                        .textContentType(.password)
                }
                
                // Encryption
                Section(header: Text("Encryption"), footer: Text("E2E encryption password. Must match across all devices.")) {
                    SecureField("Password", text: $password)
                        .textContentType(.newPassword)
                    
                    SecureField("Confirm Password", text: $confirmPassword)
                        .textContentType(.newPassword)
                }
                
                // Save button
                Section {
                    Button(action: saveSettings) {
                        HStack {
                            Spacer()
                            Text("Save Settings")
                                .fontWeight(.semibold)
                            Spacer()
                        }
                    }
                    
                    // Status message
                    if !statusMessage.isEmpty {
                        Text(statusMessage)
                            .font(.footnote)
                            .foregroundColor(isSuccess ? .green : .red)
                    }
                }
                
                // Debug section (hidden by default)
                if showDebugSection {
                    Section(header: Text("Debug")) {
                        Button("Open Debug View") {
                            showDebugView = true
                        }
                    }
                }
                
                // Copyright with easter egg
                Section {
                    Text("© 2025 Keevol. All rights reserved.")
                        .font(.footnote)
                        .foregroundColor(.secondary)
                        .frame(maxWidth: .infinity, alignment: .center)
                        .contentShape(Rectangle())
                        .onTapGesture {
                            handleCopyrightTap()
                        }
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .onAppear(perform: loadSettings)
            .sheet(isPresented: $showDebugView) {
                DebugView()
            }
        }
    }
    
    private func loadSettings() {
        endpointUrl = appState.settingsService.endpointUrl
        token = appState.settingsService.token
        password = appState.settingsService.encryptionPassword
        confirmPassword = appState.settingsService.encryptionPassword
    }
    
    private func saveSettings() {
        // Validate password match
        guard password == confirmPassword else {
            statusMessage = "Passwords do not match"
            isSuccess = false
            password = ""
            confirmPassword = ""
            return
        }
        
        let oldEndpoint = appState.settingsService.endpointUrl
        let oldToken = appState.settingsService.token
        let oldPassword = appState.settingsService.encryptionPassword
        let wasConfigured = !oldEndpoint.isEmpty && !oldToken.isEmpty
        
        let endpointChanged = oldEndpoint != endpointUrl
        let tokenChanged = oldToken != token
        let passwordChanged = oldPassword != password
        let configurationChanged = endpointChanged || tokenChanged || passwordChanged
        
        // Save settings
        appState.settingsService.saveSettings(
            endpoint: endpointUrl,
            token: token,
            password: password
        )
        
        // Update status message
        let msg = password.isEmpty ? "Settings saved ✓" : "Settings saved ✓ (E2E encryption enabled)"
        
        if configurationChanged && wasConfigured {
            statusMessage = "Configuration changed, reconnecting..."
            isSuccess = true
            
            // Reset and reconnect
            Task {
                appState.webSocketService.disconnect()
                appState.webSocketService.resetState()
                
                try? await appState.databaseService.clearSyncState()
                
                if endpointChanged || tokenChanged {
                    try? await appState.databaseService.deleteAllNotes()
                }
                
                if !endpointUrl.isEmpty && !token.isEmpty {
                    try? await Task.sleep(nanoseconds: 500_000_000)
                    appState.webSocketService.connect()
                    await MainActor.run {
                        statusMessage = "\(msg) (Reconnected)"
                    }
                } else {
                    await MainActor.run {
                        statusMessage = msg
                    }
                }
            }
        } else if !wasConfigured && !endpointUrl.isEmpty && !token.isEmpty {
            // First time configuration
            statusMessage = msg
            isSuccess = true
            appState.webSocketService.connect()
        } else {
            statusMessage = msg
            isSuccess = true
            
            // Reconnect if configured
            appState.webSocketService.disconnect()
            if !endpointUrl.isEmpty && !token.isEmpty {
                appState.webSocketService.connect()
            }
        }
    }
    
    private func handleCopyrightTap() {
        let now = Date()
        
        // Reset if more than 1 second since last tap
        if now.timeIntervalSince(lastTapTime) > 1.0 {
            copyrightTapCount = 0
        }
        lastTapTime = now
        copyrightTapCount += 1
        
        if copyrightTapCount >= 7 && !showDebugSection {
            showDebugSection = true
            statusMessage = "Debug mode enabled!"
            isSuccess = true
        } else if copyrightTapCount >= 4 && copyrightTapCount < 7 {
            let remaining = 7 - copyrightTapCount
            statusMessage = "\(remaining) more tap(s) to enable debug mode"
            isSuccess = true
        }
    }
}
