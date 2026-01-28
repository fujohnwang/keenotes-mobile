import SwiftUI
import UIKit

/// SwiftUI 包装器，用于在 SwiftUI 中使用 UIKit 的 SettingsViewController
struct SettingsViewControllerWrapper: UIViewControllerRepresentable {
    @EnvironmentObject var appState: AppState
    let showCoachMarks: Bool
    
    func makeUIViewController(context: Context) -> SettingsHostingController {
        let controller = SettingsHostingController(appState: appState)
        return controller
    }
    
    func updateUIViewController(_ uiViewController: SettingsHostingController, context: Context) {
        if showCoachMarks && !uiViewController.hasShownCoachMarks {
            uiViewController.showCoachMarks()
        }
    }
}

/// UIHostingController 的子类，用于托管 SwiftUI 的 SettingsView 并添加 Coach Marks
class SettingsHostingController: UIHostingController<AnyView> {
    
    private let appState: AppState
    private var coachMarksManager: CoachMarksManager?
    var hasShownCoachMarks = false
    
    // 用于引导的视图引用
    private var tokenFieldView: UIView?
    private var passwordFieldView: UIView?
    
    init(appState: AppState) {
        self.appState = appState
        
        // 创建 SwiftUI 视图
        let settingsView = SettingsViewForCoachMarks(
            appState: appState,
            onTokenFieldAppear: { [weak self] in
                self?.findTokenField()
            },
            onPasswordFieldAppear: { [weak self] in
                self?.findPasswordField()
            }
        )
        
        super.init(rootView: AnyView(settingsView))
        
        // 初始化 CoachMarksManager
        self.coachMarksManager = CoachMarksManager(settingsService: appState.settingsService)
    }
    
    @MainActor required dynamic init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
    
    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        
        // 检查是否需要显示引导
        if !appState.settingsService.isConfigured && !hasShownCoachMarks {
            // 延迟显示，确保视图已经完全渲染
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { [weak self] in
                self?.showCoachMarks()
            }
        }
    }
    
    func showCoachMarks() {
        guard !hasShownCoachMarks else { return }
        guard let tokenField = findTokenField(),
              let passwordField = findPasswordField() else {
            print("[SettingsHostingController] Could not find token or password fields")
            return
        }
        
        hasShownCoachMarks = true
        coachMarksManager?.startCoachMarks(
            in: self,
            tokenField: tokenField,
            passwordField: passwordField
        )
    }
    
    /// 查找 Token 输入框
    private func findTokenField() -> UIView? {
        // 使用多种策略查找
        if let field = findTextField(withPlaceholder: "Token") {
            return field
        }
        // 备用策略：查找包含 "token" 的 accessibility identifier
        return findView(withAccessibilityIdentifier: "tokenField")
    }
    
    /// 查找 Password 输入框
    private func findPasswordField() -> UIView? {
        // 使用多种策略查找
        if let field = findTextField(withPlaceholder: "Password") {
            return field
        }
        // 备用策略：查找包含 "password" 的 accessibility identifier
        return findView(withAccessibilityIdentifier: "passwordField")
    }
    
    /// 递归查找包含指定 placeholder 的 UITextField
    private func findTextField(withPlaceholder placeholder: String, in view: UIView? = nil) -> UIView? {
        let searchView = view ?? self.view
        
        for subview in searchView?.subviews ?? [] {
            if let textField = subview as? UITextField,
               textField.placeholder == placeholder {
                return textField
            }
            
            // 递归查找子视图
            if let found = findTextField(withPlaceholder: placeholder, in: subview) {
                return found
            }
        }
        
        return nil
    }
    
    /// 递归查找包含指定 accessibility identifier 的视图
    private func findView(withAccessibilityIdentifier identifier: String, in view: UIView? = nil) -> UIView? {
        let searchView = view ?? self.view
        
        for subview in searchView?.subviews ?? [] {
            if subview.accessibilityIdentifier == identifier {
                return subview
            }
            
            // 递归查找子视图
            if let found = findView(withAccessibilityIdentifier: identifier, in: subview) {
                return found
            }
        }
        
        return nil
    }
}

