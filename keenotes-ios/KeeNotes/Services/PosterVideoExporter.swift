import AVFoundation
import Photos
import UIKit

enum PosterVideoExporter {
    enum ExportError: LocalizedError {
        case missingBGM
        case frameCompositionFailed
        case writerSetupFailed
        case exportFailed(String)
        case photoLibraryDenied

        var errorDescription: String? {
            switch self {
            case .missingBGM:
                return "Background music not found"
            case .frameCompositionFailed:
                return "Failed to compose poster frame"
            case .writerSetupFailed:
                return "Failed to start video export"
            case let .exportFailed(message):
                return message
            case .photoLibraryDenied:
                return "Photo library access denied"
            }
        }
    }

    private static let fps: Int32 = 30

    static func exportAndSaveToPhotos(posterImage: UIImage) async throws {
        guard let audioURL = PosterBGM.randomTrackURL() else {
            throw ExportError.missingBGM
        }
        guard let pixelBuffer = PosterVideoComposer.makePixelBuffer(from: posterImage) else {
            throw ExportError.frameCompositionFailed
        }

        let outputURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("keenotes-poster-\(UUID().uuidString).mp4")

        defer {
            try? FileManager.default.removeItem(at: outputURL)
        }

        try await writeVideo(pixelBuffer: pixelBuffer, audioURL: audioURL, outputURL: outputURL)
        try await saveVideoToPhotos(outputURL)
    }

    private static func writeVideo(
        pixelBuffer: CVPixelBuffer,
        audioURL: URL,
        outputURL: URL
    ) async throws {
        try await Task.detached(priority: .userInitiated) {
            try performWrite(pixelBuffer: pixelBuffer, audioURL: audioURL, outputURL: outputURL)
        }.value
    }

    private static func performWrite(
        pixelBuffer: CVPixelBuffer,
        audioURL: URL,
        outputURL: URL
    ) throws {
        let audioAsset = AVURLAsset(url: audioURL)
        let audioDuration = audioAsset.duration
        guard audioDuration.isNumeric, audioDuration.seconds > 0 else {
            throw ExportError.exportFailed("Invalid audio duration")
        }

        let frameCount = max(1, Int(ceil(audioDuration.seconds * Double(fps))))
        let frameDuration = CMTime(value: 1, timescale: fps)

        if FileManager.default.fileExists(atPath: outputURL.path) {
            try FileManager.default.removeItem(at: outputURL)
        }

        let writer = try AVAssetWriter(outputURL: outputURL, fileType: .mp4)

        let videoSettings: [String: Any] = [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: Int(PosterVideoComposer.outputSize.width),
            AVVideoHeightKey: Int(PosterVideoComposer.outputSize.height),
            AVVideoCompressionPropertiesKey: [
                AVVideoAverageBitRateKey: 6_000_000,
                AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel,
            ],
        ]

        let videoInput = AVAssetWriterInput(mediaType: .video, outputSettings: videoSettings)
        videoInput.expectsMediaDataInRealTime = false

        let pixelBufferAttributes: [String: Any] = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: Int(PosterVideoComposer.outputSize.width),
            kCVPixelBufferHeightKey as String: Int(PosterVideoComposer.outputSize.height),
        ]

        let adaptor = AVAssetWriterInputPixelBufferAdaptor(
            assetWriterInput: videoInput,
            sourcePixelBufferAttributes: pixelBufferAttributes
        )

        let audioSettings: [String: Any] = [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: 44_100,
            AVNumberOfChannelsKey: 2,
            AVEncoderBitRateKey: 192_000,
        ]

        let audioInput = AVAssetWriterInput(mediaType: .audio, outputSettings: audioSettings)
        audioInput.expectsMediaDataInRealTime = false

        guard writer.canAdd(videoInput), writer.canAdd(audioInput) else {
            throw ExportError.writerSetupFailed
        }

        writer.add(videoInput)
        writer.add(audioInput)

        guard writer.startWriting() else {
            throw ExportError.exportFailed(writer.error?.localizedDescription ?? "Writer failed to start")
        }

        writer.startSession(atSourceTime: .zero)

        let writingGroup = DispatchGroup()
        var audioError: Error?

        writingGroup.enter()
        audioInput.requestMediaDataWhenReady(on: DispatchQueue(label: "com.keenotes.poster.audio")) {
            do {
                try appendAudioSamples(
                    from: audioAsset,
                    to: audioInput,
                    duration: audioDuration
                )
            } catch {
                audioError = error
            }
            audioInput.markAsFinished()
            writingGroup.leave()
        }

        for frameIndex in 0..<frameCount {
            while !videoInput.isReadyForMoreMediaData {
                Thread.sleep(forTimeInterval: 0.002)
            }

            let presentationTime = CMTimeMultiply(frameDuration, multiplier: Int32(frameIndex))
            if !adaptor.append(pixelBuffer, withPresentationTime: presentationTime) {
                throw ExportError.exportFailed("Failed to append video frame \(frameIndex)")
            }
        }

        videoInput.markAsFinished()
        writingGroup.wait()

        if let audioError {
            throw audioError
        }

        let semaphore = DispatchSemaphore(value: 0)
        var finishError: Error?

        writer.finishWriting {
            if writer.status != .completed {
                finishError = ExportError.exportFailed(
                    writer.error?.localizedDescription ?? "Video export did not complete"
                )
            }
            semaphore.signal()
        }

        semaphore.wait()

        if let finishError {
            throw finishError
        }
    }

    private static func appendAudioSamples(
        from asset: AVAsset,
        to audioInput: AVAssetWriterInput,
        duration: CMTime
    ) throws {
        guard let track = asset.tracks(withMediaType: .audio).first else {
            throw ExportError.exportFailed("Audio track not found")
        }

        let reader = try AVAssetReader(asset: asset)
        let outputSettings: [String: Any] = [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVLinearPCMIsFloatKey: false,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsNonInterleaved: false,
        ]

        let output = AVAssetReaderTrackOutput(track: track, outputSettings: outputSettings)
        output.alwaysCopiesSampleData = false

        guard reader.canAdd(output) else {
            throw ExportError.exportFailed("Cannot read audio track")
        }

        reader.add(output)
        guard reader.startReading() else {
            throw ExportError.exportFailed(reader.error?.localizedDescription ?? "Audio reader failed")
        }

        let endTime = duration

        while reader.status == .reading {
            while !audioInput.isReadyForMoreMediaData {
                Thread.sleep(forTimeInterval: 0.002)
            }

            guard let sampleBuffer = output.copyNextSampleBuffer() else {
                break
            }

            let presentationTime = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
            if presentationTime >= endTime {
                break
            }

            if !audioInput.append(sampleBuffer) {
                throw ExportError.exportFailed("Failed to append audio sample")
            }
        }

        if reader.status == .failed {
            throw ExportError.exportFailed(reader.error?.localizedDescription ?? "Audio reader failed")
        }
    }

    @MainActor
    private static func saveVideoToPhotos(_ fileURL: URL) async throws {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else {
            throw ExportError.photoLibraryDenied
        }

        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            PHPhotoLibrary.shared().performChanges({
                PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: fileURL)
            }, completionHandler: { success, error in
                if let error {
                    continuation.resume(throwing: error)
                } else if success {
                    continuation.resume()
                } else {
                    continuation.resume(throwing: ExportError.exportFailed("Failed to save video"))
                }
            })
        }
    }
}
