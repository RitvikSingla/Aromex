import SwiftUI
import SharedLogic

/// Splash — ticket-#21 restyle. Same blue gradient, same faint outlined-
/// circle decoration, and same `AromexMark` as the Login gradient header —
/// so Splash visually **flows** into Login (the user sees the same mark and
/// brand treatment on both).
struct SplashView: View {
    @EnvironmentObject private var loc: LocalizationManager
    @State private var isAnimating = false
    @State private var opacity: Double = 0

    @Environment(\.aromexColors) private var colors
    @Environment(\.aromexTypography) private var typography

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [colors.headerGradientStart, colors.headerGradientEnd],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()

            // Decorative outlined circles in the top-right — same treatment
            // as AromexGradientHeader on Login so the transition is
            // visually continuous.
            GeometryReader { proxy in
                TimelineView(.animation) { timeline in
                    Canvas { context, canvasSize in
                        let t = timeline.date.timeIntervalSinceReferenceDate
                        let phase = t * (2 * .pi / 20)
                        let d = canvasSize.width * 0.7

                        let bigBreath = 1 + 0.02 * sin(phase * 0.35)
                        let bigR = d / 2 * bigBreath
                        let bigCx = canvasSize.width * 0.9 + cos(phase) * 6
                        let bigCy = d * 0.35 + sin(phase) * 5
                        let big = CGRect(
                            x: bigCx - bigR,
                            y: bigCy - bigR,
                            width: bigR * 2,
                            height: bigR * 2
                        )
                        context.stroke(
                            Path(ellipseIn: big),
                            with: .color(colors.headerDecoration),
                            lineWidth: 1.5
                        )

                        let smBreath = 1 + 0.025 * sin(phase * 0.5 + .pi / 2)
                        let smR = d * 0.16 * smBreath
                        let smCx = canvasSize.width * 0.85 + cos(phase * 0.9 + .pi) * 8
                        let smCy = d * 0.21 + sin(phase * 0.9 + .pi) * 6
                        let inner = CGRect(
                            x: smCx - smR,
                            y: smCy - smR,
                            width: smR * 2,
                            height: smR * 2
                        )
                        context.stroke(
                            Path(ellipseIn: inner),
                            with: .color(colors.headerDecoration.opacity(0.6)),
                            lineWidth: 1.5
                        )
                    }
                    .frame(width: proxy.size.width, height: proxy.size.height)
                }
            }
            .allowsHitTesting(false)
            .ignoresSafeArea()

            VStack(spacing: 0) {
                Spacer()

                AromexMark(size: 88, foreground: colors.onBrand, strokeWidth: 3)
                    .scaleEffect(isAnimating ? 1.0 : 0.8)
                    .opacity(opacity)
                    .shadow(color: .black.opacity(0.2), radius: 10, x: 0, y: 5)
                    .padding(.bottom, 36)

                Text(loc.t(Strings.shared.splash_app_name))
                    .font(.system(size: 42, weight: .semibold, design: .default))
                    .tracking(2)
                    .foregroundStyle(colors.onBrand)
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .truncationMode(.tail)
                    .opacity(opacity)
                    .shadow(color: .black.opacity(0.25), radius: 8, x: 0, y: 3)
                    .padding(.horizontal, 20)
                    .padding(.bottom, 20)

                Text(loc.t(Strings.shared.splash_tagline))
                    .font(typography.hint)
                    .foregroundStyle(colors.onBrand.opacity(0.9))
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .opacity(opacity)
                    .tracking(2.0)

                Spacer()
            }
            .padding(.horizontal, 40)
        }
        .onAppear {
            withAnimation(.easeOut(duration: 0.8)) {
                opacity = 1.0
                isAnimating = true
            }
        }
    }
}
