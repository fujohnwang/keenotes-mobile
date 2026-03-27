import SwiftUI

/// Confetti overlay inspired by Swiftetti's Default Preset physics.
/// Uses TimelineView for frame-by-frame animation (iOS 15+).
struct ConfettiView: View {
    @Binding var isActive: Bool

    // Default Preset parameters (matching Swiftetti)
    private let particleCount = 100
    private let burstSpeedMin: Double = 2000
    private let burstSpeedMax: Double = 10000
    private let burstDirection: Double = 270  // degrees, 270 = upward
    private let coneSpread: Double = 120      // upwardBias
    private let gravity: Double = 1000
    private let massRange: ClosedRange<Double> = 0.5...1.5
    private let dragRange: ClosedRange<Double> = 0.8...1.2
    private let fallDurationBase: Double = 0.3
    private let wobbleAmpRange: ClosedRange<Double> = 5...15
    private let wobbleFreqRange: ClosedRange<Double> = 2...5
    private let wobbleDecay: Double = 1.0
    private let sizeRange: ClosedRange<CGFloat> = 2...20
    private let fadeStartPercent: Double = 0.8
    private let fadeDuration: Double = 0.2
    private let colors: [Color] = [.white, Color(white: 0.75), Color(white: 0.70), .blue]

    @State private var particles: [Particle] = []
    @State private var startDate: Date = .distantFuture
    @State private var isAnimating = false

    var body: some View {
        GeometryReader { geo in
            if isAnimating {
                TimelineView(.animation) { timeline in
                    let elapsed = timeline.date.timeIntervalSince(startDate)
                    Canvas { context, size in
                        for p in particles {
                            let localT = elapsed - p.delay
                            guard localT > 0 else { continue }
                            let progress = min(localT / p.totalDuration, 1.0)
                            let t = localT

                            // X: velocity * time * deceleration + wobble
                            let xDecel = 1.0 - progress * 0.9
                            let wobbleDecayF = 1.0 - (progress * wobbleDecay)
                            let wobble = sin(t * p.wobbleFreq) * p.wobbleAmp * wobbleDecayF
                            let x = p.startX + p.vx * t * xDecel * 0.15 + wobble

                            // Y: initial velocity + gravity (parabolic)
                            let y = p.startY + p.vy * t * 0.3 + 0.5 * gravity * t * t * p.mass * 0.8

                            // Opacity: fade in last portion
                            let opacity: Double
                            if progress < fadeStartPercent {
                                opacity = 1.0
                            } else if fadeDuration > 0 {
                                opacity = max(0, 1.0 - (progress - fadeStartPercent) / fadeDuration)
                            } else {
                                opacity = 0
                            }

                            guard opacity > 0 else { continue }

                            let pWidth = p.size
                            let pHeight = p.size * p.aspectRatio
                            let rect = CGRect(
                                x: CGFloat(x) - pWidth / 2.0,
                                y: CGFloat(y) - pHeight / 2.0,
                                width: pWidth,
                                height: pHeight
                            )
                            context.opacity = opacity
                            // Rotate the confetti piece
                            let angle = Angle.degrees(p.rotationSpeed * t)
                            var ctx = context
                            ctx.translateBy(x: rect.midX, y: rect.midY)
                            ctx.rotate(by: angle)
                            let hw = rect.width / 2.0
                            let hh = rect.height / 2.0
                            let centered = CGRect(x: -hw, y: -hh, width: rect.width, height: rect.height)
                            if p.isCircle {
                                ctx.fill(Path(ellipseIn: centered), with: .color(p.color))
                            } else {
                                ctx.fill(Path(centered), with: .color(p.color))
                            }
                        }
                    }
                }
            }
        }
        .allowsHitTesting(false)
        .onChange(of: isActive) { newValue in
            if newValue { fire() }
        }
    }

    private func fire() {
        let screen = UIScreen.main.bounds
        let burstX = screen.width * 0.5
        let burstY: CGFloat = 400

        let dirRad = burstDirection * .pi / 180
        let coneRad = coneSpread * .pi / 180

        particles = (0..<particleCount).map { _ in
            let angle = dirRad + Double.random(in: -coneRad/2...coneRad/2)
            let speed = Double.random(in: burstSpeedMin...burstSpeedMax)
            let mass = Double.random(in: massRange)
            let drag = Double.random(in: dragRange)
            let totalDuration = fallDurationBase + (1.0 / mass) + (drag * 0.5)

            return Particle(
                startX: burstX + CGFloat.random(in: -20...20),
                startY: burstY,
                vx: cos(angle) * speed,
                vy: sin(angle) * speed,
                mass: mass,
                drag: drag,
                wobbleAmp: Double.random(in: wobbleAmpRange),
                wobbleFreq: Double.random(in: wobbleFreqRange),
                size: CGFloat.random(in: sizeRange),
                aspectRatio: Bool.random() ? 1.0 : CGFloat.random(in: 0.4...0.8),  // rectangles = paper-like
                isCircle: Double.random(in: 0...1) < 0.2,  // 20% circles, 80% rectangles
                rotationSpeed: Double.random(in: -360...360),
                color: colors.randomElement() ?? .white,
                totalDuration: totalDuration,
                delay: Double.random(in: 0...0.05)
            )
        }

        startDate = Date()
        isAnimating = true

        // Find max duration and clean up after
        let maxDuration = particles.map { $0.totalDuration + $0.delay }.max() ?? 3.0
        DispatchQueue.main.asyncAfter(deadline: .now() + maxDuration + 0.5) {
            isAnimating = false
            particles = []
            isActive = false
        }
    }
}

// MARK: - Particle data

private struct Particle: Identifiable {
    let id = UUID()
    let startX: CGFloat
    let startY: CGFloat
    let vx: Double
    let vy: Double
    let mass: Double
    let drag: Double
    let wobbleAmp: Double
    let wobbleFreq: Double
    let size: CGFloat
    let aspectRatio: CGFloat   // 1.0 = square, <1 = tall rectangle (paper-like)
    let isCircle: Bool
    let rotationSpeed: Double  // degrees per second
    let color: Color
    let totalDuration: Double
    let delay: Double
}
