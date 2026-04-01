import SwiftUI

/// Centralized brand theme for KeeNotes premium UI
enum Theme {
    // MARK: - Brand Colors

    /// Primary brand color — a deeper, more premium blue
    static let brandColor = Color(red: 0.0, green: 0.24, blue: 0.65) // #003DA5 Klein Blue

    /// Lighter tint for secondary elements
    static let brandColorLight = Color(red: 0.0, green: 0.24, blue: 0.65).opacity(0.12)

    // MARK: - Card Styling

    /// Soft shadow for cards (replaces hard borders)
    static func cardShadow(_ colorScheme: ColorScheme) -> (color: Color, radius: CGFloat, x: CGFloat, y: CGFloat) {
        if colorScheme == .dark {
            return (Color.white.opacity(0.06), 8, 0, 2)
        }
        return (Color.black.opacity(0.08), 10, 0, 4)
    }

    /// Card background — pure white in light mode, elevated in dark mode
    static func cardBackground(_ colorScheme: ColorScheme) -> Color {
        colorScheme == .dark ? Color(.secondarySystemBackground) : Color.white
    }

    /// Page background — slightly off-white in light mode
    static func pageBackground(_ colorScheme: ColorScheme) -> Color {
        colorScheme == .dark ? Color(.systemBackground) : Color(red: 0.97, green: 0.97, blue: 0.98) // #F7F8FA
    }

    // MARK: - Separators

    /// Thin separator color for flat layouts
    static func separatorColor(_ colorScheme: ColorScheme) -> Color {
        colorScheme == .dark ? Color.white.opacity(0.08) : Color.black.opacity(0.06)
    }

    // MARK: - Typography

    /// Rounded bold font for stat numbers
    static func statNumberFont(size: CGFloat) -> Font {
        .system(size: size, weight: .bold, design: .rounded)
    }

    /// Stat label font
    static func statLabelFont(size: CGFloat) -> Font {
        .system(size: size, weight: .medium, design: .rounded)
    }

    // MARK: - Section Header Style

    /// Section header modifier for premium flat UI
    struct SectionHeaderStyle: ViewModifier {
        func body(content: Content) -> some View {
            content
                .font(.system(size: 11, weight: .semibold))
                .foregroundColor(Color.secondary.opacity(0.7))
                .textCase(.uppercase)
        }
    }

    /// Section footer modifier for premium flat UI
    struct SectionFooterStyle: ViewModifier {
        func body(content: Content) -> some View {
            content
                .font(.system(size: 12))
                .foregroundColor(Color.secondary.opacity(0.5))
        }
    }
}
