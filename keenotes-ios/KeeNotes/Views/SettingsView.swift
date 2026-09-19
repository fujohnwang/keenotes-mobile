import SwiftUI

/// Settings view with configuration options and easter egg
struct SettingsView: View {
    @EnvironmentObject var appState: AppState
    var body: some View { SettingsForm(draft: appState.settingsDraft) }
}

private struct SettingsForm: View {
    @ObservedObject var draft: SettingsDraft
    @EnvironmentObject var appState: AppState

    // Adaptive layout based on device
    private var isPad: Bool { DeviceType.isPad }
    private var horizontalPadding: CGFloat { DeviceType.horizontalPadding }
    @Environment(\.colorScheme) private var colorScheme

    @State private var showHistory = false
    @State private var showSubscription = false
    @State private var saving = false

    @State private var statusMessage = ""
    @State private var isSuccess = true
    
    // Hidden message draft
    @State private var hiddenMessageDraft = ""
    
    // 向导状态
    @State private var showWizard = false
    @State private var wizardTask: Task<Void, Never>?
    
    // 焦点状态
    @FocusState private var focusedField: String?
    
    // 输入框位置状态
    @State private var fieldFrames: [String: CGRect] = [:]

    // Computed property for Save button enabled state
    private var isSaveEnabled: Bool {
        let e = draft.endpoint.trimmingCharacters(in: .whitespaces)
        let t = draft.token.trimmingCharacters(in: .whitespaces)
        let p = draft.pin.trimmingCharacters(in: .whitespaces)
        let c = draft.confirmPin.trimmingCharacters(in: .whitespaces)
        return !e.isEmpty && !t.isEmpty && !p.isEmpty && !c.isEmpty && draft.pin == draft.confirmPin
    }

    // Easter egg state
    @State private var copyrightTapCount = 0
    @State private var lastTapTime: Date = .distantPast
    @State private var showDebugSection = false
    @State private var showDebugView = false

