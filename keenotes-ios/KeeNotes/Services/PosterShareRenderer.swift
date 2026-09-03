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
        width: CGFloat = exportWidth
    ) -> UIImage? {
        let minimumHeight = exportHeight(for: width)
        let poster = NoteSharePosterContent(
            noteContent: noteContent,
            formattedDate: posterDate,
            hiddenMessage: hiddenMessage,
            inkTheme: inkTheme,
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
