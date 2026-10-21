import Foundation
import Security

/// The App Group shared by the app and its WidgetKit extension.
///
/// macOS-style ID: the Team ID prefix (`P5M226DH69.`) is what authorises the group for a
/// binary signed by that team without a provisioning profile, so the same ID works for the
/// non-sandboxed app and the sandboxed extension when both are signed with the team's
/// Apple Development identity. An iOS-style `group.` ID would need a provisioning profile
/// to be authorised (see docs/macos/WIDGETS.md). Keep in sync with
/// `Config/XMAudioStudio.entitlements` and `Config/XMAudioStudioWidget.entitlements`.
public enum AppGroup {
    public static let teamID = "P5M226DH69"
    public static let identifier = "\(teamID).group.io.github.andru1989.xmaudiostudio"

    /// Posted (Darwin notify center) by the widget after it drops a command in the mailbox.
    public static let commandNotification = "\(identifier).command"

    /// Bundle identifier of the containing app (the widget launches it when it isn't running).
    public static let appBundleIdentifier = "io.github.andru1989.xmaudiostudio"

    /// The group container, or nil when this process can't use it.
    ///
    /// On macOS `containerURL(forSecurityApplicationGroupIdentifier:)` returns a path even
    /// for a group the process isn't entitled to, and touching another group's container
    /// from an unentitled app makes macOS 15+ ask the user for permission. So the app
    /// checks its own signature first (`requireEntitlement`): a `swift build` /
    /// `build_app.sh` binary without the entitlement simply runs without the widget bridge.
    public static func containerURL(requireEntitlement: Bool = true) -> URL? {
        if requireEntitlement && !processHasEntitlement() { return nil }
        return FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: identifier)
    }

    /// Whether the running binary's code signature claims this App Group.
    public static func processHasEntitlement() -> Bool {
        guard let task = SecTaskCreateFromSelf(nil) else { return false }
        let value = SecTaskCopyValueForEntitlement(task, "com.apple.security.application-groups" as CFString, nil)
        return (value as? [String])?.contains(identifier) ?? false
    }
}
