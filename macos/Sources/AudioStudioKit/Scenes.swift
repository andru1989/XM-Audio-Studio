import Foundation
import SonyHeadphonesKit

// "Escenas": a profile (format version 2) may carry the headphone settings next to its EQ.
// Same rules as android/core/.../presets/Scenes.kt; both are checked against
// shared/preset-schema (examples and scene-vectors.json). See docs/architecture/SCENES.md.
// The type is not called `Scene` so it never collides with SwiftUI's `Scene` in the app.

public enum SceneNoiseMode: String, Codable, Sendable, CaseIterable {
    case noiseCancelling, ambientSound, off

    public init(_ mode: AmbientSoundMode) {
        switch mode {
        case .noiseCancelling: self = .noiseCancelling
        case .ambientSound: self = .ambientSound
        case .off: self = .off
        }
    }

    public var mode: AmbientSoundMode {
        switch self {
        case .noiseCancelling: return .noiseCancelling
        case .ambientSound: return .ambientSound
        case .off: return .off
        }
    }
}

public enum SceneListeningMode: String, Codable, Sendable, CaseIterable {
    case standard, backgroundMusic, cinema

    public init(_ mode: ListeningMode) {
        switch mode {
        case .standard: self = .standard
        case .backgroundMusic: self = .backgroundMusic
        case .cinema: self = .cinema
        }
    }

    public var mode: ListeningMode {
        switch self {
        case .standard: return .standard
        case .backgroundMusic: return .backgroundMusic
        case .cinema: return .cinema
        }
    }
}

public enum SceneRoomSize: String, Codable, Sendable, CaseIterable {
    case small, middle, large

    public init(_ size: BGMRoomSize) {
        switch size {
        case .small: self = .small
        case .middle: self = .middle
        case .large: self = .large
        }
    }

    public var size: BGMRoomSize {
        switch self {
        case .small: return .small
        case .middle: return .middle
        case .large: return .large
        }
    }
}

public enum SceneAutoPowerOff: String, Codable, Sendable, CaseIterable {
    case whenTakenOff, off

    public init(_ mode: AutomaticPowerOffMode) {
        switch mode {
        case .whenTakenOff: self = .whenTakenOff
        case .off: self = .off
        }
    }

    public var mode: AutomaticPowerOffMode {
        switch self {
        case .whenTakenOff: return .whenTakenOff
        case .off: return .off
        }
    }
}

/// `level` and `focusOnVoice` are optional; applying keeps what the headphones have for a missing one.
public struct SceneNoiseControl: Codable, Equatable, Sendable {
    public var mode: SceneNoiseMode
    public var level: Int?
    public var focusOnVoice: Bool?

    public init(mode: SceneNoiseMode, level: Int? = nil, focusOnVoice: Bool? = nil) {
        self.mode = mode
        self.level = level
        self.focusOnVoice = focusOnVoice
    }
}

/// Headphone settings beyond the EQ. Every field is optional: only what the headphones reported is captured.
public struct HeadphoneScene: Codable, Equatable, Sendable {
    public var noiseControl: SceneNoiseControl?
    public var listeningMode: SceneListeningMode?
    public var bgmRoomSize: SceneRoomSize?
    public var speakToChat: Bool?
    public var pauseWhenTakenOff: Bool?
    public var autoPowerOff: SceneAutoPowerOff?

    public init(noiseControl: SceneNoiseControl? = nil, listeningMode: SceneListeningMode? = nil, bgmRoomSize: SceneRoomSize? = nil,
                speakToChat: Bool? = nil, pauseWhenTakenOff: Bool? = nil, autoPowerOff: SceneAutoPowerOff? = nil) {
        self.noiseControl = noiseControl
        self.listeningMode = listeningMode
        self.bgmRoomSize = bgmRoomSize
        self.speakToChat = speakToChat
        self.pauseWhenTakenOff = pauseWhenTakenOff
        self.autoPowerOff = autoPowerOff
    }

    public var isEmpty: Bool {
        noiseControl == nil && listeningMode == nil && bgmRoomSize == nil
            && speakToChat == nil && pauseWhenTakenOff == nil && autoPowerOff == nil
    }
}

