import SwiftUI

/// 首次启动配置向导 - 简化版，无遮罩，直接聚焦输入框
struct OnboardingWizardOverlay: View {
    @Binding var showWizard: Bool
    @State private var currentStep = 0
    @State private var fieldFrames: [String: CGRect] = [:]
    let settingsService: SettingsService
    let onFocusField: (String) -> Void
    
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
    
    var body: some View {
        if showWizard && currentStep < steps.count {
            GeometryReader { geometry in
                let currentFieldId = steps[currentStep].fieldId
                let fieldFrame = fieldFrames[currentFieldId] ?? .zero
                let cardYPosition = fieldFrame.maxY + 10 // 卡片显示在输入框下方 10pt
                
                // 提示卡片 - 显示在当前输入框下方
                WizardCardWithArrow(
                    step: steps[currentStep],
                    isLastStep: currentStep == steps.count - 1,
                    onNext: nextStep,
                    onSkip: skipWizard
                )
                .padding(.horizontal, 20)
                .position(x: geometry.size.width / 2, y: cardYPosition + 80)
                .transition(.opacity.combined(with: .move(edge: .bottom)))
            }
            .onPreferenceChange(FieldFramePreferenceKey.self) { frames in
                self.fieldFrames = frames
            }
            .onChange(of: currentStep) { _ in
                // 当步骤改变时，聚焦到对应的输入框
                if currentStep < steps.count {
                    onFocusField(steps[currentStep].fieldId)
                }
            }
            .onChange(of: settingsService.token) { _ in
                checkAndDismiss()
            }
            .onChange(of: settingsService.encryptionPassword) { _ in
                checkAndDismiss()
            }
            .onAppear {
                // 初始显示时聚焦第一个输入框
                if currentStep < steps.count {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                        onFocusField(steps[currentStep].fieldId)
                    }
                }
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

/// 提示卡片（带向上箭头）
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
        VStack(spacing: 0) {
            // 向上箭头
            Triangle()
                .fill(Color(.systemBackground))
                .frame(width: 20, height: 10)
                .shadow(color: Color.black.opacity(0.1), radius: 2, x: 0, y: -1)
            
            // 卡片内容
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
                    .fill(Color(.systemBackground).opacity(0.95))
                    .shadow(color: Color.black.opacity(0.2), radius: 10, x: 0, y: 4)
            )
        }
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


