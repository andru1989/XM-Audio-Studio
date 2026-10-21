import SwiftUI

/// The app's flat palette (`Surface` in Views/Theme.swift and `StudioColor` in
/// Studio/ResponseChart.swift, both from shared/design-tokens/tokens.json), resolved
/// through the widget's colour scheme: WidgetKit archives the view, so dynamic NSColors
/// are not a reliable way to follow the desktop's appearance here.
struct WidgetPalette {
    let scheme: ColorScheme

    private func pick(_ light: UInt32, _ dark: UInt32) -> Color {
        let v = scheme == .dark ? dark : light
        return Color(.sRGB, red: Double((v >> 16) & 0xff) / 255, green: Double((v >> 8) & 0xff) / 255,
                     blue: Double(v & 0xff) / 255, opacity: 1)
    }

    /// Surface.card
    var background: Color { pick(0xFFFFFF, 0x161B22) }
    /// Surface.well
    var well: Color { pick(0xF0F2F5, 0x1E242D) }
    /// Surface.wellEdge
    var wellEdge: Color { pick(0xE3E7EC, 0x2A313B) }
    /// StudioColor.warning
    var warning: Color { pick(0xB7791F, 0xF2B66D) }
    /// Live indicator.
    var live: Color { pick(0x1F9D55, 0x5DD39E) }
    /// Selection follows the system accent, as in the app (`Color.brand`).
    var accent: Color { .accentColor }
}

struct WidgetBackground: View {
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        WidgetPalette(scheme: scheme).background
    }
}
