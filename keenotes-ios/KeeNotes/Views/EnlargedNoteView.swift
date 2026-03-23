import SwiftUI

/// Enlarged view for a single note, filling the content area between Title and Dock
/// Supports tap-to-copy and text selection, same as NoteRow
struct EnlargedNoteView: View {
    let note: Note
    let onDismiss: () -> Void
    @EnvironmentObject var appState: AppState
    
    @State private var showCopiedAlert = false
    @State private var textViewHeight: CGFloat?
    
    private var isPad: Bool { DeviceType.isPad }
    private var cardPadding: CGFloat { isPad ? 32 : 20 }
    private var messageFontSize: CGFloat { isPad ? 24 : 22 }
    
    private var formattedDate: String {
        // Parse UTC time and convert to local timezone for display
        let inputFormatter = DateFormatter()
        inputFormatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
        inputFormatter.timeZone = TimeZone(identifier: "UTC")

        if let date = inputFormatter.date(from: note.createdAt) {
            let displayFormatter = DateFormatter()
            displayFormatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
            displayFormatter.timeZone = TimeZone.current // Use system local timezone
            return displayFormatter.string(from: date)
        }

        // Fallback: try ISO format
        inputFormatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        inputFormatter.timeZone = TimeZone(identifier: "UTC")

        if let date = inputFormatter.date(from: note.createdAt) {
            let displayFormatter = DateFormatter()
            displayFormatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
            displayFormatter.timeZone = TimeZone.current
            return displayFormatter.string(from: date)
        }

        // If all else fails, return as-is
        return note.createdAt
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
                
                Button(action: onDismiss) {
                    Image(systemName: "arrow.up.right.and.arrow.down.left")
                        .font(.system(size: isPad ? 20 : 17))
                        .foregroundColor(.secondary)
                        .padding(8)
                        .contentShape(Rectangle())
                }
            }
            .padding(.horizontal, cardPadding)
            .padding(.top, cardPadding)
            .padding(.bottom, 12)
            
            // Note content — scrollable, selectable, tap to copy
            ScrollView {
                SelectableTextView(
                    text: note.content,
                    fontSize: messageFontSize,
                    onTap: copyToClipboard,
                    onCopyMenuAction: showCopiedNotification,
                    onHeightChange: { height in
                        textViewHeight = height
                    }
                )
                .frame(height: textViewHeight)
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
                        .padding(.bottom, 20)
                    }
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
        )
        .padding(.horizontal, DeviceType.horizontalPadding)
        .padding(.vertical, 8)
        .transition(.opacity.combined(with: .scale(scale: 0.95)))
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
}
