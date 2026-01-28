import SwiftUI

/// 首次启动配置向导 - 高亮输入框并在其下方显示提示
struct OnboardingWizardOverlay: View {
    @Binding var showWizard: Bool
    @State private var currentStep = 0
    @State private var tokenFieldFrame: CGRect = .zero
    @State private var passwordFieldFrame: CGRect = .zero
    let settingsService: SettingsService
    
    // 检测系统语言
    private func isChinese() -> Bool {
        let language = Locale.current.languageCode ?? ""
        let region = Locale.current.regionCode ?? ""
        return language == "zh" || region == "CN" || region == "TW" || region == "HK"
    }
    
    // 定义向导步骤
    private var steps: [WizardStep] {
        let chinese = isChinese()
        return [
            WizardStep(
                fieldId: "token",
                title: chinese ? "访问令牌" : "Access Token",
                description: chinese ? "请输入访问令牌，用于安全连接到服务器" : 
                                      "Please enter your access token for secure server connection",
                isRequired: true
            ),
            WizardStep(
                fieldId: "encryptionPassword",
                title: chinese ? "加密密码" : "Encryption Password",
                description: chinese ? "请输入加密密码，用于端到端加密保护您的笔记数据" : 
                                      "Please enter encryption password for end-to-end encryption of your notes",
                isRequired: true
            )
        ]
    }
    
    // 获取当前步骤的输入框位置
    private var currentFieldFrame: CGRect {
        currentStep == 0 ? tokenFieldFrame : passwordFieldFrame
    }
    
    var body: some View {
        if showWizard && currentStep < steps.count {
            ZStack {
                // 半透明遮罩 - 不阻止交互
                Color.black.opacity(0.4)
                    .ignoresSafeArea()
                    .allowsHitTesting(false)
                
                // 提示卡片 - 显示在输入框下方（如果已捕获到位置）
                if currentFieldFrame != .zero {
                    VStack(spacing: 0) {
                        Spacer()
                            .frame(height: currentFieldFrame.maxY + 10)
                        
                        // 箭头指向输入框
                        Image(systemName: "arrowtriangle.up.fill")
                            .font(.system(size: 16))
                            .foregroundColor(.white)
                            .shadow(color: .black.opacity(0.2), radius: 2)
                            .offset(y: -1)
                        
                        // 提示卡片
                        WizardCardWithArrow(
                            step: steps[currentStep],
                            isLastStep: currentStep == steps.count - 1,
                            onNext: nextStep,
                            onSkip: skipWizard
                        )
                        .padding(.horizontal, 20)
                        
                        Spacer()
                    }
                }
            }
            .transition(.opacity)
            .onPreferenceChange(FieldFramePreferenceKey.self) { frames in
                if let tokenFrame = frames["token"] {
                    tokenFieldFrame = tokenFrame
                }
                if let passwordFrame = frames["password"] {
                    passwordFieldFrame = passwordFrame
                }
            }
            .onChange(of: settingsService.token) { _ in
                checkAndDismiss()
            }
            .onChange(of: settingsService.encryptionPassword) { _ in
                checkAndDismiss()
            }
        }
    }
    
    private func nextStep() {
        withAnimation(.easeInOut(duration: 0.3)) {
            currentStep += 1
            if currentStep >= steps.count {
                showWizard = false
            }
        }
    }
    
    private func skipWizard() {
        withAnimation(.easeInOut(duration: 0.3)) {
            showWizard = false
        }
    }
    
    private func checkAndDismiss() {
        if settingsService.isConfigured {
            withAnimation {
                showWizard = false
            }
        }
    }
}

/// 提示卡片
struct WizardCardWithArrow: View {
    let step: WizardStep
    let isLastStep: Bool
    let onNext: () -> Void
    let onSkip: () -> Void
    
    private var isChinese: Bool {
        let language = Locale.current.languageCode ?? ""
        let region = Locale.current.regionCode ?? ""
        return language == "zh" || region == "CN" || region == "TW" || region == "HK"
    }
    
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            // 标题行
            HStack {
                Image(systemName: "info.circle.fill")
                    .foregroundColor(.blue)
                    .font(.system(size: 16))
                
                Text(step.title)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(.primary)
                
                Spacer()
                
                Button(isChinese ? "跳过" : "Skip") {
                    onSkip()
                }
                .font(.system(size: 14))
                .foregroundColor(.blue)
            }
            
            // 描述文本
            Text(step.description)
                .font(.system(size: 14))
                .foregroundColor(.secondary)
                .fixedSize(horizontal: false, vertical: true)
                .lineSpacing(2)
            
            // 下一步按钮
            Button(action: onNext) {
                HStack {
                    Text(isLastStep ? (isChinese ? "完成" : "Finish") : (isChinese ? "下一步" : "Next"))
                        .font(.system(size: 15, weight: .semibold))
                    
                    if !isLastStep {
                        Image(systemName: "arrow.right")
                            .font(.system(size: 13, weight: .semibold))
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(Color.blue)
                .foregroundColor(.white)
                .cornerRadius(8)
            }
        }
        .padding(16)
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(Color(.systemBackground))
                .shadow(color: Color.black.opacity(0.2), radius: 10, x: 0, y: 4)
        )
    }
}

/// PreferenceKey 用于传递输入框位置
struct FieldFramePreferenceKey: PreferenceKey {
    static var defaultValue: [String: CGRect] = [:]
    
    static func reduce(value: inout [String: CGRect], nextValue: () -> [String: CGRect]) {
        value.merge(nextValue()) { $1 }
    }
}

/// 用于捕获视图位置的 ViewModifier
struct FrameCaptureModifier: ViewModifier {
    let fieldId: String
    
    func body(content: Content) -> some View {
        content
            .background(
                GeometryReader { geometry in
                    Color.clear.preference(
                        key: FieldFramePreferenceKey.self,
                        value: [fieldId: geometry.frame(in: .global)]
                    )
                }
            )
    }
}

extension View {
    func captureFrame(fieldId: String) -> some View {
        self.modifier(FrameCaptureModifier(fieldId: fieldId))
    }
}

/// 输入框高亮边框的 ViewModifier
struct HighlightBorderModifier: ViewModifier {
    let isHighlighted: Bool
    
    func body(content: Content) -> some View {
        content
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(Color.blue, lineWidth: isHighlighted ? 2 : 0)
                    .animation(.easeInOut(duration: 0.3), value: isHighlighted)
            )
    }
}

extension View {
    func highlightBorder(isHighlighted: Bool) -> some View {
        self.modifier(HighlightBorderModifier(isHighlighted: isHighlighted))
    }
}
