import SwiftUI
import AppKit
import SonyHeadphonesKit
import WidgetBridgeKit

@main
struct XMAudioStudioApp: App {
    @StateObject private var controller: HeadphonesController
    @StateObject private var settings: AppSettings
    @StateObject private var studio: EqStudioModel
    @StateObject private var widgetBridge: WidgetBridge
    /// Local DSP ("Advanced EQ"); off at every launch, only shown in the studio window.
    @StateObject private var dsp = DSPStudioModel()

    init() {
        ProbeMode.runIfRequested()
        AppSettings.migrateWindowFramesIfNeeded()
        let controller = AppSettings.launchesInDemoMode ? HeadphonesController.demo() : HeadphonesController()
        let studio = EqStudioModel(controller: controller)
        _controller = StateObject(wrappedValue: controller)
        _settings = StateObject(wrappedValue: AppSettings(controller: controller))
        _studio = StateObject(wrappedValue: studio)

        // WidgetKit widgets (Xcode build only; inert without the App Group entitlement).
        let bridge = WidgetBridge(
            controller: controller,
            store: ProbeMode.active ? nil : WidgetStore.appGroup(),
            isDemo: AppSettings.launchesInDemoMode,
            profileName: { [weak studio] in studio?.activeProfile?.name }
        )
        _widgetBridge = StateObject(wrappedValue: bridge)
        // After init returns: commands left by a widget may connect, and that shouldn't
        // happen while the app is still being constructed.
        Task { @MainActor in bridge.start() }
    }

    var body: some Scene {
        WindowGroup(id: "main") {
            ContentView()
                .environmentObject(controller)
                .environmentObject(settings)
                .environmentObject(studio)
                .frame(minWidth: 380, idealWidth: 960, minHeight: 480, idealHeight: 820)
                .background(ControlSurfaceObserver(controller: controller, hideInitialWindow: settings.shouldHideInitialWindow))
                .onAppear {
                    // The stored preference has to be pushed onto NSApp once the app is
                    // actually up; the bundle always launches as a regular app so that
                    // this window can exist at all.
                    if ProbeMode.active {
                        // Keep the protocol probe out of the Dock and the app switcher.
                        NSApp.setActivationPolicy(.accessory)
                    } else {
                        settings.apply()
                    }
                }
        }
        // Sized so the two-column dashboard fits without scrolling on a 13-14" MacBook
        // (about 900-1000 pt of usable height); smaller windows scroll as a fallback.
        .defaultSize(width: 960, height: 820)
        .windowResizability(.contentMinSize)
        .withoutWindowRestoration()
        // Without restoration, say explicitly that the dashboard opens at launch
        // (menu-bar-only mode hides it again right away, see AppSettings).
        .defaultLaunchBehavior(.presented)
        .commands {
            CommandGroup(replacing: .newItem) {}
        }

        // Menu bar controls: always one click away, even with the main window closed.
        // Icon-only label: the title+systemImage form reserves layout space for the
        // (invisible) title text, leaving an odd gap next to the icon.
        MenuBarExtra {
            CompactControlsView()
                .environmentObject(controller)
                .environmentObject(settings)
                .environmentObject(studio)
                .background(ControlSurfaceObserver(controller: controller))
        } label: {
            MenuBarIcon()
        }
        .menuBarExtraStyle(.window)

        // Native Settings window (⌘,) for app preferences.
        Settings {
            SettingsView()
                .environmentObject(controller)
                .environmentObject(settings)
        }

        // The equalizer studio: profiles, AutoEQ, A/B and the response chart.
        Window("Equalizer Studio", id: "equalizer-studio") {
            EqualizerStudioView()
                .environmentObject(controller)
                .environmentObject(settings)
                .environmentObject(studio)
                .environmentObject(dsp)
        }
        .defaultSize(width: 1000, height: 760)
        .withoutWindowRestoration()
        .keyboardShortcut("e", modifiers: [.command, .shift])

        // Floating desktop widget, opened from the main window or the menu bar panel.
        Window("XM Audio Studio Widget", id: "desktop-widget") {
            DesktopWidgetView()
                .environmentObject(controller)
                .environmentObject(settings)
                .environmentObject(studio)
                .background(ControlSurfaceObserver(controller: controller))
        }
        .windowResizability(.contentSize)
        .defaultPosition(.topTrailing)
    }
}
