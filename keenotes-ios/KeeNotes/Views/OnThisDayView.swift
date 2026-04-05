import SwiftUI

/// "On this day in years past" view
/// Shows notes from the same month/day in previous years
struct OnThisDayView: View {
    @EnvironmentObject var appState: AppState
    @Environment(\.dismiss) private var dismiss
    @State private var enlargedNote: Note? = nil
    @Environment(\.colorScheme) private var colorScheme

    private var isPad: Bool { DeviceType.isPad }
    private var horizontalPadding: CGFloat { DeviceType.horizontalPadding }
    private var notes: [Note] { appState.onThisDayNotes }
    private var topButtonSize: CGFloat { isPad ? 44 : 40 }
    private var topIconSize: CGFloat { isPad ? 19 : 17 }

    var body: some View {
        ZStack {
            Theme.pageBackground(colorScheme).ignoresSafeArea()

            VStack(spacing: 0) {
                topHeader
                    .padding(.horizontal, horizontalPadding)
                    .padding(.top, 6)
                    .padding(.bottom, 2)

                // Header
                HStack {
                    (Text("\(notes.count)").foregroundColor(Theme.brandColor).fontWeight(.semibold) +
                     Text(" note(s) from past years").foregroundColor(.secondary))
                        .font(.caption)
                    Spacer()
                }
                .padding(EdgeInsets(top: 8, leading: horizontalPadding, bottom: 8, trailing: horizontalPadding))

                ZStack {
                    List {
                        ForEach(notes) { note in
                            NoteRow(note: note, onEnlarge: {
                                withAnimation(.easeInOut(duration: 0.25)) {
                                    enlargedNote = note
                                }
                            })
                            .listRowInsets(EdgeInsets(top: 0, leading: horizontalPadding, bottom: 0, trailing: horizontalPadding))
                            .listRowSeparator(.hidden)
                            .listRowBackground(Color.clear)
                        }

                        // Bottom safe area spacer
                        Color.clear
                            .frame(height: 80)
                            .listRowInsets(EdgeInsets())
                            .listRowSeparator(.hidden)
                            .listRowBackground(Color.clear)
                    }
                    .listStyle(.plain)
                    .modifier(ListBackgroundModifier())
                    .opacity(enlargedNote == nil ? 1 : 0)
                    .allowsHitTesting(enlargedNote == nil)

                    // Enlarged note overlay
                    if let enlarged = enlargedNote {
                        EnlargedNoteView(note: enlarged) {
                            withAnimation(.easeInOut(duration: 0.25)) {
                                enlargedNote = nil
                            }
                        }
                    }
                }
            }
        }
        .navigationBarHidden(true)
    }

    private var topHeader: some View {
        HStack(spacing: 12) {
            Button(action: { dismiss() }) {
                Image(systemName: "chevron.left")
                    .font(.system(size: topIconSize, weight: .medium))
                    .foregroundColor(.primary)
                    .frame(width: topButtonSize, height: topButtonSize)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Spacer(minLength: 0)

            Text("On this day in years past")
                .font(.system(size: isPad ? 20 : 19, weight: .semibold))
                .foregroundColor(.primary)
                .lineLimit(1)

            Spacer(minLength: 0)

            Color.clear
                .frame(width: topButtonSize, height: topButtonSize)
        }
        .frame(height: topButtonSize)
    }
}