/// What the headphones report, as far as scenes care. The app fills it from `HeadphonesController`'s
/// published state; nil means "not reported".
public struct ReportedHeadphoneState: Equatable, Sendable {
    public var ambientSound: AmbientSoundState?
    public var listeningMode: ListeningMode?
    public var bgmRoomSize: BGMRoomSize?
    public var speakToChatEnabled: Bool?
    public var pauseWhenTakenOff: Bool?
    public var automaticPowerOff: AutomaticPowerOffMode?
    public var equalizer: EqualizerState?

    public init(ambientSound: AmbientSoundState? = nil, listeningMode: ListeningMode? = nil, bgmRoomSize: BGMRoomSize? = nil,
                speakToChatEnabled: Bool? = nil, pauseWhenTakenOff: Bool? = nil, automaticPowerOff: AutomaticPowerOffMode? = nil,
                equalizer: EqualizerState? = nil) {
        self.ambientSound = ambientSound
        self.listeningMode = listeningMode
        self.bgmRoomSize = bgmRoomSize
        self.speakToChatEnabled = speakToChatEnabled
        self.pauseWhenTakenOff = pauseWhenTakenOff
        self.automaticPowerOff = automaticPowerOff
        self.equalizer = equalizer
    }
}

/// One write that applying a scene makes. Mirrors the Kotlin `DeviceCommand` intents; the app maps each
/// to a `HeadphonesController` setter.
public enum SceneIntent: Equatable, Sendable {
    case ambientSound(mode: AmbientSoundMode, focusOnVoice: Bool, level: Int)
    case bgmRoomSize(BGMRoomSize)
    case listeningMode(ListeningMode)
    case equalizerPreset(Int)
    /// `clearBass` is set only for models with a Clear Bass control (XM3-XM5).
    case equalizerBands([Int], clearBass: Int?)
    case speakToChat(Bool)
    case pauseWhenTakenOff(Bool)
    case automaticPowerOff(AutomaticPowerOffMode)
}

/// One setting a scene would change, for the confirmation shown before applying it.
public enum SceneChange: Equatable, Sendable {
    case noiseMode(from: AmbientSoundMode?, to: AmbientSoundMode)
    case ambientLevel(from: Int?, to: Int)
    case focusOnVoice(from: Bool?, to: Bool)
    case listeningMode(from: ListeningMode?, to: ListeningMode)
    case bgmRoomSize(from: BGMRoomSize?, to: BGMRoomSize)
    case speakToChat(from: Bool?, to: Bool)
    case pauseWhenTakenOff(from: Bool?, to: Bool)
    case automaticPowerOff(from: AutomaticPowerOffMode?, to: AutomaticPowerOffMode)

    /// The field name used by `shared/preset-schema/scene-vectors.json`.
    public var key: String {
        switch self {
        case .noiseMode: return "noiseMode"
        case .ambientLevel: return "ambientLevel"
        case .focusOnVoice: return "focusOnVoice"
        case .listeningMode: return "listeningMode"
        case .bgmRoomSize: return "bgmRoomSize"
        case .speakToChat: return "speakToChat"
        case .pauseWhenTakenOff: return "pauseWhenTakenOff"
        case .automaticPowerOff: return "autoPowerOff"
        }
    }
}

public enum HeadphoneScenes {
    /// Level written when neither the scene nor the headphones give a usable one (same as the controls).
    public static let fallbackAmbientLevel = 15

