import SwiftUI

/// Enlarged view for a single note, filling the content area between Title and Dock
/// Supports tap-to-copy and text selection, same as NoteRow
struct EnlargedNoteView: View {
    let note: Note
    let onDismiss: () -> Void
    @EnvironmentObject var appState: AppState
    
    @State private var showCopiedAlert = false
    @State private var showSharePoster = false
    @State private var dragOffset: CGFloat = 0
    
    private var isPad: Bool { DeviceType.isPad }
    private var cardPadding: CGFloat { isPad ? 32 : 20 }
    private var messageFontSize: CGFloat { isPad ? 24 : 22 }
    private var copiedToastBottomPadding: CGFloat { isPad ? 120 : 92 }
    private var dismissDragThreshold: CGFloat { isPad ? 180 : 120 }
    
    private var formattedDate: String {
        Theme.formatNoteDate(note.createdAt, compact: appState.settingsService.compactDateFormat)
    }
    
    var body: some View {
        VStack(spacing: 0) {
            // Header: date/channel (left) + shrink button (right)
            HStack {
                HStack(spacing: isPad ? 10 : 8) {
                    HStack(spacing: 4) {
                        Image(systemName: "clock")
                            .font(.caption)
                        Text(formattedDate)
                            .font(.subheadline)
                    }
                    .foregroundColor(.secondary)
                    
                    if !note.channel.isEmpty {
                        Text("•")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                        Text(note.channel)
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                    }
                }
                
                Spacer()

                HStack(spacing: 4) {
                    Button(action: { showSharePoster = true }) {
                        Image(systemName: "square.and.arrow.up")
                            .font(.system(size: isPad ? 20 : 17))
                            .foregroundColor(.secondary)
                            .padding(8)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)

                    Button(action: onDismiss) {
                        Image(systemName: "arrow.up.right.and.arrow.down.left")
                            .font(.system(size: isPad ? 20 : 17))
                            .foregroundColor(.secondary)
                            .padding(8)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, cardPadding)
            .padding(.top, cardPadding)
            .padding(.bottom, 12)
            
            // Note content — scrollable, selectable, tap to copy
            ScrollView {
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
                .padding(.horizontal, cardPadding)
                .padding(.bottom, cardPadding)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(
            RoundedRectangle(cornerRadius: DeviceType.cornerRadius)
                .fill(Color(.systemBackground))
                .shadow(color: Color.black.opacity(0.08), radius: 12, x: 0, y: 4)
        )
        .overlay(
            RoundedRectangle(cornerRadius: DeviceType.cornerRadius)
                .stroke(Color(.systemGray5), lineWidth: 1)
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
                        .padding(.bottom, copiedToastBottomPadding)
                    }
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
        )
        .padding(.horizontal, DeviceType.horizontalPadding)
        .padding(.vertical, 8)
        .offset(y: dragOffset)
        .simultaneousGesture(dismissDragGesture)
        .transition(.opacity.combined(with: .scale(scale: 0.95)))
        .fullScreenCover(isPresented: $showSharePoster) {
            NoteSharePosterOverlay(
                note: note,
                formattedDate: formattedDate,
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
        
        withAnimation { showCopiedAlert = true }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            withAnimation { showCopiedAlert = false }
        }
    }

    private var dismissDragGesture: some Gesture {
        DragGesture(minimumDistance: 12)
            .onChanged { value in
                let horizontal = value.translation.width
                let vertical = value.translation.height

                guard vertical > 0, vertical > abs(horizontal) else { return }
                dragOffset = vertical
            }
            .onEnded { value in
                let horizontal = value.translation.width
                let vertical = value.translation.height
                let predictedVertical = value.predictedEndTranslation.height

                guard vertical > 0, vertical > abs(horizontal) else {
                    withAnimation(.spring(response: 0.28, dampingFraction: 0.85)) {
                        dragOffset = 0
                    }
                    return
                }

                if vertical > dismissDragThreshold || predictedVertical > dismissDragThreshold * 1.3 {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        dragOffset = max(vertical, dismissDragThreshold)
                    }
                    onDismiss()
                } else {
                    withAnimation(.spring(response: 0.28, dampingFraction: 0.85)) {
                        dragOffset = 0
                    }
                }
            }
    }
}
