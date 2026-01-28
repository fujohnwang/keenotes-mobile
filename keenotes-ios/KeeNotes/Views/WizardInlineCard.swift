import SwiftUI

/// 内联向导提示卡片 - 与 APP 风格一致
struct WizardInlineCard: View {
    let step: WizardStep
    let isLastStep: Bool
    let onNext: () -> Void
    let onSkip: () -> Void
    
    // 检测系统语言
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
                .shadow(color: Color.black.opacity(0.1), radius: 8, x: 0, y: 2)
        )
        .padding(.horizontal, 20)
    }
}
