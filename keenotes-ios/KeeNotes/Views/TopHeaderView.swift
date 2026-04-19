import SwiftUI

/// Unified top header component used across all screens.
///
/// Provides a consistent layout: optional left button, centered title, optional right button.
/// When a button side is nil, a transparent spacer of the same size is used to keep the title centered.
struct TopHeaderView: View {
    let title: String
    let leftButton: HeaderButton?
    let rightButton: HeaderButton?

    private var isPad: Bool { DeviceType.isPad }
    private var buttonSize: CGFloat { isPad ? 44 : 40 }
    private var iconSize: CGFloat { isPad ? 19 : 17 }

    /// Convenience: title only (no buttons)
    init(title: String) {
        self.title = title
        self.leftButton = nil
        self.rightButton = nil
    }

    /// Full initializer
    init(title: String, leftButton: HeaderButton? = nil, rightButton: HeaderButton? = nil) {
        self.title = title
        self.leftButton = leftButton
        self.rightButton = rightButton
    }

    var body: some View {
        HStack(spacing: 12) {
            buttonView(leftButton, alignment: .leading)
            Spacer(minLength: 0)
            Text(title)
                .font(.system(size: isPad ? 20 : 19, weight: .semibold))
                .foregroundColor(.primary)
                .lineLimit(1)
            Spacer(minLength: 0)
            buttonView(rightButton, alignment: .trailing)
        }
        .frame(height: buttonSize)
    }

    @ViewBuilder
    private func buttonView(_ button: HeaderButton?, alignment: Alignment) -> some View {
        if let button = button, button.isVisible {
            Button(action: button.action) {
                Image(systemName: button.systemName)
                    .font(.system(size: iconSize, weight: .medium))
                    .foregroundColor(.primary)
                    .frame(width: buttonSize, height: buttonSize, alignment: alignment)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        } else {
            Color.clear
                .frame(width: buttonSize, height: buttonSize)
        }
    }
}

/// Configuration for a header button.
struct HeaderButton {
    let systemName: String
    let isVisible: Bool
    let action: () -> Void

    init(systemName: String, isVisible: Bool = true, action: @escaping () -> Void) {
        self.systemName = systemName
        self.isVisible = isVisible
        self.action = action
    }

    /// Convenience for a back/chevron-left button
    static func back(action: @escaping () -> Void) -> HeaderButton {
        HeaderButton(systemName: "chevron.left", action: action)
    }
}
