import SwiftUI

/// 首次启动配置向导 - 胶囊式设计，跟随输入框
struct OnboardingWizardOverlay: View {
    @Binding var showWizard: Bool
    @State private var currentStep = 0
    let fieldFrames: [String: CGRect]
    let settingsService: SettingsService
    let onFocusField: (String) -> Void
    
    // 检测系统语言
    private func isChinese() -> Bool {
        let preferredLanguage = Locale.preferredLanguages.first ?? ""
        return preferredLanguage.hasPrefix("zh")
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
            ),
            WizardStep(
                fieldId: "confirmPassword",
                title: chinese ? "确认加密密码" : "Confirm Encryption Password",
                description: chinese ? "请再次输入加密密码以确认" : 
                                      "Please re-enter the encryption password to confirm",
                isRequired: true
            )
        ]
    }
    
    var body: some View {
        if showWizard && currentStep < steps.count {
            let currentFieldId = steps[currentStep].fieldId
            let fieldFrame = fieldFrames[currentFieldId] ?? .zero
            
            if fieldFrame != .zero {
                GeometryReader { _ in
                    CapsuleWizardCard(
                        step: steps[currentStep],
                        isLastStep: currentStep == steps.count - 1,
                        onNext: nextStep,
                        onSkip: skipWizard
                    )
                    .position(
                        x: UIScreen.main.bounds.width / 2,
                        y: fieldFrame.maxY + 70
                    )
                    .transition(.opacity.combined(with: .scale(scale: 0.9)))
                }
                .ignoresSafeArea()
                .onChange(of: currentStep) { _ in
                    if currentStep < steps.count {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                            onFocusField(steps[currentStep].fieldId)
                        }
                    }
                }
                .onChange(of: settingsService.token) { _ in
                    checkAndDismiss()
                }
                .onChange(of: settingsService.encryptionPassword) { _ in
                    checkAndDismiss()
                }
                .onAppear {
                    if currentStep < steps.count {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                            onFocusField(steps[currentStep].fieldId)
                        }
                    }
                }
            }
        }
    }
    
    private func nextStep() {
        withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) {
            currentStep += 1
            if currentStep >= steps.count {
                showWizard = false
            }
        }
    }
    
    private func skipWizard() {
        withAnimation(.easeOut(duration: 0.25)) {
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

/// 胶囊式向导卡片（带向上箭头，固定在输入框下方）
struct CapsuleWizardCard: View {
    let step: WizardStep
    let isLastStep: Bool
    let onNext: () -> Void
    let onSkip: () -> Void
    
    private var isChinese: Bool {
        let preferredLanguage = Locale.preferredLanguages.first ?? ""
        return preferredLanguage.hasPrefix("zh")
    }
    
    var body: some View {
        VStack(spacing: 0) {
            // 向上箭头 - 指向输入框
            Triangle()
                .fill(
                    LinearGradient(
                        colors: [Color(.systemBackground).opacity(0.98), Color(.systemBackground).opacity(0.95)],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )
                .frame(width: 16, height: 8)
                .shadow(color: Color.black.opacity(0.08), radius: 1, x: 0, y: -0.5)
            
            // 胶囊式卡片内容
            HStack(spacing: 12) {
                // 左侧图标
                Image(systemName: "info.circle.fill")
                    .foregroundColor(.blue)
                    .font(.system(size: 20))
                
                // 中间文本内容
                VStack(alignment: .leading, spacing: 4) {
                    Text(step.title)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(.primary)
                    
                    Text(step.description)
                        .font(.system(size: 13))
                        .foregroundColor(.secondary)
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
                
                Spacer()
                
                // 右侧按钮组
                VStack(spacing: 8) {
                    // 跳过按钮
                    Button(action: onSkip) {
                        Text(isChinese ? "跳过" : "Skip")
                            .font(.system(size: 12))
                            .foregroundColor(.blue.opacity(0.8))
                    }
                    
                    // 下一步按钮
                    Button(action: onNext) {
                        HStack(spacing: 4) {
                            Text(isLastStep ? (isChinese ? "完成" : "Done") : (isChinese ? "下一步" : "Next"))
                                .font(.system(size: 14, weight: .medium))
                            
                            if !isLastStep {
                                Image(systemName: "arrow.right")
                                    .font(.system(size: 12, weight: .medium))
                            }
                        }
                        .foregroundColor(.white)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                        .background(
                            Capsule()
                                .fill(Color.blue)
                        )
                    }
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 16)
            .background(
                Capsule()
                    .fill(
                        .ultraThinMaterial
                    )
                    .overlay(
                        Capsule()
                            .stroke(Color.white.opacity(0.2), lineWidth: 0.5)
                    )
                    .shadow(color: Color.black.opacity(0.15), radius: 20, x: 0, y: 8)
            )
        }
        .frame(maxWidth: 400)
        .padding(.horizontal, 16)
    }
}

/// 向上的三角形箭头
struct Triangle: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: rect.midX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
        path.closeSubpath()
        return path
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


