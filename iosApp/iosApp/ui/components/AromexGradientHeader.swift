import SwiftUI

/// Reusable brand header: a blue vertical gradient with a faint outlined
/// circle in the top-right. **Rectangular — no rounded corners** (per the
/// updated design after ticket #21 review).
///
/// The header extends **under the notch / status bar** (`.ignoresSafeArea(.top)`);
/// its content uses the safe-area inset internally as its top padding so the
/// mark + wordmark clear the notch cleanly.
struct AromexGradientHeader: View {
    let title: String
    let subtitle: String
    var contentHeight: CGFloat = 220

    @Environment(\.aromexColors) private var colors
    @Environment(\.aromexTypography) private var typography
    @Environment(\.aromexDimensions) private var dimensions

    var body: some View {
        GeometryReader { proxy in
            let topInset = proxy.safeAreaInsets.top
            ZStack(alignment: .topLeading) {
                LinearGradient(
                    colors: [colors.headerGradientStart, colors.headerGradientEnd],
                    startPoint: .top,
                    endPoint: .bottom
                )

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
                }
                .allowsHitTesting(false)

                VStack(alignment: .leading, spacing: 0) {
                    HStack(spacing: dimensions.space12) {
                        AromexMark(size: 44, foreground: colors.onBrand)
                        Text("AROMEX")
                            // Larger + tracked for the header presence.
                            .font(.system(size: 26, weight: .bold, design: .default))
                            .tracking(2)
                            .foregroundStyle(colors.onBrand)
                            .lineLimit(1)
                            .truncationMode(.tail)
                    }
                    Spacer()
                    VStack(alignment: .leading, spacing: dimensions.space8) {
                        Text(title)
                            .font(typography.screenTitle)
                            .foregroundStyle(colors.onBrand)
                            .lineLimit(2)
                            .truncationMode(.tail)
                        Text(subtitle)
                            .font(typography.body)
                            .foregroundStyle(colors.onBrand.opacity(0.75))
                            .lineLimit(2)
                            .truncationMode(.tail)
                    }
                }
                // Extra top gap below the notch so the mark sits comfortably
                // below the status-bar icons, per PM feedback.
                .padding(.top, topInset + dimensions.space24)
                .padding(.horizontal, dimensions.screenPadding)
                .padding(.bottom, dimensions.space24)
                .frame(width: proxy.size.width, height: topInset + contentHeight, alignment: .topLeading)
            }
            .frame(width: proxy.size.width, height: topInset + contentHeight)
            // Rectangular — no rounded corners (per the updated design after
            // ticket #21 review).
            .ignoresSafeArea(edges: .top)
        }
        .frame(height: contentHeight)
    }
}