    var body: some View {
        ZStack {
            Theme.pageBackground(colorScheme).ignoresSafeArea()

            NavigationView {
                ScrollViewReader { scrollProxy in
                VStack(spacing: 0) {
                    TopHeaderView(title: NSLocalizedString("KeeNotes Settings", comment: "Settings title"))
                        .padding(.horizontal, horizontalPadding)
                        .padding(.top, 6)
                        .padding(.bottom, 2)

                Form {
                    // Server configuration
                    Section(header: HStack(spacing: 8) {
                        Text("Server Configuration")
                            .modifier(Theme.SectionHeaderStyle())
                            .fixedSize(horizontal: false, vertical: true)
                        Button {
                            showWizard = false; focusedField = nil; showHistory = true
                        } label: {
                            Image(systemName: "clock.arrow.circlepath")
                                .font(.footnote)
                                .foregroundColor(.secondary)
                                .padding(6)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(Text("History"))
                        .accessibilityIdentifier("credentialHistory")
                        Spacer(minLength: 0)
                        Button {
                            showWizard = false; focusedField = nil; showSubscription = true
                        } label: {
                            Text("Purchase")
                                .fixedSize(horizontal: true, vertical: false)
                                .font(.footnote)
                                .foregroundColor(.white)
                        }
                        .accessibilityIdentifier("subscriptionEntry")
                        .buttonStyle(.borderedProminent)
                        .tint(Theme.brandColor)
                    }
                    .buttonStyle(.bordered)
                    .buttonBorderShape(.roundedRectangle)
                    .controlSize(.small)
                    .tint(.gray)
                    .textCase(nil)) {
                    TextField("Endpoint URL", text: $draft.endpoint)
                        .accessibilityIdentifier("endpointInput")
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .font(.system(size: isPad ? 17 : 17))
                        .listRowBackground(Color.clear)

                    SecureField("Token", text: $draft.token)
                        .accessibilityIdentifier("tokenInput")
                        .textContentType(.init(rawValue: ""))
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .font(.system(size: isPad ? 17 : 17))
                        .captureFrame(fieldId: "token")
                        .focused($focusedField, equals: "token")
                        .listRowBackground(Color.clear)
                }

                // Encryption
                Section(header: Text("Encryption").modifier(Theme.SectionHeaderStyle()),
                        footer: Text("E2E encryption password. Must match across all devices.")
                            .font(.system(size: 12))
                            .foregroundColor(Color(.systemGray3))) {
                    SecureField("Password", text: $draft.pin)
                        .accessibilityIdentifier("pinInput")
                        .textContentType(.init(rawValue: ""))
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .font(.system(size: isPad ? 17 : 17))
                        .captureFrame(fieldId: "encryptionPassword")
                        .focused($focusedField, equals: "encryptionPassword")
                        .listRowBackground(Color.clear)

                    SecureField("Confirm Password", text: $draft.confirmPin)
                        .accessibilityIdentifier("confirmPinInput")
                        .textContentType(.init(rawValue: ""))
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .font(.system(size: isPad ? 17 : 17))
                        .captureFrame(fieldId: "confirmPassword")
                        .focused($focusedField, equals: "confirmPassword")
                        .listRowBackground(Color.clear)
                }

                // Save button — visually belongs to the config above, so less top spacing, more bottom
                Section(footer: Spacer().frame(height: 24)) {
                    Button(action: saveSettings) {
                        Text("Save Settings")
                            .fontWeight(.semibold)
                            .font(.system(size: isPad ? 18 : 17))
                            .foregroundColor(Color.white)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 14)
                    }
                    .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 8, trailing: 16))
                    .listRowBackground(Color.clear)
                    .background(
                        RoundedRectangle(cornerRadius: 12)
                            .fill(isSaveEnabled ? Theme.brandColor : Color(.systemGray4))
                    )
                    .disabled(!isSaveEnabled || saving || appState.configurationCoordinator.isApplying)
                    .accessibilityIdentifier("saveSettings")

                    Text(connectionDescription)
                        .accessibilityIdentifier("connectionStatus")
                        .font(.caption).foregroundColor(.secondary)
                        .listRowBackground(Color.clear)
                    if let error = appState.configurationCoordinator.errorMessage ?? appState.settingsService.configurationError {
                        Text(error).foregroundColor(.red)
                        Button("Retry Local Recovery") {
                            Task {
                                do {
                                    try await appState.configurationCoordinator.recover()
                                    appState.webSocketService.connect()
                                    statusMessage = ""
                                    isSuccess = true
                                } catch { statusMessage = error.localizedDescription; isSuccess = false }
                            }
                        }
                    }
                    // Status message
                    if !statusMessage.isEmpty {
                        Text(statusMessage)
                            .accessibilityIdentifier("configurationStatus")
                            .font(.system(size: (isPad ? 14 : 13)))
                            .foregroundColor(isSuccess ? Theme.brandColor : .red)
                            .frame(maxWidth: .infinity, alignment: .center)
                            .listRowBackground(Color.clear)
                    }
                }

                // Preferences
                Section(header: Text("Preferences").modifier(Theme.SectionHeaderStyle())) {
                    Toggle("Copy to clipboard on post success", isOn: Binding(
                        get: { appState.settingsService.copyToClipboardOnPost },
                        set: { appState.settingsService.copyToClipboardOnPost = $0 }
                    ))
                    .listRowBackground(Color.clear)
                    .padding(.vertical, 6)

                    Toggle("Show Overview Card", isOn: Binding(
                        get: { appState.settingsService.showOverviewCard },
                        set: { appState.settingsService.showOverviewCard = $0 }
                    ))
                    .listRowBackground(Color.clear)
                    .padding(.vertical, 6)

                    Toggle("Auto-focus input on launch", isOn: Binding(
                        get: { appState.settingsService.autoFocusInputOnLaunch },
                        set: { appState.settingsService.autoFocusInputOnLaunch = $0 }
                    ))
                    .listRowBackground(Color.clear)
                    .padding(.vertical, 6)

                    Toggle("Auto-start voice input on launch", isOn: Binding(
                        get: { appState.settingsService.autoStartDictation },
                        set: { appState.settingsService.autoStartDictation = $0 }
                    ))
                    .listRowBackground(Color.clear)
                    .padding(.vertical, 6)

                    Toggle("Confetti on post success", isOn: Binding(
                        get: { appState.settingsService.confettiOnPostSuccess },
                        set: { appState.settingsService.confettiOnPostSuccess = $0 }
                    ))
                    .listRowBackground(Color.clear)
                    .padding(.vertical, 6)

                    Toggle("Show Sync Channel Status", isOn: Binding(
                        get: { appState.settingsService.showSyncChannelStatus },
                        set: { appState.settingsService.showSyncChannelStatus = $0 }
                    ))
                    .listRowBackground(Color.clear)
                    .padding(.vertical, 6)

                    Toggle("Compact date format", isOn: Binding(
                        get: { appState.settingsService.compactDateFormat },
                        set: { appState.settingsService.compactDateFormat = $0 }
                    ))
                    .listRowBackground(Color.clear)
                    .padding(.vertical, 6)

                    Toggle("On this day in years past", isOn: Binding(
                        get: { appState.settingsService.showOnThisDayInYearsPast },
                        set: { appState.settingsService.showOnThisDayInYearsPast = $0 }
                    ))
                    .listRowBackground(Color.clear)
                    .padding(.vertical, 6)
                }
                .font(.system(size: isPad ? 17 : 17))
                .toggleStyle(SwitchToggleStyle(tint: Theme.brandColor))

                // Hidden Watermark
                Section(header: Text("Hidden Watermark").modifier(Theme.SectionHeaderStyle()),
                        footer: Text("When set, an invisible watermark is embedded into copied note content for traceability.")
                            .font(.system(size: 12))
                            .foregroundColor(Color(.systemGray3))) {
                    HStack(spacing: 8) {
                        TextField("Enter hidden message...", text: $hiddenMessageDraft)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .font(.system(size: isPad ? 17 : 17))
                            .submitLabel(.done)
                            .onSubmit { saveHiddenMessage() }
                            .focused($focusedField, equals: "hiddenMessage")

                        Button(action: { saveHiddenMessage() }) {
                            Text("Save")
                                .font(.system(size: isPad ? 14 : 13, weight: .medium))
                                .padding(.horizontal, 12)
                                .padding(.vertical, 6)
                                .background(hiddenMessageDraft == appState.settingsService.hiddenMessage ? Color(.systemGray5) : Theme.brandColor)
                                .foregroundColor(hiddenMessageDraft == appState.settingsService.hiddenMessage ? .secondary : .white)
                                .clipShape(Capsule())
                        }
                        .disabled(hiddenMessageDraft == appState.settingsService.hiddenMessage)
                    }
                    .listRowBackground(Color.clear)
                }
                .id("hiddenWatermark")

                // Debug section (hidden by default)
                if showDebugSection {
                    Section(header: Text("Debug").modifier(Theme.SectionHeaderStyle())) {
                        Button("Open Debug View") {
                            showDebugView = true
                        }
                        .font(.system(size: isPad ? 17 : 17))
                        .listRowBackground(Color.clear)
                    }
                }

                // Copyright with easter egg
                Section(footer: Spacer().frame(height: 60)) {
                    VStack(spacing: 4) {
                        Text("©2025 王福强(Fuqiang Wang) All Rights Reserved")
                            .font(.system(size: isPad ? 13 : 12))
                            .foregroundColor(.secondary)

                        Link("https://keenotes.afoo.me", destination: URL(string: "https://keenotes.afoo.me")!)
                            .font(.system(size: isPad ? 13 : 12))
                    }
                    .frame(maxWidth: .infinity, alignment: .center)
                    .contentShape(Rectangle())
                    .onTapGesture {
                        handleCopyrightTap()
                    }
                    .listRowBackground(Color.clear)
                }
                }
                .modifier(FormBackgroundModifier(colorScheme: colorScheme))
                .navigationBarHidden(true)
                .onAppear(perform: loadSettings)
                .onDisappear { wizardTask?.cancel(); draft.invalidateDelivery() }
                .onChange(of: showSubscription) { opened in coordinateWizard(sheetOpened: opened) }
                .onChange(of: showHistory) { opened in coordinateWizard(sheetOpened: opened) }
                .sheet(isPresented: $showSubscription) {
                    SubscriptionView(service: appState.purchaseService, draft: draft) {
                        statusMessage = NSLocalizedString("Credentials filled. Save to start using them.", comment: "IAP and connection configuration")
                        isSuccess = true
                        showSubscription = false
                    }
                }
                .sheet(isPresented: $showHistory) {
                    CredentialHistoryView {
                        statusMessage = NSLocalizedString("Configuration switched. See connection status below.", comment: "IAP and connection configuration")
                        isSuccess = true
                    }
                }
                .sheet(isPresented: $showDebugView) {
                    DebugView()
                }
                .onChange(of: focusedField) { field in
                    if field == "hiddenMessage" {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                            withAnimation {
                                scrollProxy.scrollTo("hiddenWatermark", anchor: .bottom)
                            }
                        }
                    }
                }
                } // VStack
                .background(Theme.pageBackground(colorScheme).ignoresSafeArea())
                } // ScrollViewReader
            }
            .navigationViewStyle(.stack)
            .onPreferenceChange(FieldFramePreferenceKey.self) { frames in
                // 将 frame 信息保存到 state
                self.fieldFrames = frames
                // print("[SettingsView] Captured frames: \(frames)")
            }
            
            // 向导覆盖层
            OnboardingWizardOverlay(
                showWizard: $showWizard,
                fieldFrames: fieldFrames,
                settingsService: appState.settingsService,
                onFocusField: { fieldId in
                    if !showSubscription && !showHistory { focusedField = fieldId }
                }
            )
        }
    }

    private func loadSettings() {
        draft.loadOnce(appState.settingsService.configuration)
        hiddenMessageDraft = appState.settingsService.hiddenMessage
        checkAndShowWizard()
    }

    private var connectionDescription: String {
        switch appState.webSocketService.connectionState {
        case .connected: return NSLocalizedString("Connected", comment: "IAP and connection configuration")
        case .connecting: return NSLocalizedString("Connecting…", comment: "IAP and connection configuration")
        case .disconnected: return NSLocalizedString("Not connected. Your saved local configuration is preserved.", comment: "IAP and connection configuration")
        }
    }

    private func saveHiddenMessage() {
        appState.settingsService.hiddenMessage = hiddenMessageDraft
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    }

    private func saveSettings() {
        guard !saving, draft.pin == draft.confirmPin else { return }
        let target = draft.configuration
        draft.invalidateDelivery()
        saving = true
        Task {
            defer { saving = false }
            do {
                try await appState.configurationCoordinator.apply(target, source: appState.purchaseService.credentialsStore.source(for: target))
                statusMessage = NSLocalizedString("Configuration saved. See connection status below.", comment: "IAP and connection configuration")
                isSuccess = true
            } catch { statusMessage = error.localizedDescription; isSuccess = false }
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
    
    private func coordinateWizard(sheetOpened: Bool) {
        wizardTask?.cancel()
        if sheetOpened { showWizard = false; focusedField = nil }
        else { checkAndShowWizard() }
    }

    private func checkAndShowWizard() {
        wizardTask?.cancel()
        guard !appState.settingsService.isConfigured else { showWizard = false; return }
        wizardTask = Task {
            do { try await Task.sleep(nanoseconds: 500_000_000) } catch { return }
            guard !Task.isCancelled, !showSubscription, !showHistory else { return }
            showWizard = true
        }
    }
}

/// Modifier to hide Form default background (iOS 16+) with fallback
struct FormBackgroundModifier: ViewModifier {
    let colorScheme: ColorScheme

    func body(content: Content) -> some View {
        if #available(iOS 16.0, *) {
            content
                .scrollContentBackground(.hidden)
                .background(Theme.pageBackground(colorScheme))
        } else {
            content
        }
    }
}
