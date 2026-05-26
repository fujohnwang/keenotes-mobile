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

    private let posterWidth: CGFloat = 390

    private func posterPreviewWidth(for geometry: GeometryProxy) -> CGFloat {
        let candidate = geometry.size.width - 40
        guard candidate.isFinite, candidate > 0 else {
            return posterWidth
        }
        return min(candidate, posterWidth)
    }

    private func posterPreviewMaxHeight(for geometry: GeometryProxy) -> CGFloat {
        let candidate = geometry.size.height - 120
        guard candidate.isFinite, candidate > 0 else {
            return 220
        }
        return max(candidate, 220)
    }

    var body: some View {
        GeometryReader { geometry in
            let displayWidth = posterPreviewWidth(for: geometry)

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
                        .disabled(isSaving)

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
                                hiddenMessage: hiddenMessage
                            )
                            .frame(width: displayWidth)
                            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                            .shadow(color: Color.black.opacity(0.25), radius: 24, x: 0, y: 12)
                            .contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
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

                if isSaving {
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
        guard !isSaving else { return }
        isSaving = true

        let poster = NoteSharePosterContent(
            noteContent: note.content,
            formattedDate: posterDate,
            hiddenMessage: hiddenMessage
        )
        .frame(width: posterWidth)
        .fixedSize(horizontal: false, vertical: true)

        let image: UIImage?
        if #available(iOS 16.0, *) {
            let renderer = ImageRenderer(content: poster)
            renderer.scale = UIScreen.main.scale
            renderer.proposedSize = ProposedViewSize(width: posterWidth, height: nil)
            image = renderer.uiImage
        } else {
            image = PosterRenderer.render(view: poster, width: posterWidth)
        }

        if let image {
            imageSaver.save(image.opaquePosterImage()) {
                isSaving = false
            }
        } else {
            imageSaver.showMessage("Save failed")
            isSaving = false
        }
    }
}

/// Poster image content. Keep this view self-contained so it can be rendered to UIImage.
private struct NoteSharePosterContent: View {
    let noteContent: String
    let formattedDate: String
    let hiddenMessage: String

    private var brandText: String {
        let trimmed = hiddenMessage.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "KeeNotes" : trimmed
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

    private var posterHorizontalPadding: CGFloat {
        contentLength > 700 ? 24 : 28
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

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [
                    Color(red: 0.945, green: 0.94, blue: 0.925),
                    Color(red: 0.975, green: 0.965, blue: 0.945)
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )

            PaperGrainOverlay()
                .opacity(0.45)

            RadialGradient(
                colors: [
                    Color.white.opacity(0),
                    Color.black.opacity(0.035)
                ],
                center: .center,
                startRadius: 160,
                endRadius: 430
            )

            VStack {
                VStack(alignment: .leading, spacing: 0) {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(noteContent)
                            .font(.system(size: contentFontSize, weight: .bold, design: .serif))
                            .foregroundColor(Color(red: 0.10, green: 0.095, blue: 0.085))
                            .lineSpacing(contentLineSpacing)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(.vertical, contentVerticalPadding)
                    .padding(.horizontal, cardPadding)

                    Rectangle()
                        .fill(Color.black.opacity(0.07))
                        .frame(height: 0.5)

                    HStack(spacing: 6) {
                        Spacer(minLength: 0)

                        Text(brandText)
                            .lineLimit(1)
                            .minimumScaleFactor(0.72)

                        Text("·")

                        Text(formattedDate)
                            .lineLimit(1)

                        Text("·")

                        Text("via KeeNotes")
                            .lineLimit(1)

                        Spacer(minLength: 0)
                    }
                    .font(.system(size: 11, weight: .medium))
                    .foregroundColor(Color.black.opacity(0.34))
                    .padding(.horizontal, cardPadding)
                    .padding(.vertical, footerVerticalPadding)
                }
                .background(
                    RoundedRectangle(cornerRadius: 28, style: .continuous)
                        .fill(Color(red: 0.995, green: 0.992, blue: 0.982))
                        .shadow(color: Color.black.opacity(0.055), radius: 32, x: 0, y: 18)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 28, style: .continuous)
                        .stroke(Color.white.opacity(0.82), lineWidth: 0.8)
                )
            }
            .padding(.horizontal, posterHorizontalPadding)
            .padding(.vertical, contentLength > 700 ? 34 : 42)
        }
        .frame(minHeight: 540)
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

private enum PosterRenderer {
    @MainActor
    static func render<Content: View>(view: Content, width: CGFloat) -> UIImage {
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

        let renderer = UIGraphicsImageRenderer(size: size)
        return renderer.image { _ in
            renderView.drawHierarchy(in: renderView.bounds, afterScreenUpdates: true)
        }
    }
}

private extension UIImage {
    func opaquePosterImage() -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = scale
        format.opaque = true

        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            UIColor.white.setFill()
            UIBezierPath(rect: CGRect(origin: .zero, size: size)).fill()
            draw(in: CGRect(origin: .zero, size: size))
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
