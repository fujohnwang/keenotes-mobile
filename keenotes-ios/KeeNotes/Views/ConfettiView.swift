import SwiftUI

/// A self-contained confetti overlay that triggers when `isActive` becomes true.
/// Automatically resets after the animation completes.
struct ConfettiView: View {
    @Binding var isActive: Bool

    private let particleCount = 50
    private let colors: [Color] = [.red, .orange, .yellow, .green, .blue, .purple, .pink, .mint, .cyan]
    private let shapes: [ConfettiShape] = [.circle, .rectangle, .triangle]
    private let duration: Double = 2.0

    @State private var particles: [ConfettiParticle] = []
    @State private var animating = false

    var body: some View {
        ZStack {
            ForEach(particles) { p in
                ConfettiPiece(shape: p.shape, color: p.color)
                    .frame(width: p.size, height: p.size * p.aspectRatio)
                    .rotationEffect(.degrees(animating ? p.spinEnd : 0))
                    .offset(
                        x: animating ? p.xEnd : 0,
                        y: animating ? p.yEnd : 300
                    )
                    .opacity(animating ? 0 : 1)
                    .scaleEffect(animating ? p.scaleEnd : 0.2)
            }
        }
        .allowsHitTesting(false)
        .onChange(of: isActive) { newValue in
            if newValue { fire() }
        }
    }

    private func fire() {
        particles = (0..<particleCount).map { _ in ConfettiParticle.random(colors: colors, shapes: shapes) }
        animating = false

        // Tiny delay so SwiftUI registers the initial state before animating
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
            withAnimation(.easeOut(duration: duration)) {
                animating = true
            }
        }

        // Reset after animation completes
        DispatchQueue.main.asyncAfter(deadline: .now() + duration + 0.1) {
            animating = false
            particles = []
            isActive = false
        }
    }
}

// MARK: - Data model

private enum ConfettiShape: CaseIterable {
    case circle, rectangle, triangle
}

private struct ConfettiParticle: Identifiable {
    let id = UUID()
    let color: Color
    let shape: ConfettiShape
    let size: CGFloat
    let aspectRatio: CGFloat
    let xEnd: CGFloat
    let yEnd: CGFloat
    let spinEnd: Double
    let scaleEnd: CGFloat

    static func random(colors: [Color], shapes: [ConfettiShape]) -> ConfettiParticle {
        ConfettiParticle(
            color: colors.randomElement()!,
            shape: shapes.randomElement()!,
            size: CGFloat.random(in: 6...12),
            aspectRatio: CGFloat.random(in: 0.5...1.5),
            xEnd: CGFloat.random(in: -180...180),
            yEnd: CGFloat.random(in: (-500)...(-50)),
            spinEnd: Double.random(in: -720...720),
            scaleEnd: CGFloat.random(in: 0.3...1.2)
        )
    }
}

// MARK: - Shape rendering

private struct ConfettiPiece: View {
    let shape: ConfettiShape
    let color: Color

    var body: some View {
        switch shape {
        case .circle:
            Circle().fill(color)
        case .rectangle:
            Rectangle().fill(color)
        case .triangle:
            TriangleShape().fill(color)
        }
    }
}

private struct TriangleShape: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: rect.midX, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
        p.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
        p.closeSubpath()
        return p
    }
}
