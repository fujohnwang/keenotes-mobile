import SwiftUI
import UIKit

/// Main view with custom floating dock navigation
struct MainTabView: View {
    @EnvironmentObject var appState: AppState
    @Environment(\.colorScheme) private var colorScheme

    /// Tracks the swipe direction for slide transition
    @State private var swipeDirection: SwipeDirection = .none
    @State private var textSelection = NavigationTextSelection()

    private let tabCount = 3
    /// Minimum horizontal distance to trigger a swipe
    private let swipeThreshold: CGFloat = 50

    var body: some View {
        ZStack(alignment: .bottom) {
            // Layer 1: Content views with swipe gesture
            Group {
                switch appState.selectedTab {
                case 0:
                    NoteView().id(appState.configurationRevision)
                case 1:
                    ReviewView().id(appState.configurationRevision)
                case 2:
                    SettingsView()
                default:
                    NoteView().id(appState.configurationRevision)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .id(appState.selectedTab) // Preserve Settings feedback while replacing account-specific note views.
            .transition(.asymmetric(
                insertion: .move(edge: swipeDirection == .left ? .trailing : .leading),
                removal: .move(edge: swipeDirection == .left ? .leading : .trailing)
            ))
            .simultaneousGesture(
                DragGesture(minimumDistance: swipeThreshold)
                    .onChanged { _ in
                        textSelection.updateForCurrentTouch()
                    }
                    .onEnded { value in
                        guard !textSelection.blocksNavigation else { return }
                        let horizontal = value.translation.width
                        let vertical = value.translation.height
                        // Only trigger if horizontal movement clearly dominates
                        guard abs(horizontal) > abs(vertical) * 1.5 else { return }

                        if appState.isInSubPage {
                            // Sub-page: swipe right to go back
                            if horizontal > 0 {
                                appState.subPageDismissTrigger += 1
                            }
                            return
                        }

                        if horizontal < 0, appState.selectedTab < tabCount - 1 {
                            // Swipe left → next tab
                            swipeDirection = .left
                            withAnimation(.easeInOut(duration: 0.25)) {
                                appState.selectedTab += 1
                            }
                        } else if horizontal > 0, appState.selectedTab > 0 {
                            // Swipe right → previous tab
                            swipeDirection = .right
                            withAnimation(.easeInOut(duration: 0.25)) {
                                appState.selectedTab -= 1
                            }
                        }
                    }
            )

            // Layer 2: Fade-out gradient mask above dock
            VStack(spacing: 0) {
                Spacer()
                LinearGradient(
                    colors: [
                        Theme.pageBackground(colorScheme).opacity(0),
                        Theme.pageBackground(colorScheme).opacity(0.85),
                        Theme.pageBackground(colorScheme)
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )
                .frame(height: 80)
                .allowsHitTesting(false)
            }

            // Layer 3: Floating Dock
            FloatingDockView(selectedTab: $appState.selectedTab) { targetIndex in
                guard targetIndex != appState.selectedTab else { return }
                swipeDirection = targetIndex > appState.selectedTab ? .left : .right
                withAnimation(.easeInOut(duration: 0.25)) {
                    appState.selectedTab = targetIndex
                }
            }
            .padding(.bottom, 16)
        }
        .ignoresSafeArea(.container, edges: .bottom)
        .ignoresSafeArea(.keyboard, edges: .bottom)
        .background(TextSelectionObserver(selection: textSelection))
    }
}

// MARK: - Swipe Direction

private enum SwipeDirection {
    case none, left, right
}

/// Samples the native selection without replacing SwiftUI's selectable Text or its gestures.
@MainActor
private final class NavigationTextSelection {
    weak var window: UIWindow?
    private var selectionWasActiveDuringTouch = false

    var blocksNavigation: Bool {
        selectionWasActiveDuringTouch || hasActiveSelection
    }

    func beginTouch() {
        selectionWasActiveDuringTouch = hasActiveSelection
    }

    func updateForCurrentTouch() {
        // Keep this touch blocked even if dragging a handle collapses the selection.
        selectionWasActiveDuringTouch = blocksNavigation
    }

    private var hasActiveSelection: Bool {
        guard let window else { return false }
        return Self.hasActiveSelection(in: window)
    }

    private static func hasActiveSelection(in view: UIView) -> Bool {
        guard !view.isHidden, view.alpha > 0.01 else { return false }
        if view.isFirstResponder, hasSelection(view as? UITextInput) {
            return true
        }
        for interaction in view.interactions {
            // SwiftUI can host selection through an interaction rather than a UITextView.
            if let interaction = interaction as? UITextInteraction,
               let input = interaction.textInput,
               input.isFirstResponder, hasSelection(input) {
                return true
            }
            if #available(iOS 17.0, *),
               let interaction = interaction as? UITextSelectionDisplayInteraction,
               interaction.isActivated, hasSelection(interaction.textInput) {
                return true
            }
        }
        return view.subviews.contains { hasActiveSelection(in: $0) }
    }

    private static func hasSelection(_ input: UITextInput?) -> Bool {
        guard let range = input?.selectedTextRange else { return false }
        return !range.isEmpty
    }
}

private struct TextSelectionObserver: UIViewRepresentable {
    let selection: NavigationTextSelection

    func makeUIView(context: Context) -> ObservationView {
        ObservationView(selection: selection)
    }

    func updateUIView(_ uiView: ObservationView, context: Context) {}

    static func dismantleUIView(_ uiView: ObservationView, coordinator: ()) {
        uiView.detach()
    }

    final class ObservationView: UIView, UIGestureRecognizerDelegate {
        private let selection: NavigationTextSelection
        private let touchObserver = UITapGestureRecognizer()
        private weak var observedWindow: UIWindow?

        init(selection: NavigationTextSelection) {
            self.selection = selection
            super.init(frame: .zero)
            isUserInteractionEnabled = false
            touchObserver.delegate = self
            touchObserver.cancelsTouchesInView = false
        }

        required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            detach()
            observedWindow = window
            selection.window = window
            window?.addGestureRecognizer(touchObserver)
        }

        func detach() {
            observedWindow?.removeGestureRecognizer(touchObserver)
            observedWindow = nil
            selection.window = nil
        }

        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
            // Snapshot before a drag can clear the selection. Reject every touch so this
            // observer never recognizes a gesture or interferes with scrolling/dock taps.
            selection.beginTouch()
            return false
        }
    }
}
