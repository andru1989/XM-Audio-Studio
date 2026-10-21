import SwiftUI
import AudioStudioKit

/// Colours from `shared/design-tokens/tokens.json`. Blue is always the headphones' own EQ,
/// amber is always local DSP, purple is a target/measured curve.
enum StudioColor {
    static let deviceEq = Color(light: 0x1E7FD8, dark: 0x7CC4FF)
    static let dspEq = Color(light: 0xC27A1A, dark: 0xF2B66D)
    static let target = Color(light: 0x7B4FD6, dark: 0xC9A7FF)
    static let warning = Color(light: 0xB7791F, dark: 0xF2B66D)
    static let grid = Color(light: 0xDDE2E8, dark: 0x2A313B)
}

extension Color {
    init(light: UInt32, dark: UInt32) {
        self.init(nsColor: NSColor(name: nil) { appearance in
            let hex = appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? dark : light
            return NSColor(
                srgbRed: CGFloat((hex >> 16) & 0xff) / 255,
                green: CGFloat((hex >> 8) & 0xff) / 255,
                blue: CGFloat(hex & 0xff) / 255,
                alpha: 1
            )
        })
    }
}

struct ChartCurve {
    var frequencies: [Double]
    var gainsDb: [Double]
    var color: Color
    /// Curves computed from the device model are predictions and are drawn dashed.
    var dashed: Bool
    var lineWidth: CGFloat = 2
}

/// Log-frequency magnitude chart, 20 Hz to 20 kHz.
struct ResponseChart: View {
    var curves: [ChartCurve]
    var bandFrequencies: [Double]
    var bandGainsDb: [Double]
    var rangeDb: Double = 12
    var showsDbLabels = true
    /// Band markers: blue for the headphones' EQ, amber for the local DSP.
    var markerColor: Color = StudioColor.deviceEq

    private static let minHz = 20.0
    private static let maxHz = 20_000.0

    var body: some View {
        Canvas { context, size in
            // dB labels get their own gutter so they never sit on top of a curve.
            let gutter: CGFloat = showsDbLabels ? 38 : 0
            let plotWidth = size.width - gutter
            func x(_ f: Double) -> CGFloat {
                gutter + CGFloat(log10(f / Self.minHz) / log10(Self.maxHz / Self.minHz)) * plotWidth
            }
            func y(_ db: Double) -> CGFloat {
                min(max(size.height / 2 - CGFloat(db / rangeDb) * size.height / 2, 0), size.height)
            }

            for f in bandFrequencies {
                var line = Path()
                line.move(to: CGPoint(x: x(f), y: 0))
                line.addLine(to: CGPoint(x: x(f), y: size.height))
                context.stroke(line, with: .color(StudioColor.grid), lineWidth: 1)
            }
            for db in stride(from: -rangeDb, through: rangeDb, by: 6) {
                var line = Path()
                line.move(to: CGPoint(x: gutter, y: y(db)))
                line.addLine(to: CGPoint(x: size.width, y: y(db)))
                context.stroke(line, with: .color(db == 0 ? Color.secondary.opacity(0.6) : StudioColor.grid), lineWidth: db == 0 ? 1.5 : 1)
                if showsDbLabels && db != -rangeDb && db != rangeDb {
                    context.draw(
                        Text("\(db > 0 ? "+" : "")\(Int(db)) dB").font(.system(size: 9)).foregroundColor(.secondary),
                        at: CGPoint(x: gutter - 6, y: y(db)), anchor: .trailing
                    )
                }
            }
            for curve in curves where !curve.frequencies.isEmpty {
                var path = Path()
                for (i, f) in curve.frequencies.enumerated() {
                    let p = CGPoint(x: x(f), y: y(curve.gainsDb[i]))
                    if i == 0 { path.move(to: p) } else { path.addLine(to: p) }
                }
                context.stroke(path, with: .color(curve.color),
                               style: StrokeStyle(lineWidth: curve.lineWidth, lineCap: .round, lineJoin: .round, dash: curve.dashed ? [7, 4] : []))
            }
            for (f, g) in zip(bandFrequencies, bandGainsDb) {
                let p = CGPoint(x: x(f), y: y(g))
                context.fill(Path(ellipseIn: CGRect(x: p.x - 4.5, y: p.y - 4.5, width: 9, height: 9)), with: .color(markerColor))
                context.fill(Path(ellipseIn: CGRect(x: p.x - 2, y: p.y - 2, width: 4, height: 4)), with: .color(Color(nsColor: .windowBackgroundColor)))
            }
        }
        .accessibilityElement()
        .accessibilityLabel("Frequency response chart")
    }

    static func frequencyLabel(_ hz: Double) -> String {
        if hz >= 1000 {
            let k = hz / 1000
            return k == k.rounded() ? "\(Int(k))k" : String(format: "%.1fk", k)
        }
        return "\(Int(hz.rounded()))"
    }
}
