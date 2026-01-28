import SwiftUI

/// 内联向导提示卡片 - 显示在字段下方
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
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Image(systemName: "lightbulb.fill")
                    .foregroundColor(.orange)
                    .font(.system(size: 14))
                
                Text(step.title)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(.primary)
                
                Spacer()
                
                Button(isChinese ? "跳过" : "Skip") {
                    onSkip()
                }
                .font(.system(size: 13))
                .foregroundColor(.secondary)
            }
            
            Text(step.description)
                .font(.system(size: 13))
                .foregroundColor(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            
            Button(action: onNext) {
                Text(isLastStep ? (isChinese ? "完成" : "Finish") : (isChinese ? "下一步" : "Next"))
                    .font(.system(size: 14, weight: .medium))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                    .background(Color.accentColor)
                    .foregroundColor(.white)
                    .cornerRadius(6)
            }
        }
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: 10)
                .fill(Color.orange.opacity(0.1))
                .overlay(
                    RoundedRectangle(cornerRadius: 10)
                        .stroke(Color.orange.opacity(0.3), lineWidth: 1)
                )
        )
        .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
        .listRowBackground(Color.clear)
    }
}
