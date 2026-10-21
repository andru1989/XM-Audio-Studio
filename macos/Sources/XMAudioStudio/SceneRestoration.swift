import SwiftUI

extension Scene {
    /// Windows open at their default size instead of being restored by macOS at the size
    /// they had last time (which may predate the current layout). Each window still
    /// remembers a size the user sets, through its frame autosave.
    func withoutWindowRestoration() -> some Scene {
        restorationBehavior(.disabled)
    }
}
