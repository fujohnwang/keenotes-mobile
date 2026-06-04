import SwiftUI
import UIKit

enum PosterShareRenderer {
    static let exportWidth: CGFloat = 390

    @MainActor
    static func renderPosterImage(
        noteContent: String,
        posterDate: String,
        hiddenMessage: String,
        width: CGFloat = exportWidth
    ) -> UIImage? {
        let poster = NoteSharePosterContent(
            noteContent: noteContent,
            formattedDate: posterDate,
            hiddenMessage: hiddenMessage
        )
        .frame(width: width)
        .fixedSize(horizontal: false, vertical: true)

        if #available(iOS 16.0, *) {
            let renderer = ImageRenderer(content: poster)
            renderer.scale = UIScreen.main.scale
            renderer.proposedSize = ProposedViewSize(width: width, height: nil)
            return renderer.uiImage
        }

        return PosterHostingRenderer.render(view: poster, width: width)
    }
}

enum PosterHostingRenderer {
    @MainActor
    static func render<Content: View>(view: Content, width: CGFloat) -> UIImage? {
        let controller = UIHostingController(rootView: view)
        let renderView = controller.view!
        renderView.bounds = CGRect(x: 0, y: 0, width: width, height: 10_000)
        renderView.backgroundColor = .clear
        renderView.setNeedsLayout()
        renderView.layoutIfNeeded()

        let measuredHeight = renderView.sizeThatFits(
            CGSize(width: width, height: .greatestFiniteMagnitude)
        ).height
        let size = CGSize(width: width, height: max(measuredHeight, 1))
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

extension UIImage {
    func opaquePosterImage(backgroundColor: UIColor = .white) -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = scale
        format.opaque = true

        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            backgroundColor.setFill()
            UIBezierPath(rect: CGRect(origin: .zero, size: size)).fill()
            draw(in: CGRect(origin: .zero, size: size))
        }
    }
}
