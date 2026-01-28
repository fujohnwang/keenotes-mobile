import SwiftUI

/// Coach Marks 风格的首次启动配置向导
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
                // 全屏半透明遮罩
                Color.black.opacity(0.7)
                    .ignoresSafeArea()
                    .onTapGesture {
                        // 点击遮罩不关闭，保持引导
                    }
                
                // 提示卡片 - 根据步骤显示在不同位置
                VStack {
                    if currentStep == 0 {
                        // Token 字段提示 - 显示在屏幕上方
                        Spacer()
                            .frame(height: 200)
                        
                        CoachMarkCard(
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
                        
                        CoachMarkCard(
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

/// Coach Mark 卡片组件
struct CoachMarkCard: View {
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
        VStack(alignment: .leading, spacing: 16) {
            // 标题和跳过按钮
            HStack {
                Image(systemName: "lightbulb.fill")
                    .foregroundColor(.yellow)
                    .font(.system(size: 20))
                
                Text(step.title)
                    .font(.system(size: 18, weight: .bold))
                    .foregroundColor(.white)
                
                Spacer()
                
                Button(isChinese ? "跳过" : "Skip") {
                    onSkip()
                }
                .font(.system(size: 15))
                .foregroundColor(.white.opacity(0.8))
            }
            
            // 描述
            Text(step.description)
                .font(.system(size: 15))
                .foregroundColor(.white.opacity(0.9))
                .fixedSize(horizontal: false, vertical: true)
                .lineSpacing(4)
            
            // 下一步按钮
            Button(action: onNext) {
                HStack {
                    Text(isLastStep ? (isChinese ? "完成" : "Finish") : (isChinese ? "下一步" : "Next"))
                        .font(.system(size: 16, weight: .semibold))
                    
                    if !isLastStep {
                        Image(systemName: "arrow.right")
                            .font(.system(size: 14, weight: .semibold))
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
                .background(Color.blue)
                .foregroundColor(.white)
                .cornerRadius(10)
            }
        }
        .padding(20)
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(Color.black.opacity(0.9))
                .shadow(color: Color.black.opacity(0.3), radius: 20, x: 0, y: 10)
        )
        .padding(.horizontal, 24)
    }
}
