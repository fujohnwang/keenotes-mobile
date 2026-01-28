import SwiftUI

/// 首次启动配置向导覆盖层
struct OnboardingWizardOverlay: View {
    @Binding var showWizard: Bool
    @State private var currentStep = 0
    @ObservedObject var settings: SettingsService
    
    // 检测系统语言
    private func isChinese() -> Bool {
        let language = Locale.current.language.languageCode?.identifier ?? ""
        let region = Locale.current.region?.identifier ?? ""
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
                // 半透明遮罩（不阻止交互）
                Color.black.opacity(0.3)
                    .allowsHitTesting(false)
                    .ignoresSafeArea()
                
                VStack {
                    Spacer()
                    
                    // 提示卡片
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
            }
            .onChange(of: settings.isConfigured) { isConfigured in
                if isConfigured {
                    withAnimation {
                        showWizard = false
                    }
                }
            }
        }
    }
}
