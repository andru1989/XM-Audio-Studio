import SwiftUI
import AppKit
import SonyHeadphonesKit

/// The hero image at the top of the dashboard, for the detected model.
///
/// Order: a photo the user supplies (`~/Library/Application Support/XM Audio Studio/
/// <model-id>.png|jpg` or `headphones.png`), then the product photo bundled under
/// `Resources/Models/` (Sony's images, personal use only: shared/model-images/SOURCES.md),
/// then the original vector illustration.
struct HeadphoneImage: View {
    @EnvironmentObject private var controller: HeadphonesController

    private var model: HeadphoneModel? { HeadphoneModel.from(bluetoothName: controller.deviceName) }

    var body: some View {
        if let photo = Self.image(for: model) {
            Image(nsImage: photo)
                .resizable()
                .interpolation(.high)
                .scaledToFit()
        } else {
            HeadphoneIllustration()
        }
    }

    static func image(for model: HeadphoneModel?) -> NSImage? {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first?
            .appendingPathComponent("XM Audio Studio", isDirectory: true)
        var candidates: [URL] = []
        if let support {
            if let model {
                candidates += ["png", "jpg"].map { support.appendingPathComponent("\(model.rawValue).\($0)") }
            }
            candidates.append(support.appendingPathComponent("headphones.png"))
        }
        if let model, let resources = Bundle.main.resourceURL?.appendingPathComponent("Models") {
            candidates += ["jpg", "png"].map { resources.appendingPathComponent("\(model.rawValue).\($0)") }
        }
        for url in candidates {
            if let image = NSImage(contentsOf: url) { return image }
        }
        return nil
    }
}

/// Original vector artwork: front view of black over-ear headphones.
struct HeadphoneIllustration: View {
    var body: some View {
        GeometryReader { geo in
            let s = min(geo.size.width, geo.size.height)

            ZStack {
                // Headband (outer)
                HeadbandArc(inset: 0)
                    .stroke(
                        LinearGradient(
                            colors: [Color(red: 0.24, green: 0.24, blue: 0.26), Color(red: 0.10, green: 0.10, blue: 0.12)],
                            startPoint: .top,
                            endPoint: .bottom
                        ),
                        style: StrokeStyle(lineWidth: s * 0.085, lineCap: .round)
                    )

                // Headband cushion (inner, slightly lighter)
                HeadbandArc(inset: s * 0.012)
                    .stroke(
                        Color(red: 0.30, green: 0.30, blue: 0.33).opacity(0.9),
                        style: StrokeStyle(lineWidth: s * 0.03, lineCap: .round)
                    )

                // Yokes connecting band ends to the cups
                Capsule()
                    .fill(Color(red: 0.16, green: 0.16, blue: 0.18))
                    .frame(width: s * 0.035, height: s * 0.10)
                    .rotationEffect(.degrees(-14))
                    .offset(x: -s * 0.315, y: s * 0.02)

                Capsule()
                    .fill(Color(red: 0.16, green: 0.16, blue: 0.18))
                    .frame(width: s * 0.035, height: s * 0.10)
                    .rotationEffect(.degrees(14))
                    .offset(x: s * 0.315, y: s * 0.02)

                earCup(size: s)
                    .rotationEffect(.degrees(-7))
                    .offset(x: -s * 0.33, y: s * 0.175)

                earCup(size: s)
                    .scaleEffect(x: -1)
                    .rotationEffect(.degrees(7))
                    .offset(x: s * 0.33, y: s * 0.175)
            }
            .frame(width: geo.size.width, height: geo.size.height)
        }
        .aspectRatio(1.05, contentMode: .fit)
    }

    private func earCup(size s: CGFloat) -> some View {
        ZStack {
            // Cup body
            RoundedRectangle(cornerRadius: s * 0.115)
                .fill(
                    LinearGradient(
                        colors: [Color(red: 0.17, green: 0.17, blue: 0.19), Color(red: 0.05, green: 0.05, blue: 0.06)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
                .frame(width: s * 0.235, height: s * 0.335)
                .shadow(color: .black.opacity(0.35), radius: s * 0.02, y: s * 0.012)

            // Soft top-edge sheen
            RoundedRectangle(cornerRadius: s * 0.115)
                .strokeBorder(
                    LinearGradient(
                        colors: [.white.opacity(0.16), .clear],
                        startPoint: .top,
                        endPoint: .center
                    ),
                    lineWidth: s * 0.008
                )
                .frame(width: s * 0.235, height: s * 0.335)

            // Diagonal highlight
            Ellipse()
                .fill(.white.opacity(0.05))
                .frame(width: s * 0.10, height: s * 0.22)
                .rotationEffect(.degrees(18))
                .offset(x: -s * 0.045, y: -s * 0.05)
        }
    }
}

/// Top arc of the headband.
private struct HeadbandArc: Shape {
    var inset: CGFloat

    func path(in rect: CGRect) -> Path {
        var path = Path()
        let s = min(rect.width, rect.height)
        path.addArc(
            center: CGPoint(x: rect.midX, y: rect.midY + s * 0.16),
            radius: s * 0.335 - inset,
            startAngle: .degrees(197),
            endAngle: .degrees(343),
            clockwise: false
        )
        return path
    }
}
