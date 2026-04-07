import SwiftUI
import UIKit

/// Shared note card used by Review, Search and On This Day lists.
struct NoteRow: View {
    let note: Note
    var onEnlarge: (() -> Void)? = nil
    @EnvironmentObject var appState: AppState
    @State private var showCopiedAlert = false
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