/// 用于 Coach Marks 的 SettingsView 变体
/// 添加了回调来通知字段出现
struct SettingsViewForCoachMarks: View {
    let appState: AppState
    let onTokenFieldAppear: () -> Void
    let onPasswordFieldAppear: () -> Void
    
    @State private var endpointUrl = ""
    @State private var token = ""
    @State private var password = ""
    @State private var confirmPassword = ""
    @State private var statusMessage = ""
    @State private var isSuccess = true
    
    private var isPad: Bool { DeviceType.isPad }
    
    private var isSaveEnabled: Bool {
        let e = endpointUrl.trimmingCharacters(in: .whitespaces)
        let t = token.trimmingCharacters(in: .whitespaces)
        let p = password.trimmingCharacters(in: .whitespaces)
        let c = confirmPassword.trimmingCharacters(in: .whitespaces)
        return !e.isEmpty && !t.isEmpty && !p.isEmpty && !c.isEmpty && password == confirmPassword
    }
    
    var body: some View {
        NavigationView {
            Form {
                // Server configuration
                Section(header: Text("Server Configuration")) {
                    TextField("Endpoint URL", text: $endpointUrl)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .font(.system(size: isPad ? 17 : 17))
                    
                    SecureField("Token", text: $token)
                        .textContentType(.init(rawValue: ""))
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .font(.system(size: isPad ? 17 : 17))
                        .accessibilityIdentifier("tokenField")
                        .onAppear {
                            onTokenFieldAppear()
                        }
                }
                
                // Encryption
                Section(header: Text("Encryption"), footer: Text("E2E encryption password. Must match across all devices.")) {
                    SecureField("Password", text: $password)
                        .textContentType(.init(rawValue: ""))
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .font(.system(size: isPad ? 17 : 17))
                        .accessibilityIdentifier("passwordField")
                        .onAppear {
                            onPasswordFieldAppear()
                        }
                    
                    SecureField("Confirm Password", text: $confirmPassword)
                        .textContentType(.init(rawValue: ""))
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .font(.system(size: isPad ? 17 : 17))
                }
                
                // Save button
                Section {
                    Button(action: saveSettings) {
                        HStack {
                            Spacer()
                            Text("Save Settings")
                                .fontWeight(.semibold)
                                .font(.system(size: isPad ? 18 : 17))
                            Spacer()
                        }
                    }
                    .disabled(!isSaveEnabled)
                    
                    if !statusMessage.isEmpty {
                        Text(statusMessage)
                            .font(.system(size: (isPad ? 14 : 13)))
                            .foregroundColor(isSuccess ? .green : .red)
                            .frame(maxWidth: .infinity, alignment: .center)
                    }
                }
            }
            .navigationTitle("KeeNotes Settings")
            .navigationBarTitleDisplayMode(.inline)
            .onAppear(perform: loadSettings)
        }
        .navigationViewStyle(.stack)
    }
    
    private func loadSettings() {
        DispatchQueue.main.async {
            endpointUrl = appState.settingsService.endpointUrl
            token = appState.settingsService.token
            password = appState.settingsService.encryptionPassword
            confirmPassword = appState.settingsService.encryptionPassword
        }
    }
    
    private func saveSettings() {
        guard password == confirmPassword else {
            statusMessage = "Passwords do not match"
            isSuccess = false
            password = ""
            confirmPassword = ""
            return
        }
        
        do {
            appState.settingsService.saveSettings(
                endpoint: endpointUrl,
                token: token,
                password: password
            )
            statusMessage = "Settings saved ✓"
            isSuccess = true
            
            // 重新连接
            appState.webSocketService.disconnect()
            if !endpointUrl.isEmpty && !token.isEmpty {
                appState.webSocketService.connect()
            }
            
            // 切换到笔记页面
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                appState.selectedTab = 0
            }
        } catch {
            statusMessage = "Failed to save: \(error.localizedDescription)"
            isSuccess = false
        }
    }
}