    /// The scene the headphones report now, limited to what `capabilities` supports. Nil when nothing usable was reported.
    public static func capture(_ state: ReportedHeadphoneState, capabilities: DeviceCapabilities) -> HeadphoneScene? {
        var scene = HeadphoneScene()
        if let a = state.ambientSound, capabilities.supports(.noiseCancelling),
           a.mode != .ambientSound || capabilities.supports(.ambientSound) {
            // MDR v1 reports 0 while noise cancelling is on: not a level that can be written back.
            let level = capabilities.supports(.ambientLevel) && capabilities.ambientLevelRange.contains(a.level) ? a.level : nil
            scene.noiseControl = SceneNoiseControl(mode: SceneNoiseMode(a.mode), level: level,
                                                   focusOnVoice: capabilities.supports(.focusOnVoice) ? a.focusOnVoice : nil)
        }
        if capabilities.supports(.listeningMode) {
            scene.listeningMode = state.listeningMode.map(SceneListeningMode.init)
            scene.bgmRoomSize = state.bgmRoomSize.map(SceneRoomSize.init)
        }
        if capabilities.supports(.speakToChat) { scene.speakToChat = state.speakToChatEnabled }
        if capabilities.supports(.wearDetection) { scene.pauseWhenTakenOff = state.pauseWhenTakenOff }
        if capabilities.supports(.autoPowerOff) { scene.autoPowerOff = state.automaticPowerOff.map(SceneAutoPowerOff.init) }
        return scene.isEmpty ? nil : scene
    }

    /// Nil when valid, otherwise the reason (used by `ProfileCodec.validate`). Same rules as Kotlin.
    public static func validate(_ scene: HeadphoneScene, capabilities: DeviceCapabilities) -> String? {
        let model = capabilities.model.displayName
        if scene.isEmpty { return "scene is empty" }
        if let nc = scene.noiseControl {
            if !capabilities.supports(.noiseCancelling) { return "scene.noiseControl: \(model) has no noise control" }
            if nc.mode == .ambientSound && !capabilities.supports(.ambientSound) {
                return "scene.noiseControl: \(model) has no ambient sound mode"
            }
            if let level = nc.level {
                if !capabilities.supports(.ambientLevel) { return "scene.noiseControl.level: \(model) has no ambient level" }
                let range = capabilities.ambientLevelRange
                if !range.contains(level) { return "scene.noiseControl.level must be within \(range.lowerBound)..\(range.upperBound)" }
            }
            if nc.focusOnVoice != nil && !capabilities.supports(.focusOnVoice) {
                return "scene.noiseControl.focusOnVoice: \(model) has no focus on voice"
            }
        }
        if scene.listeningMode != nil && !capabilities.supports(.listeningMode) { return "scene.listeningMode: \(model) has no listening mode" }
        if scene.bgmRoomSize != nil && !capabilities.supports(.listeningMode) { return "scene.bgmRoomSize: \(model) has no listening mode" }
        if scene.speakToChat != nil && !capabilities.supports(.speakToChat) { return "scene.speakToChat: \(model) has no speak-to-chat" }
        if scene.pauseWhenTakenOff != nil && !capabilities.supports(.wearDetection) {
            return "scene.pauseWhenTakenOff: \(model) has no wear detection"
        }
        if scene.autoPowerOff != nil && !capabilities.supports(.autoPowerOff) {
            return "scene.autoPowerOff: \(model) has no automatic power off"
        }
        return nil
    }

    /// The write that puts `setting` on the headphones. Clear Bass defaults to 0 where the model has it.
    public static func eqIntent(_ setting: DeviceEqSetting, capabilities: DeviceCapabilities) -> SceneIntent? {
        if let code = setting.presetCode { return .equalizerPreset(code) }
        guard let steps = setting.steps else { return nil }
        let neutralClearBass: Int? = capabilities.equalizer?.clearBassRange == nil ? nil : 0
        return .equalizerBands(steps, clearBass: setting.clearBass ?? neutralClearBass)
    }

