import SwiftUI

/// "On this day in years past" view
/// Shows notes from the same month/day in previous years
struct OnThisDayView: View {
    @EnvironmentObject var appState: AppState
    @State private var enlargedNote: Note? = nil
    @Environment(\.colorScheme) private var colorScheme

    private var isPad: Bool { DeviceType.isPad }
    private var horizontalPadding: CGFloat { DeviceType.horizontalPadding }
    private var notes: [Note] { appState.onThisDayNotes }

    var body: some View {
        ZStack {
            Theme.pageBackground(colorScheme).ignoresSafeArea()

            VStack(spacing: 0) {
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
        .navigationTitle("On this day in years past")
        .navigationBarTitleDisplayMode(.inline)
    }
}
