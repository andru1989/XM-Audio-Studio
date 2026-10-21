import SwiftUI
import AppKit
import Combine
import ServiceManagement
import SonyHeadphonesKit

/// App-level preferences, as opposed to headphone settings.
///
/// The menu-bar-only mode is applied at runtime rather than through `LSUIElement`
/// in the bundle: an `LSUIElement` app can never show a Dock icon or open a window
/// on launch, so the choice has to be a switchable activation policy instead of a
/// build-time fact.
@MainActor
final class AppSettings: ObservableObject {
    private static let menuBarOnlyKey = "menuBarOnly"
    private static let releaseWhenIdleKey = "releaseHeadphonesWhenIdle"
    private static let demoModeKey = "demoMode"

    /// Demo mode is fixed for the life of the process: the controller is built with the
    /// byte-level XM6 simulator instead of IOBluetooth. `--demo` forces it for one launch.
    static var launchesInDemoMode: Bool {
        CommandLine.arguments.contains("--demo") || UserDefaults.standard.bool(forKey: demoModeKey)
    }

    let isDemo = AppSettings.launchesInDemoMode

    /// Takes effect on the next launch; see `relaunch()`.
    @Published var demoOnNextLaunch: Bool {
        didSet { UserDefaults.standard.set(demoOnNextLaunch, forKey: Self.demoModeKey) }
    }

    /// Registered with `SMAppService` (idea ported from yelloduxx/xm6-control). Requires the
    /// app to run from a signed .app bundle; the toggle reflects the system's real status.
    @Published var openAtLogin: Bool = SMAppService.mainApp.status == .enabled {
        didSet {
            guard !updatingLoginItem, oldValue != openAtLogin else { return }
            do {
                if openAtLogin { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
                loginItemError = nil
            } catch {
                loginItemError = error.localizedDescription
            }
            updatingLoginItem = true
            openAtLogin = SMAppService.mainApp.status == .enabled
            updatingLoginItem = false
        }
    }
    @Published private(set) var loginItemError: String?
    private var updatingLoginItem = false
    private let controller: HeadphonesController
    private var configuredInitialWindow = false
    private var terminationObserver: AnyCancellable?

    @Published var releaseWhenIdle: Bool {
        didSet {
            guard oldValue != releaseWhenIdle else { return }
            UserDefaults.standard.set(releaseWhenIdle, forKey: Self.releaseWhenIdleKey)
            controller.releaseWhenIdle = releaseWhenIdle
        }
    }

    /// `true` runs as a menu bar accessory: no Dock icon, no app-switcher entry, the
    /// menu bar panel is the whole interface. `false` is a normal Mac app.
    @Published var menuBarOnly: Bool {
        didSet {
            guard oldValue != menuBarOnly else { return }
            UserDefaults.standard.set(menuBarOnly, forKey: Self.menuBarOnlyKey)
            apply(openWindowIfNeeded: true)
        }
    }

    /// Bumped when the default window sizes change, so frames saved for an older layout
    /// (which no longer fit their content) are dropped once and the new defaults apply.
    private static let layoutVersion = 5
    private static let layoutVersionKey = "windowLayoutVersion"

    /// Must run before any window exists (called from `XMAudioStudioApp.init`).
    static func migrateWindowFramesIfNeeded() {
        let defaults = UserDefaults.standard
        // Window state restoration would reopen windows at whatever size they had, even
        // a size the current layout does not fit. Each window still remembers the size
        // the user gives it through its own frame autosave.
        defaults.set(false, forKey: "NSQuitAlwaysKeepsWindows")
        guard defaults.integer(forKey: layoutVersionKey) < layoutVersion else { return }
        for key in defaults.dictionaryRepresentation().keys where key.hasPrefix("NSWindow Frame ") {
            defaults.removeObject(forKey: key)
        }
        // Window frames from older builds also live in the system's saved state.
        if let bundleID = Bundle.main.bundleIdentifier {
            let savedState = FileManager.default.homeDirectoryForCurrentUser
                .appendingPathComponent("Library/Saved Application State/\(bundleID).savedState")
            try? FileManager.default.removeItem(at: savedState)
        }
        defaults.set(layoutVersion, forKey: layoutVersionKey)
    }

    init(controller: HeadphonesController) {
        self.controller = controller
        releaseWhenIdle = UserDefaults.standard.object(forKey: Self.releaseWhenIdleKey) as? Bool ?? true
        menuBarOnly = UserDefaults.standard.bool(forKey: Self.menuBarOnlyKey)
        demoOnNextLaunch = UserDefaults.standard.bool(forKey: Self.demoModeKey)
        controller.releaseWhenIdle = releaseWhenIdle
        terminationObserver = NotificationCenter.default.publisher(for: NSApplication.willTerminateNotification)
            .sink { [weak controller] _ in controller?.disconnect() }
    }

    /// Pushes the current preference onto `NSApp`. Call once at launch, and it runs
    /// again automatically whenever the preference changes.
    ///
    /// - Parameter openWindowIfNeeded: when leaving menu-bar-only mode, bring the app
    ///   forward so the change is visible; there is otherwise no feedback that the
    ///   Dock icon came back.
    func apply(openWindowIfNeeded: Bool = false) {
        NSApp.setActivationPolicy(menuBarOnly ? .accessory : .regular)

        if !menuBarOnly && openWindowIfNeeded {
            NSApp.activate(ignoringOtherApps: true)
        }
    }

    /// Starts a fresh copy of the app and quits this one (used to switch demo mode).
    func relaunch() {
        let configuration = NSWorkspace.OpenConfiguration()
        configuration.createsNewApplicationInstance = true
        NSWorkspace.shared.openApplication(at: Bundle.main.bundleURL, configuration: configuration) { _, _ in
            DispatchQueue.main.async { NSApp.terminate(nil) }
        }
    }

    func shouldHideInitialWindow() -> Bool {
        guard !configuredInitialWindow else { return false }
        configuredInitialWindow = true
        return releaseWhenIdle && menuBarOnly
    }
}
