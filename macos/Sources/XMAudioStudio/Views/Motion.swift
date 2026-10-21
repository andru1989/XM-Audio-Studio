import SwiftUI

/// Motion vocabulary for the app, kept in one place so timings don't drift between
/// controls.
///
/// Everything here is short and springy rather than long and eased: these are direct
/// manipulations of hardware, and a control that takes half a second to acknowledge a
/// click feels broken rather than smooth.
enum Motion {
    /// Presses and selection changes. Quick, with just enough bounce to read as
    /// physical.
    static let press = Animation.spring(response: 0.28, dampingFraction: 0.62)
    /// Selection moving between options, and content appearing or collapsing.
    static let transition = Animation.spring(response: 0.38, dampingFraction: 0.78)
    /// Hover, which should be felt more than seen.
    static let hover = Animation.easeOut(duration: 0.14)
    /// Switching between disconnected, connecting and the dashboard.
    static let sceneChange = Animation.spring(response: 0.45, dampingFraction: 0.85)
}

/// Springy press feedback for any button.
///
/// Uses `ButtonStyle`'s own `isPressed`, so it needs no view-local state and works on
/// every control uniformly, including the ones built out of plain shapes.
struct PressableButtonStyle: ButtonStyle {
    /// How far the control sinks. Circles can take more than wide chips before the
    /// movement starts to look like a glitch.
    var scale: CGFloat = 0.94

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? scale : 1.0)
            .animation(Motion.press, value: configuration.isPressed)
    }
}

