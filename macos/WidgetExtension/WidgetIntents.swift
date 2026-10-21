import AppIntents
import AppKit
import WidgetKit
import WidgetBridgeKit

// The App Intents types live in the extension's own module: the metadata processor only
// sees AppEnum/AppIntent declarations compiled into the target that ships them.

enum NoiseModeOption: String, AppEnum {
    case noiseCancelling
    case ambientSound
    case off

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Noise control"
    static let caseDisplayRepresentations: [NoiseModeOption: DisplayRepresentation] = [
        .noiseCancelling: "Noise Canceling",
        .ambientSound: "Ambient Sound",
        .off: "Off",
    ]

    init(_ mode: NoiseMode) {
        switch mode {
        case .noiseCancelling: self = .noiseCancelling
        case .ambientSound: self = .ambientSound
        case .off: self = .off
        }
    }

    var mode: NoiseMode {
        switch self {
        case .noiseCancelling: return .noiseCancelling
        case .ambientSound: return .ambientSound
        case .off: return .off
        }
    }
}

enum EqPresetOption: String, AppEnum {
    case off, heavy, clear, hard, soft, custom

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Equalizer preset"
    static let caseDisplayRepresentations: [EqPresetOption: DisplayRepresentation] = [
        .off: "Off",
        .heavy: "Heavy",
        .clear: "Clear",
        .hard: "Hard",
        .soft: "Soft",
        .custom: "Custom",
    ]

    init(_ preset: EqPresetValue) {
        self = EqPresetOption(rawValue: preset.rawValue) ?? .off
    }

    var preset: EqPresetValue { EqPresetValue(rawValue: rawValue) ?? .off }
}

struct SetNoiseModeIntent: AppIntent {
    static let title: LocalizedStringResource = "Set Noise Control"
    static let description = IntentDescription("Switches the headphones between noise canceling, ambient sound and off.")
    static let isDiscoverable = false

    @Parameter(title: "Mode")
    var mode: NoiseModeOption

    init() {}

    init(mode: NoiseModeOption) {
        self.mode = mode
    }

    func perform() async throws -> some IntentResult {
        await WidgetCommandSender.send(.setNoiseMode(mode.mode))
        return .result()
    }
}

struct ApplyEqPresetIntent: AppIntent {
    static let title: LocalizedStringResource = "Apply Equalizer Preset"
    static let description = IntentDescription("Applies one of the headphones' equalizer presets.")
    static let isDiscoverable = false

    @Parameter(title: "Preset")
    var preset: EqPresetOption

    init() {}

    init(preset: EqPresetOption) {
        self.preset = preset
    }

    func perform() async throws -> some IntentResult {
        await WidgetCommandSender.send(.applyEqPreset(preset.preset))
        return .result()
    }
}

/// Runs in the (sandboxed) widget extension when a button is clicked.
enum WidgetCommandSender {
    static func send(_ action: WidgetAction) async {
        // The extension is entitled to the group by its sandbox; no runtime check needed.
        guard let store = WidgetStore.appGroup(requireEntitlement: false) else { return }
        // 1. Leave the command in the mailbox and wake the app (Darwin notification).
        try? store.post(WidgetCommand(action: action))
        // 2. Nothing is listening if the app isn't running: launch it. It takes pending
        //    commands at startup, so the tap still applies instead of silently failing.
        if !AppLauncher.isAppRunning {
            await AppLauncher.launchApp()
        }
        // 3. Redraw now so the button shows the tap as pending until the app reports back.
        WidgetCenter.shared.reloadAllTimelines()
    }
}

enum AppLauncher {
    static var isAppRunning: Bool {
        !NSRunningApplication.runningApplications(withBundleIdentifier: AppGroup.appBundleIdentifier).isEmpty
    }

    /// `…/XM Audio Studio.app/Contents/PlugIns/XMAudioStudioWidget.appex` → the app.
    static var containingAppURL: URL? {
        let appex = Bundle.main.bundleURL
        guard appex.pathExtension == "appex" else { return nil }
        let app = appex.deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        return app.pathExtension == "app" ? app : nil
    }

    static func launchApp() async {
        guard let url = containingAppURL
                ?? NSWorkspace.shared.urlForApplication(withBundleIdentifier: AppGroup.appBundleIdentifier) else { return }
        let configuration = NSWorkspace.OpenConfiguration()
        configuration.activates = false
        _ = try? await NSWorkspace.shared.openApplication(at: url, configuration: configuration)
    }
}
