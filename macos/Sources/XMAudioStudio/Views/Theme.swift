import SwiftUI
import AppKit

/// Surface tones that adapt to the system appearance.
///
/// These were hardcoded as black and white overlays, which worked in dark mode and
/// broke in light: a well filled with 16% black sat on a light card as a heavy grey
/// blob, so unselected controls looked as prominent as the selected one. Every value
/// here resolves per appearance instead.
enum Surface {
    // Flat palette from shared/design-tokens/tokens.json: one uniform window colour,
    // cards a step lighter (or darker in dark mode) with a hairline edge. No gradients,
    // no shadows: hierarchy comes from spacing, type and a single accent colour.

    /// Window background (tokens: color.*.background).
    static let window = hex(light: 0xF6F7F9, dark: 0x0E1116)
    /// Card fill (tokens: color.*.surface).
    static let card = hex(light: 0xFFFFFF, dark: 0x161B22)
    /// Card edge (tokens: color.*.gridLine).
    static let cardEdge = hex(light: 0xE3E7EC, dark: 0x262D36)
    /// Unselected control fill (tokens: color.*.surfaceElevated).
    static let well = hex(light: 0xF0F2F5, dark: 0x1E242D)
    /// Unselected control edge.
    static let wellEdge = hex(light: 0xE3E7EC, dark: 0x2A313B)

    /// Hairline between rows.
    static let separator = Color(nsColor: .separatorColor)

    private static func hex(light: UInt32, dark: UInt32) -> Color {
        Color(nsColor: NSColor(name: nil) { appearance in
            let v = appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? dark : light
            return NSColor(srgbRed: CGFloat((v >> 16) & 0xff) / 255, green: CGFloat((v >> 8) & 0xff) / 255,
                           blue: CGFloat(v & 0xff) / 255, alpha: 1)
        })
    }
}
