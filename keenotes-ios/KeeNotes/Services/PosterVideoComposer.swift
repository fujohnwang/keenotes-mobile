import CoreImage
import CoreVideo
import UIKit

enum PosterVideoComposer {
    static let outputSize = CGSize(width: 1080, height: 1920)

    private static let renderContext = CIContext(options: [.useSoftwareRenderer: false])

    static func makePixelBuffer(from posterImage: UIImage) -> CVPixelBuffer? {
        guard let ciImage = CIImage(image: posterImage) else { return nil }

        let outputRect = CGRect(origin: .zero, size: outputSize)
        let background = makeBlurredBackground(from: ciImage, outputRect: outputRect)
        let foreground = makeForeground(from: ciImage, outputRect: outputRect)
        let frame = foreground.composited(over: background).cropped(to: outputRect)

        var pixelBuffer: CVPixelBuffer?
        let status = CVPixelBufferCreate(
            kCFAllocatorDefault,
            Int(outputSize.width),
            Int(outputSize.height),
            kCVPixelFormatType_32BGRA,
            [
                kCVPixelBufferCGImageCompatibilityKey: true,
                kCVPixelBufferCGBitmapContextCompatibilityKey: true,
            ] as CFDictionary,
            &pixelBuffer
        )
        guard status == kCVReturnSuccess, let pixelBuffer else { return nil }

        renderContext.render(
            frame,
            to: pixelBuffer,
            bounds: outputRect,
            colorSpace: CGColorSpaceCreateDeviceRGB()
        )
        return pixelBuffer
    }

    private static func makeBlurredBackground(from image: CIImage, outputRect: CGRect) -> CIImage {
        let extent = image.extent
        let scale = max(outputRect.width / extent.width, outputRect.height / extent.height)
        let scaled = image.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let scaledExtent = scaled.extent

        let cropRect = CGRect(
            x: scaledExtent.midX - outputRect.width / 2,
            y: scaledExtent.midY - outputRect.height / 2,
            width: outputRect.width,
            height: outputRect.height
        )

        let cropped = scaled.cropped(to: cropRect)
        let blurred = cropped
            .clampedToExtent()
            .applyingFilter("CIGaussianBlur", parameters: [kCIInputRadiusKey: 20])
            .cropped(to: outputRect)

        return blurred
    }

    private static func makeForeground(from image: CIImage, outputRect: CGRect) -> CIImage {
        let extent = image.extent
        let scale = min(outputRect.width / extent.width, outputRect.height / extent.height)
        let scaled = image.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let scaledExtent = scaled.extent

        let offsetX = outputRect.midX - scaledExtent.midX
        let offsetY = outputRect.midY - scaledExtent.midY
        return scaled.transformed(by: CGAffineTransform(translationX: offsetX, y: offsetY))
    }
}
