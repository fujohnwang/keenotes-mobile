import Foundation

/// 向导步骤数据结构
struct WizardStep: Identifiable {
    let id = UUID()
    let fieldId: String          // 字段标识符
    let title: String            // 标题
    let description: String      // 描述文本
    let isRequired: Bool         // 是否必填
}
