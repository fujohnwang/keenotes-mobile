import SwiftUI

enum PosterInkTheme: CaseIterable, Equatable {
    case mei
    case lan
    case zhu
    case ju

    var assetName: String {
        switch self {
        case .mei: return "PosterInkMei"
        case .lan: return "PosterInkLan"
        case .zhu: return "PosterInkZhu"
        case .ju: return "PosterInkJu"
        }
    }

    var label: String {
        switch self {
        case .mei: return "梅"
        case .lan: return "兰"
        case .zhu: return "竹"
        case .ju: return "菊"
        }
    }

    var alignment: Alignment {
        switch self {
        case .mei: return .bottomLeading
        case .lan: return .bottomLeading
        case .zhu: return .bottomTrailing
        case .ju: return .bottomLeading
        }
    }

    static func stable(forNoteID noteID: Int64) -> PosterInkTheme {
        let index = Int(abs(noteID) % Int64(allCases.count))
        return allCases[index]
    }

    func next() -> PosterInkTheme {
        let index = (Self.allCases.firstIndex(of: self)! + 1) % Self.allCases.count
        return Self.allCases[index]
    }

    func opacity(for contentLength: Int) -> Double {
        let base: Double
        if contentLength > 900 {
            base = 0.07
        } else if contentLength > 500 {
            base = 0.09
        } else if contentLength > 220 {
            base = 0.10
        } else {
            base = 0.11
        }
        return base
    }
}

struct InkWashThemeOverlay: View {
    let theme: PosterInkTheme
    let contentLength: Int

    var body: some View {
        GeometryReader { geometry in
            let inkHeight = geometry.size.width / PosterShareRenderer.aspectRatio

            Image(theme.assetName)
                .resizable()
                .scaledToFit()
                .frame(width: geometry.size.width, height: inkHeight)
                .frame(width: geometry.size.width, height: geometry.size.height, alignment: .bottom)
                .opacity(theme.opacity(for: contentLength))
                .blendMode(.multiply)
                .clipped()
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}
