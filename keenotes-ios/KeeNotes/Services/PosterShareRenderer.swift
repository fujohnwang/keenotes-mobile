import SwiftUI
import UIKit

enum PosterShareRenderer {
    static let exportWidth: CGFloat = 390
    static let aspectRatio: CGFloat = 9.0 / 16.0
    static let cornerRadius: CGFloat = 32

    static func exportHeight(for width: CGFloat) -> CGFloat {
        width / aspectRatio
    }

    @MainActor
    static func renderPosterImage(
        noteContent: String,
        posterDate: String,
        hiddenMessage: String,
        inkTheme: PosterInkTheme,
        colorTheme: PosterColorTheme = .paper,
        width: CGFloat = exportWidth
    ) -> UIImage? {
        let minimumHeight = exportHeight(for: width)
        let poster = NoteSharePosterContent(
            noteContent: noteContent,
            formattedDate: posterDate,
            hiddenMessage: hiddenMessage,
            inkTheme: inkTheme,
            colorTheme: colorTheme,
            minimumHeight: minimumHeight
        )
        .frame(width: width)
        .fixedSize(horizontal: false, vertical: true)

        let renderedImage: UIImage?
        if #available(iOS 16.0, *) {
            let renderer = ImageRenderer(content: poster)
            renderer.scale = UIScreen.main.scale
            renderer.proposedSize = ProposedViewSize(width: width, height: nil)
            renderer.isOpaque = false
            renderedImage = renderer.uiImage
        } else {
            renderedImage = PosterHostingRenderer.render(
                view: poster,
                width: width,
                minimumHeight: minimumHeight
            )
        }

        guard let renderedImage else {
            return nil
        }
        return applyingTransparentRoundedCorners(to: renderedImage)
    }

    private static func applyingTransparentRoundedCorners(to image: UIImage) -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = image.scale
        format.opaque = false

        let bounds = CGRect(origin: .zero, size: image.size)
        return UIGraphicsImageRenderer(size: image.size, format: format).image { _ in
            UIBezierPath(
                roundedRect: bounds,
                cornerRadius: cornerRadius
            ).addClip()
            image.draw(in: bounds)
        }
    }
}

enum PosterHostingRenderer {
    @MainActor
    static func render<Content: View>(view: Content, width: CGFloat, minimumHeight: CGFloat) -> UIImage? {
        let controller = UIHostingController(rootView: view)
        let renderView = controller.view!
        renderView.bounds = CGRect(x: 0, y: 0, width: width, height: 10_000)
        renderView.backgroundColor = .clear
        renderView.isOpaque = false
        renderView.setNeedsLayout()
        renderView.layoutIfNeeded()

        let measuredHeight = renderView.sizeThatFits(
            CGSize(width: width, height: .greatestFiniteMagnitude)
        ).height
        let size = CGSize(width: width, height: max(measuredHeight, minimumHeight))
        renderView.bounds = CGRect(origin: .zero, size: size)
        renderView.setNeedsLayout()
        renderView.layoutIfNeeded()

        let format = UIGraphicsImageRendererFormat()
        format.scale = UIScreen.main.scale
        format.opaque = false

        let renderer = UIGraphicsImageRenderer(size: size, format: format)
        return renderer.image { _ in
            renderView.drawHierarchy(in: renderView.bounds, afterScreenUpdates: true)
        }
    }
}

enum PosterPalette {
    static let paperColor = Color(red: 0.995, green: 0.992, blue: 0.982)
}

/// A poster-local choice. The original paper palette remains the default.
enum PosterColorTheme: Int, CaseIterable {
    case paper, klein, midnight, pine

    var label: String {
        switch self {
        case .paper: return "纸间 · 暖白"
        case .klein: return "洞见 · 克莱因蓝"
        case .midnight: return "深思 · 深夜蓝"
        case .pine: return "沉淀 · 松墨绿"
        }
    }

    var isDark: Bool { self != .paper }
    var background: Color {
        switch self {
        case .paper: return PosterPalette.paperColor
        case .klein: return Self.color(0x002FA7)
        case .midnight: return Self.color(0x111C30)
        case .pine: return Self.color(0x123A35)
        }
    }
    var text: Color {
        switch self {
        case .paper: return Color(red: 0.10, green: 0.095, blue: 0.085)
        case .klein: return .white
        case .midnight: return Self.color(0xF4F6FB)
        case .pine: return Self.color(0xF2F3EB)
        }
    }
    var footer: Color {
        switch self {
        case .paper: return .black.opacity(0.34)
        case .klein: return Self.color(0xB7C9F1)
        case .midnight: return Self.color(0xAEBBCC)
        case .pine: return Self.color(0xB0C5BC)
        }
    }
    var badgeText: Color { isDark ? footer : text.opacity(0.58) }
    var badgeFill: Color { isDark ? .white.opacity(0.045) : .black.opacity(0.045) }
    var badgeStroke: Color { isDark ? .white.opacity(0.14) : .black.opacity(0.055) }
    var divider: Color { isDark ? .white.opacity(0.15) : .black.opacity(0.06) }
    var outline: Color { .white.opacity(isDark ? 0.125 : 0.82) }

    func offset(_ delta: Int) -> Self {
        let count = Self.allCases.count
        return Self.allCases[((rawValue + delta) % count + count) % count]
    }

    private static func color(_ rgb: Int) -> Color {
        Color(red: Double((rgb >> 16) & 255) / 255,
              green: Double((rgb >> 8) & 255) / 255,
              blue: Double(rgb & 255) / 255)
    }
}