    /// The writes that apply `scene` (and the profile's `eq`, if given), in the order they must be sent.
    ///
    /// - The BGM room goes before the listening mode, so the listening-mode write carries the new room
    ///   (the room byte travels with the BGM on/off flag).
    /// - The firmware turns the EQ off while Background Music or Cinema is on, so the EQ is written while
    ///   the mode is Standard: after switching to Standard, before switching away from it.
    ///
    /// `current` fills the parts of the noise-control write a scene left out.
    public static func intents(_ scene: HeadphoneScene, current: ReportedHeadphoneState, capabilities: DeviceCapabilities,
                               eq: SceneIntent? = nil) -> [SceneIntent] {
        var out: [SceneIntent] = []
        if let nc = scene.noiseControl, capabilities.supports(.noiseCancelling) {
            let reported = current.ambientSound
            let reportedLevel = reported.map(\.level).flatMap { capabilities.ambientLevelRange.contains($0) ? $0 : nil }
            out.append(.ambientSound(mode: nc.mode.mode,
                                     focusOnVoice: nc.focusOnVoice ?? reported?.focusOnVoice ?? false,
                                     level: nc.level ?? reportedLevel ?? fallbackAmbientLevel))
        }
        let listening = capabilities.supports(.listeningMode)
        if listening, let room = scene.bgmRoomSize { out.append(.bgmRoomSize(room.size)) }
        let mode = listening ? scene.listeningMode?.mode : nil
        if mode == .standard { out.append(.listeningMode(.standard)) }
        if let eq { out.append(eq) }
        if let mode, mode != .standard { out.append(.listeningMode(mode)) }
        if let stc = scene.speakToChat, capabilities.supports(.speakToChat) { out.append(.speakToChat(stc)) }
        if let pause = scene.pauseWhenTakenOff, capabilities.supports(.wearDetection) { out.append(.pauseWhenTakenOff(pause)) }
        if let apo = scene.autoPowerOff, capabilities.supports(.autoPowerOff) { out.append(.automaticPowerOff(apo.mode)) }
        return out
    }

    /// What applying `scene` changes compared with `current` (the EQ is listed by the caller). The ambient
    /// level and focus on voice are listed only when the result is ambient sound, and the room only when
    /// the result is Background Music: elsewhere they are written but not heard.
    public static func changes(_ scene: HeadphoneScene, current: ReportedHeadphoneState, capabilities: DeviceCapabilities) -> [SceneChange] {
        let reported = current.ambientSound
        let listening = capabilities.supports(.listeningMode) ? scene.listeningMode?.mode : nil
        let resultingListening = listening ?? current.listeningMode
        var out: [SceneChange] = []
        let all = intents(scene, current: current, capabilities: capabilities)
        for intent in all {
            if case let .ambientSound(mode, focusOnVoice, level) = intent {
                if mode != reported?.mode { out.append(.noiseMode(from: reported?.mode, to: mode)) }
                if mode == .ambientSound {
                    if scene.noiseControl?.level != nil && level != reported?.level {
                        out.append(.ambientLevel(from: reported?.level, to: level))
                    }
                    if scene.noiseControl?.focusOnVoice != nil && focusOnVoice != reported?.focusOnVoice {
                        out.append(.focusOnVoice(from: reported?.focusOnVoice, to: focusOnVoice))
                    }
                }
            }
        }
        if let listening, listening != current.listeningMode { out.append(.listeningMode(from: current.listeningMode, to: listening)) }
        for intent in all {
            switch intent {
            case .bgmRoomSize(let size):
                if resultingListening == .backgroundMusic && size != current.bgmRoomSize {
                    out.append(.bgmRoomSize(from: current.bgmRoomSize, to: size))
                }
            default: break
            }
        }
        for intent in all {
            switch intent {
            case .speakToChat(let on) where on != current.speakToChatEnabled:
                out.append(.speakToChat(from: current.speakToChatEnabled, to: on))
            case .pauseWhenTakenOff(let on) where on != current.pauseWhenTakenOff:
                out.append(.pauseWhenTakenOff(from: current.pauseWhenTakenOff, to: on))
            case .automaticPowerOff(let mode) where mode != current.automaticPowerOff:
                out.append(.automaticPowerOff(from: current.automaticPowerOff, to: mode))
            default: break
            }
        }
        return out
    }

    /// True when the scene leaves the headphones in a listening mode that turns their EQ off.
    public static func leavesEqInactive(_ scene: HeadphoneScene, current: ReportedHeadphoneState) -> Bool {
        guard let result = scene.listeningMode?.mode ?? current.listeningMode else { return false }
        return result != .standard
    }
}
