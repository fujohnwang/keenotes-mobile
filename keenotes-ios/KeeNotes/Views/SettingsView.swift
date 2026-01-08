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
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                    
                    SecureField("Token", text: $token)
                        .textContentType(.init(rawValue: ""))
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                }
                
                // Encryption
                Section(header: Text("Encryption"), footer: Text("E2E encryption password. Must match across all devices.")) {
                    SecureField("Password", text: $password)
                        .textContentType(.init(rawValue: ""))
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    
                    SecureField("Confirm Password", text: $confirmPassword)
                        .textContentType(.init(rawValue: ""))
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                }
                
                // Preferences
                Section(header: Text("Preferences")) {
                    Toggle("Copy to clipboard on post success", isOn: $appState.settingsService.copyToClipboardOnPost)
                }
                .font(.body)
                
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
                    VStack(spacing: 4) {
                        Text("©2025 王福强(Fuqiang Wang) All Rights Reserved")
                            .font(.footnote)
                            .foregroundColor(.secondary)
                        
                        Link("https://keenotes.afoo.me", destination: URL(string: "https://keenotes.afoo.me")!)
                            .font(.footnote)
                    }
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
        print("[Settings] saveSettings called")
        
        // Validate password match
        guard password == confirmPassword else {
            print("[Settings] Password mismatch")
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
        
        print("[Settings] Configuration: endpoint=\(endpointChanged), token=\(tokenChanged), password=\(passwordChanged)")
        
        // Save settings
        do {
            appState.settingsService.saveSettings(
                endpoint: endpointUrl,
                token: token,
                password: password
            )
            print("[Settings] Settings saved successfully")
        } catch {
            print("[Settings] ERROR saving settings: \(error)")
            statusMessage = "Failed to save: \(error.localizedDescription)"
            isSuccess = false
            return
        }
        
        // Update status message
        let msg = password.isEmpty ? "Settings saved ✓" : "Settings saved ✓ (E2E encryption enabled)"
        
        if configurationChanged && wasConfigured {
            statusMessage = "Configuration changed, reconnecting..."
            isSuccess = true
            print("[Settings] Configuration changed, reconnecting...")
            
            // Reset and reconnect
            Task {
                do {
                    print("[Settings] Disconnecting WebSocket...")
                    appState.webSocketService.disconnect()
                    appState.webSocketService.resetState()
                    
                    print("[Settings] Clearing sync state...")
                    try await appState.databaseService.clearSyncState()
                    
                    if endpointChanged || tokenChanged {
                        print("[Settings] Deleting all notes (endpoint/token changed)...")
                        try await appState.databaseService.deleteAllNotes()
                    }
                    
                    if !endpointUrl.isEmpty && !token.isEmpty {
                        print("[Settings] Reconnecting WebSocket...")
                        try? await Task.sleep(nanoseconds: 500_000_000)
                        appState.webSocketService.connect()
                        await MainActor.run {
                            statusMessage = "\(msg) (Reconnected)"
                            // Switch to Note tab after successful save
                            appState.selectedTab = 0
                        }
                    } else {
                        await MainActor.run {
                            statusMessage = msg
                            appState.selectedTab = 0
                        }
                    }
                    print("[Settings] Reconnection complete")
                } catch {
                    print("[Settings] ERROR during reconnection: \(error)")
                    await MainActor.run {
                        statusMessage = "Error: \(error.localizedDescription)"
                        isSuccess = false
                    }
                }
            }
        } else if !wasConfigured && !endpointUrl.isEmpty && !token.isEmpty {
            // First time configuration
            print("[Settings] First time configuration")
            statusMessage = msg
            isSuccess = true
            appState.webSocketService.connect()
            // Switch to Note tab after successful save
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                appState.selectedTab = 0
            }
        } else {
            print("[Settings] Normal save, reconnecting...")
            statusMessage = msg
            isSuccess = true
            
            // Reconnect if configured
            appState.webSocketService.disconnect()
            if !endpointUrl.isEmpty && !token.isEmpty {
                appState.webSocketService.connect()
            }
            // Switch to Note tab after successful save
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                appState.selectedTab = 0
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
        }
    }
}
