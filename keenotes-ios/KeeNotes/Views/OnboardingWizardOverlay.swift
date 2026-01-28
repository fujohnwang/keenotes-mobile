import SwiftUI

/// 首次启动配置向导覆盖层
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
            VStack {
                Spacer()
                
                // 提示卡片 - 固定在底部
                WizardCard(
                    step: steps[currentStep],
                    isLastStep: currentStep == steps.count - 1,
                    onNext: {
                        withAnimation {
                            currentStep += 1
                            if currentStep >= steps.count {
                                showWizard = false
                            }
                        }
                    },
                    onSkip: {
                        withAnimation {
                            showWizard = false
                        }
                    }
                )
                .transition(.move(edge: .bottom))
            }
            .onChange(of: settingsService.token) { _ in
                checkAndDismiss()
            }
            .onChange(of: settingsService.encryptionPassword) { _ in
                checkAndDismiss()
            }
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
