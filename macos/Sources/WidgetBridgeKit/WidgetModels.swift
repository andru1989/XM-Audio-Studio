import Foundation

/// Ambient sound control as the widget shows it. Mirrors `AmbientSoundMode` in
/// SonyHeadphonesKit (kept separate so the extension doesn't link the protocol code).
public enum NoiseMode: String, Codable, CaseIterable, Sendable {
    case noiseCancelling
    case ambientSound
    case off
}

/// Mirrors `ListeningMode` in SonyHeadphonesKit.
public enum ListeningModeValue: String, Codable, CaseIterable, Sendable {
    case standard
    case backgroundMusic
    case cinema
}

/// The XM6 firmware presets. `code` mirrors `EqualizerPreset.rawValue` (checked by a test).
public enum EqPresetValue: String, Codable, CaseIterable, Sendable {
    case off, heavy, clear, hard, soft, custom

    public var code: Int {
        switch self {
        case .off: return 0x00
        case .heavy: return 0x30
        case .clear: return 0x31
        case .hard: return 0x32
        case .soft: return 0x33
        case .custom: return 0xa0
        }
    }

    public init?(code: Int) {
        guard let match = Self.allCases.first(where: { $0.code == code }) else { return nil }
        self = match
    }
}

/// Something a widget button asks the app to do.
public enum WidgetAction: Equatable, Hashable, Sendable {
    case setNoiseMode(NoiseMode)
    case applyEqPreset(EqPresetValue)

    /// Commands of the same kind supersede each other (only the latest tap matters).
    public enum Kind: String, Sendable { case noise, equalizer }

    public var kind: Kind {
        switch self {
        case .setNoiseMode: return .noise
        case .applyEqPreset: return .equalizer
        }
    }
}

extension WidgetAction: Codable {
    private enum CodingKeys: String, CodingKey { case type, value }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        switch try container.decode(String.self, forKey: .type) {
        case "setNoiseMode":
            self = .setNoiseMode(try container.decode(NoiseMode.self, forKey: .value))
        case "applyEqPreset":
            self = .applyEqPreset(try container.decode(EqPresetValue.self, forKey: .value))
        case let other:
            throw DecodingError.dataCorruptedError(forKey: .type, in: container, debugDescription: "Unknown action \(other)")
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        switch self {
        case .setNoiseMode(let mode):
            try container.encode("setNoiseMode", forKey: .type)
            try container.encode(mode, forKey: .value)
        case .applyEqPreset(let preset):
            try container.encode("applyEqPreset", forKey: .type)
            try container.encode(preset, forKey: .value)
        }
    }
}

/// One entry in the mailbox: written by the widget, consumed (deleted) by the app.
public struct WidgetCommand: Codable, Equatable, Identifiable, Sendable {
    public var id: UUID
    public var issuedAt: Date
    public var action: WidgetAction

    public init(id: UUID = UUID(), issuedAt: Date = Date(), action: WidgetAction) {
        self.id = id
        self.issuedAt = issuedAt
        self.action = action
    }
}

/// What happened to the last widget command, so the widget can say why nothing changed.
public struct CommandOutcome: Codable, Equatable, Sendable {
    public enum Result: String, Codable, Sendable {
        /// Handed to the headphones controller (sent, or queued until the channel is open).
        case sent
        /// The headphones already were in the requested state.
        case alreadySet
        /// The headphones couldn't be reached (off, out of range, not paired).
        case unreachable
        /// Another device (e.g. Sound Connect on a phone) holds the control channel.
        case anotherDevice
        /// Connected, but the headphones never reported the state the command builds on.
        case stateUnavailable
        /// The firmware ignores the EQ while Background Music or Cinema is on.
        case blockedByListeningMode
        /// The connected model doesn't have this preset (the widget's presets are XM6's).
        case unsupported
        /// The command sat in the mailbox too long (app wasn't running) and was dropped.
        case expired
        /// No answer within the wait limit.
        case timedOut

