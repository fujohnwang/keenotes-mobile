import SwiftUI
import UIKit

/// Shared note card used by Review, Search and On This Day lists.
struct NoteRow: View {
    let note: Note
    var onEnlarge: (() -> Void)? = nil
    @EnvironmentObject var appState: AppState
    @State private var showCopiedAlert = false
    @State private var showSharePoster = false
    @Environment(\.colorScheme) private var colorScheme

    private var isPad: Bool { DeviceType.isPad }
    private var messageFontSize: CGFloat { isPad ? 18 : 17 }

    private var formattedDate: String {
        Theme.formatNoteDate(note.createdAt, compact: appState.settingsService.compactDateFormat)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: isPad ? 16 : 12) {
            HStack(spacing: isPad ? 10 : 8) {
                HStack(spacing: isPad ? 10 : 8) {
                    Text(formattedDate)
                        .font(.system(size: 12))
                        .foregroundColor(Color(.systemGray2))

                    if !note.channel.isEmpty {
                        Text("·")
                            .font(.system(size: 10))
                            .foregroundColor(Color(.systemGray3))
                        Text(note.channel)
                            .font(.system(size: 11))
                            .foregroundColor(Color(.systemGray2))
                    }
                }
                .contentShape(Rectangle())
                .onTapGesture {
                    copyToClipboard()
                }

                Spacer()

                Button(action: { showSharePoster = true }) {
                    Image(systemName: "square.and.arrow.up")
                        .font(.system(size: isPad ? 14 : 12))
                        .foregroundColor(Color(.systemGray2))
                        .padding(6)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                if let onEnlarge = onEnlarge {
                    Button(action: onEnlarge) {
                        Image(systemName: "arrow.down.left.and.arrow.up.right")
                            .font(.system(size: isPad ? 14 : 12))
                            .foregroundColor(Color(.systemGray2))
                            .padding(6)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }

            Text(note.content)
                .font(.system(size: messageFontSize))
                .foregroundColor(.primary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
                .textSelection(.enabled)
                .contentShape(Rectangle())
                .onTapGesture {
                    copyToClipboard()
                }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, isPad ? 20 : 16)
        .overlay(
            Rectangle()
                .fill(Theme.separatorColor(colorScheme))
                .frame(height: 1),
            alignment: .bottom
        )
        .overlay(
            Group {
                if showCopiedAlert {
                    VStack {
                        Spacer()
                        HStack {
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundColor(.white)
                            Text("Copied")
                                .foregroundColor(.white)
                                .font(.subheadline)
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                        .background(Color.black.opacity(0.7))
                        .cornerRadius(20)
                        .padding(.bottom, 20)
                    }
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
        )
        .clipped()
        .fullScreenCover(isPresented: $showSharePoster) {
            NoteSharePosterOverlay(
                note: note,
                formattedDate: formattedDate,
                posterDate: Theme.formatPosterDate(note.createdAt) ?? formattedDate,
                hiddenMessage: appState.settingsService.hiddenMessage,
                onDismiss: { showSharePoster = false }
            )
        }
    }

    private func copyToClipboard() {
        UIPasteboard.general.string = ZeroWidthSteganography.embedIfNeeded(
            content: note.content,
            hiddenMessage: appState.settingsService.hiddenMessage
        )
        showCopiedNotification()
    }

    private func showCopiedNotification() {
        let generator = UIImpactFeedbackGenerator(style: .medium)
        generator.impactOccurred()

        withAnimation {
            showCopiedAlert = true
        }

        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            withAnimation {
                showCopiedAlert = false
            }
        }
    }
}

/// UITextView wrapper that supports both tap-to-copy and long-press-to-select.
struct SelectableTextView: UIViewRepresentable {
    let text: String
    let fontSize: CGFloat
    var availableWidth: CGFloat = 0
    let onTap: () -> Void
    let onCopyMenuAction: () -> Void

    static func estimatedHeight(text: String, fontSize: CGFloat, width: CGFloat) -> CGFloat {
        max(calculateTextHeight(text: text, font: .systemFont(ofSize: fontSize), width: width), 20)
    }

    func makeUIView(context: Context) -> CustomUITextView {
        let textView = CustomUITextView()
        textView.isEditable = false
        textView.isSelectable = true
        textView.isScrollEnabled = false
        textView.backgroundColor = .clear
        textView.textContainerInset = .zero
        textView.textContainer.lineFragmentPadding = 0
        textView.font = .systemFont(ofSize: fontSize)
        textView.textColor = .label
        textView.bounces = false
        textView.clipsToBounds = true
        textView.copyActionCallback = context.coordinator.handleCopyAction

        let tapGesture = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handleTap))
        tapGesture.delegate = context.coordinator
        textView.addGestureRecognizer(tapGesture)

        return textView
    }

    func updateUIView(_ uiView: CustomUITextView, context: Context) {
        uiView.text = text
        uiView.font = .systemFont(ofSize: fontSize)
        uiView.copyActionCallback = context.coordinator.handleCopyAction
        if availableWidth > 0 {
            uiView.textContainer.size = CGSize(width: availableWidth, height: .greatestFiniteMagnitude)
        }
        uiView.setNeedsLayout()
        uiView.layoutIfNeeded()
    }

    private static func calculateTextHeight(text: String, font: UIFont, width: CGFloat) -> CGFloat {
        let textStorage = NSTextStorage(string: text, attributes: [.font: font])
        let textContainer = NSTextContainer(size: CGSize(width: width, height: .greatestFiniteMagnitude))
        let layoutManager = NSLayoutManager()
        textContainer.lineFragmentPadding = 0
        layoutManager.addTextContainer(textContainer)
        textStorage.addLayoutManager(layoutManager)
        layoutManager.glyphRange(for: textContainer)
        return ceil(layoutManager.usedRect(for: textContainer).height)
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(onTap: onTap, onCopyMenuAction: onCopyMenuAction)
    }

    class Coordinator: NSObject, UIGestureRecognizerDelegate {
        let onTap: () -> Void
        let onCopyMenuAction: () -> Void

        init(onTap: @escaping () -> Void, onCopyMenuAction: @escaping () -> Void) {
            self.onTap = onTap
            self.onCopyMenuAction = onCopyMenuAction
        }

        @objc func handleTap(_ gesture: UITapGestureRecognizer) {
            guard let textView = gesture.view as? UITextView else { return }

            if textView.selectedRange.length == 0 {
                onTap()
            }
        }

        func handleCopyAction() {
            onCopyMenuAction()
        }

        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
            true
        }
    }
}

class CustomUITextView: UITextView {
    var copyActionCallback: (() -> Void)?

    override func copy(_ sender: Any?) {
        super.copy(sender)

        let hiddenMessage = UserDefaults.standard.string(forKey: "hidden_message") ?? ""
        if !hiddenMessage.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
           let copied = UIPasteboard.general.string {
            UIPasteboard.general.string = ZeroWidthSteganography.embedIfNeeded(
                content: copied,
                hiddenMessage: hiddenMessage
            )
        }

        selectedTextRange = nil
        copyActionCallback?()
    }
}

/// Full-screen poster preview for sharing a note as an image.
struct NoteSharePosterOverlay: View {
    let note: Note
    let formattedDate: String
    let posterDate: String
    let hiddenMessage: String
    let onDismiss: () -> Void

    @StateObject private var imageSaver = PosterImageSaver()
    @State private var isSaving = false
    @State private var isExportingVideo = false

    private let posterWidth: CGFloat = 390

    private var isBusy: Bool { isSaving || isExportingVideo }

    private func posterPreviewWidth(for geometry: GeometryProxy) -> CGFloat {
        let candidateWidth = geometry.size.width - 40
        let maxWidth = min(candidateWidth.isFinite && candidateWidth > 0 ? candidateWidth : posterWidth, posterWidth)
        return min(maxWidth, posterPreviewMaxHeight(for: geometry) * PosterShareRenderer.aspectRatio)
    }

    private func posterPreviewMaxHeight(for geometry: GeometryProxy) -> CGFloat {
        let candidateHeight = geometry.size.height
            - geometry.safeAreaInsets.top
            - geometry.safeAreaInsets.bottom
            - 44
            - 18
            - 36
        return max(candidateHeight.isFinite && candidateHeight > 0 ? candidateHeight : 220, 220)
    }

    var body: some View {
        GeometryReader { geometry in
            let displayWidth = posterPreviewWidth(for: geometry)
            let minimumHeight = PosterShareRenderer.exportHeight(for: displayWidth)

            ZStack {
                Color.black.opacity(0.55)
                    .ignoresSafeArea()
                    .onTapGesture(perform: onDismiss)

                VStack(spacing: 18) {
                    HStack(spacing: 12) {
                        Spacer()
                        Button(action: savePoster) {
                            Image(systemName: "arrow.down.circle.fill")
                                .font(.system(size: 17, weight: .semibold))
                                .foregroundColor(.white)
                                .frame(width: 44, height: 44)
                                .background(Color.white.opacity(0.2))
                                .clipShape(Circle())
                                .contentShape(Circle())
                        }
                        .buttonStyle(.plain)
                        .disabled(isBusy)

                        Button(action: saveVideo) {
                            Image(systemName: "video.fill")
                                .font(.system(size: 17, weight: .semibold))
                                .foregroundColor(.white)
                                .frame(width: 44, height: 44)
                                .background(Color.white.opacity(0.2))
                                .clipShape(Circle())
                                .contentShape(Circle())
                        }
                        .buttonStyle(.plain)
                        .disabled(isBusy)

                        Button(action: onDismiss) {
                            Image(systemName: "xmark.circle.fill")
                                .font(.system(size: 17, weight: .semibold))
                                .foregroundColor(.white)
                                .frame(width: 44, height: 44)
                                .background(Color.white.opacity(0.2))
                                .clipShape(Circle())
                                .contentShape(Circle())
                        }
                        .buttonStyle(.plain)
                    }
                    .frame(width: displayWidth)

                    ScrollView(.vertical, showsIndicators: false) {
                        VStack(spacing: 10) {
                            NoteSharePosterContent(
                                noteContent: note.content,
                                formattedDate: posterDate,
                                hiddenMessage: hiddenMessage,
                                minimumHeight: minimumHeight
                            )
                            .frame(width: displayWidth)
                            .fixedSize(horizontal: false, vertical: true)
                            .clipShape(RoundedRectangle(cornerRadius: 32, style: .continuous))
                            .shadow(color: Color.black.opacity(0.25), radius: 24, x: 0, y: 12)
                            .contentShape(RoundedRectangle(cornerRadius: 32, style: .continuous))
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.bottom, 24)
                    }
                    .frame(maxHeight: posterPreviewMaxHeight(for: geometry))
                }

                if let message = imageSaver.toastMessage {
                    VStack {
                        Spacer()
                        Text(message)
                            .font(.system(size: 14, weight: .medium))
                            .foregroundColor(.white)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 10)
                            .background(Color.black.opacity(0.75))
                            .clipShape(Capsule())
                            .padding(.bottom, 42)
                    }
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                }

                if isBusy {
                    ProgressView()
                        .progressViewStyle(CircularProgressViewStyle(tint: .white))
                        .padding(18)
                        .background(Color.black.opacity(0.45))
                        .clipShape(Circle())
                }
            }
        }
        .background(ClearFullScreenCoverBackground())
    }

    @MainActor
    private func savePoster() {
        guard !isBusy else { return }
        isSaving = true

        guard let image = PosterShareRenderer.renderPosterImage(
            noteContent: note.content,
            posterDate: posterDate,
            hiddenMessage: hiddenMessage,
            width: posterWidth
        ) else {
            imageSaver.showMessage("Save failed")
            isSaving = false
            return
        }

        imageSaver.save(image) {
            isSaving = false
        }
    }

    private func saveVideo() {
        guard !isBusy else { return }
        isExportingVideo = true

        Task {
            defer { isExportingVideo = false }

            guard let image = PosterShareRenderer.renderPosterImage(
                noteContent: note.content,
                posterDate: posterDate,
                hiddenMessage: hiddenMessage,
                width: posterWidth
            ) else {
                imageSaver.showMessage("Save failed")
                return
            }

            do {
                try await PosterVideoExporter.exportAndSaveToPhotos(posterImage: image)
                imageSaver.showMessage("Saved video to Photos")
            } catch {
                imageSaver.showMessage("Video export failed")
            }
        }
    }
}

/// Poster image content. Keep this view self-contained so it can be rendered to UIImage.
struct NoteSharePosterContent: View {
    let noteContent: String
    let formattedDate: String
    let hiddenMessage: String
    var minimumHeight: CGFloat = PosterShareRenderer.exportHeight(for: PosterShareRenderer.exportWidth)

