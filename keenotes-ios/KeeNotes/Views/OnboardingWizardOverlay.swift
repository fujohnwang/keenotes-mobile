import SwiftUI

/// 非侵入式的首次启动配置向导
/// 不阻止用户交互，只提供视觉引导
struct OnboardingWizardOverlay: View {
    @Binding var showWizard: Bool
    @State private var currentStep = 0
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
    
    var body: some View {
        if showWizard && currentStep < steps.count {
            ZStack {
                // 半透明遮罩 - 不阻止交互
                Color.black.opacity(0.3)
                    .ignoresSafeArea()
                    .allowsHitTesting(false)  // 关键：不拦截触摸事件
                
                // 提示卡片 - 根据步骤显示在不同位置
                VStack {
                    if currentStep == 0 {
                        // Token 字段提示 - 显示在屏幕上方
                        Spacer()
                            .frame(height: 200)
                        
                        WizardInlineCard(
                            step: steps[currentStep],
                            isLastStep: false,
                            onNext: nextStep,
                            onSkip: skipWizard
                        )
                        .transition(.opacity.combined(with: .scale))
                        
                        Spacer()
                    } else if currentStep == 1 {
                        // Password 字段提示 - 显示在屏幕中部
                        Spacer()
                            .frame(height: 350)
                        
                        WizardInlineCard(
                            step: steps[currentStep],
                            isLastStep: true,
                            onNext: nextStep,
                            onSkip: skipWizard
                        )
                        .transition(.opacity.combined(with: .scale))
                        
                        Spacer()
                    }
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