        public var isFailure: Bool { self != .sent && self != .alreadySet }
    }

    public var commandID: UUID
    public var action: WidgetAction
    public var result: Result
    public var at: Date

    public init(commandID: UUID, action: WidgetAction, result: Result, at: Date = Date()) {
        self.commandID = commandID
        self.action = action
        self.result = result
        self.at = at
    }
}

/// The app's view of the headphones, published for the widget.
///
/// Values are "last known": the app keeps them when the control channel is released
/// (the headphones only report state while connected) and `link` says whether they
/// are live.
public struct WidgetSnapshot: Codable, Equatable, Sendable {
    public enum Link: String, Codable, Sendable {
        /// Connected and the state has been read: values are current.
        case live
        /// Opening the control channel.
        case connecting
        /// The app released the channel while idle; values are the last ones read.
        case released
        /// Not connected (failed, another device took over, or never connected).
        case offline
        /// The app quit; values are the last ones read.
        case appClosed
    }

    public static let currentVersion = 1

    public var version: Int
    public var link: Link
    /// `HeadphoneModel.rawValue` (e.g. "wh-1000xm6"), when the name identifies the model.
    public var modelID: String?
    public var deviceName: String?
    public var batteryLevel: Int?
    public var isCharging: Bool?
    public var noiseMode: NoiseMode?
    public var ambientLevel: Int?
    public var listeningMode: ListeningModeValue?
    /// Raw preset code reported by the headphones (may be a Sound Connect user preset).
    public var eqPresetCode: Int?
    /// The app's profile matching the current curve, if any.
    public var eqProfileName: String?
    /// Values come from the simulator (demo mode), not real headphones.
    public var isDemo: Bool
    /// When the values were last confirmed by the headphones.
    public var observedAt: Date?
    /// When the app wrote this snapshot.
    public var updatedAt: Date
    /// Widget commands the app accepted and is still waiting to apply.
    public var pendingActions: [WidgetAction]
    public var lastOutcome: CommandOutcome?

    public init(
        link: Link = .offline,
        modelID: String? = nil,
        deviceName: String? = nil,
        batteryLevel: Int? = nil,
        isCharging: Bool? = nil,
        noiseMode: NoiseMode? = nil,
        ambientLevel: Int? = nil,
        listeningMode: ListeningModeValue? = nil,
        eqPresetCode: Int? = nil,
        eqProfileName: String? = nil,
        isDemo: Bool = false,
        observedAt: Date? = nil,
        updatedAt: Date = Date(),
        pendingActions: [WidgetAction] = [],
        lastOutcome: CommandOutcome? = nil
    ) {
        self.version = Self.currentVersion
        self.link = link
        self.modelID = modelID
        self.deviceName = deviceName
        self.batteryLevel = batteryLevel
        self.isCharging = isCharging
        self.noiseMode = noiseMode
        self.ambientLevel = ambientLevel
        self.listeningMode = listeningMode
        self.eqPresetCode = eqPresetCode
        self.eqProfileName = eqProfileName
        self.isDemo = isDemo
        self.observedAt = observedAt
        self.updatedAt = updatedAt
        self.pendingActions = pendingActions
        self.lastOutcome = lastOutcome
    }

    public var isLive: Bool { link == .live }

    /// True once any headphone state has ever been recorded.
    public var hasDeviceState: Bool {
        batteryLevel != nil || noiseMode != nil || eqPresetCode != nil
    }

    public var eqPreset: EqPresetValue? { eqPresetCode.flatMap(EqPresetValue.init(code:)) }

    /// Equality that ignores the timestamps, used to skip writes (and widget reloads)
    /// that would change nothing the widget shows.
    public func hasSameContent(as other: WidgetSnapshot) -> Bool {
        var a = self
        var b = other
        a.updatedAt = .distantPast
        b.updatedAt = .distantPast
        a.observedAt = nil
        b.observedAt = nil
        return a == b
    }
}