    private var authorText: String? {
        let trimmed = hiddenMessage.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    private var contentLength: Int {
        noteContent.count
    }

    private var contentFontSize: CGFloat {
        if contentLength > 900 {
            return 16
        } else if contentLength > 500 {
            return 18
        } else if contentLength > 220 {
            return 20
        }
        return 24
    }

    private var contentLineSpacing: CGFloat {
        if contentLength > 700 {
            return 8
        } else if contentLength > 300 {
            return 10
        }
        return 12
    }

    private var cardPadding: CGFloat {
        contentLength > 700 ? 28 : 34
    }

    private var contentVerticalPadding: CGFloat {
        if contentLength > 900 {
            return 30
        } else if contentLength > 500 {
            return 38
        }
        return 52
    }

    private var footerVerticalPadding: CGFloat {
        contentLength > 700 ? 18 : 22
    }

    private var posterCornerRadius: CGFloat {
        32
    }

    var body: some View {
        let posterShape = RoundedRectangle(cornerRadius: posterCornerRadius, style: .continuous)

        ZStack {
            posterShape
                .fill(PosterPalette.paperColor)

            PaperGrainOverlay()
                .opacity(0.32)

            RadialGradient(
                colors: [
                    Color.white.opacity(0),
                    Color.black.opacity(0.025)
                ],
                center: .center,
                startRadius: 160,
                endRadius: 430
            )

            VStack(alignment: .leading, spacing: 0) {
                Spacer(minLength: contentVerticalPadding)

                Text(noteContent)
                    .font(.system(size: contentFontSize, weight: .bold, design: .serif))
                    .foregroundColor(Color(red: 0.10, green: 0.095, blue: 0.085))
                    .lineSpacing(contentLineSpacing)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, cardPadding)

                Spacer(minLength: contentVerticalPadding)

                Rectangle()
                    .fill(Color.black.opacity(0.06))
                    .frame(height: 0.5)

                HStack(spacing: 10) {
                    HStack(spacing: 6) {
                        if let authorText {
                            Text(authorText)
                                .lineLimit(1)
                                .minimumScaleFactor(0.72)

                            Text("·")
                        }

                        Text(formattedDate)
                            .lineLimit(1)
                    }
                    .foregroundColor(Color.black.opacity(0.34))
                    .layoutPriority(1)

                    Spacer(minLength: 12)

                    Text("KeeNotes")
                        .font(.system(size: 11, weight: .semibold, design: .rounded))
                        .foregroundColor(Color(red: 0.10, green: 0.095, blue: 0.085).opacity(0.58))
                        .lineLimit(1)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .background(
                            Capsule()
                                .fill(Color.black.opacity(0.045))
                        )
                        .overlay(
                            Capsule()
                                .stroke(Color.black.opacity(0.055), lineWidth: 0.5)
                        )
                        .accessibilityLabel("KeeNotes")
                }
                .font(.system(size: 11, weight: .medium))
                .padding(.horizontal, cardPadding)
                .padding(.vertical, footerVerticalPadding)
            }
            .frame(maxWidth: .infinity, minHeight: minimumHeight, alignment: .topLeading)
        }
        .clipShape(posterShape)
        .overlay(
            posterShape
                .stroke(Color.white.opacity(0.82), lineWidth: 0.8)
        )
    }
}

private struct PaperGrainOverlay: View {
    var body: some View {
        Canvas { context, size in
            let step: CGFloat = 7
            var y: CGFloat = 0
            while y < size.height {
                var x: CGFloat = 0
                while x < size.width {
                    let seed = Int((x * 31 + y * 17).rounded())
                    let opacity = seed.isMultiple(of: 5) ? 0.028 : 0.014
                    let rect = CGRect(x: x, y: y, width: 1, height: 1)
                    context.fill(Path(rect), with: .color(Color.black.opacity(opacity)))
                    x += step
                }
                y += step
            }
        }
        .allowsHitTesting(false)
    }
}

private final class PosterImageSaver: NSObject, ObservableObject {
    @Published var toastMessage: String?
    private(set) var lastSaveSucceeded = false
    private var completion: (() -> Void)?

    func save(_ image: UIImage, completion: @escaping () -> Void) {
        self.completion = completion
        UIImageWriteToSavedPhotosAlbum(
            image,
            self,
            #selector(saveCompleted(_:didFinishSavingWithError:contextInfo:)),
            nil
        )
    }

    func showMessage(_ message: String) {
        withAnimation {
            toastMessage = message
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.8) {
            withAnimation {
                self.toastMessage = nil
            }
        }
    }

    @objc private func saveCompleted(_ image: UIImage, didFinishSavingWithError error: Error?, contextInfo: UnsafeRawPointer) {
        DispatchQueue.main.async {
            self.lastSaveSucceeded = error == nil
            self.showMessage(self.lastSaveSucceeded ? "Saved to Photos" : "Save failed")
            self.completion?()
            self.completion = nil
        }
    }
}

private struct ClearFullScreenCoverBackground: UIViewRepresentable {
    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        DispatchQueue.main.async {
            view.superview?.superview?.backgroundColor = .clear
        }
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {}
}

/// Modifier to hide List default background (iOS 16+) with fallback.
struct ListBackgroundModifier: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 16.0, *) {
            content.scrollContentBackground(.hidden)
        } else {
            content
        }
    }
}
