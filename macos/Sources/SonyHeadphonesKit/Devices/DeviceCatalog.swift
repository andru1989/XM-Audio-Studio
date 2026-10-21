import Foundation

/// Which MDR command table a model speaks (confirmed by the init reply length).
public enum ProtocolFamily: String, Sendable {
    case mdrV1 = "mdr-v1"
    case mdrV2 = "mdr-v2"
    case unknown
}

/// Sony 1000X over-ear generations. Only the XM6 is driven by this app today; the others are
/// catalogued so detection, capability gating and future adapters share one definition.
/// Mirrors `android/core/.../device/HeadphoneModel.kt`; both are checked against
/// `shared/protocol-spec/models.json`.
public enum HeadphoneModel: String, CaseIterable, Sendable, Identifiable {
    case mdr1000x = "mdr-1000x"
    case wh1000xm2 = "wh-1000xm2"
    case wh1000xm3 = "wh-1000xm3"
    case wh1000xm4 = "wh-1000xm4"
    case wh1000xm5 = "wh-1000xm5"
    case wh1000xm6 = "wh-1000xm6"

    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .mdr1000x: return "MDR-1000X"
        case .wh1000xm2: return "WH-1000XM2"
        case .wh1000xm3: return "WH-1000XM3"
        case .wh1000xm4: return "WH-1000XM4"
        case .wh1000xm5: return "WH-1000XM5"
        case .wh1000xm6: return "WH-1000XM6"
        }
    }

    public var namePatterns: [String] { [displayName] }

    public var protocolFamily: ProtocolFamily {
        switch self {
        case .mdr1000x: return .unknown
        case .wh1000xm2, .wh1000xm3, .wh1000xm4: return .mdrV1
        case .wh1000xm5, .wh1000xm6: return .mdrV2
        }
    }

    /// The protocol adapter id in `shared/protocol-spec/models.json` ("xm6", "xm5", "mdr-v1").
    /// SonyHeadphonesKit has pure codecs for each (`SonyCommands`, `Xm5Commands`, `MdrV1Commands`);
    /// Android drives all of them. Nil: no adapter anywhere (no public capture).
    public var adapterId: String? {
        switch self {
        case .wh1000xm6: return "xm6"
        case .wh1000xm5: return "xm5"
        case .wh1000xm4, .wh1000xm3, .wh1000xm2: return "mdr-v1"
        case .mdr1000x: return nil
        }
    }

    /// Whether the macOS `HeadphonesController` drives the model. Only the XM6 for now: the
    /// other adapters exist as codecs only on macOS. Models it does not drive are never sent a byte.
    public var hasAdapter: Bool { self == .wh1000xm6 }

    /// Best guess from the Bluetooth name (users can rename devices; the init reply confirms).
    public static func from(bluetoothName name: String?) -> HeadphoneModel? {
        guard let upper = name?.uppercased() else { return nil }
        return allCases
            .sorted { ($0.namePatterns.map(\.count).max() ?? 0) > ($1.namePatterns.map(\.count).max() ?? 0) }
            .first { model in model.namePatterns.contains { upper.contains($0) } }
    }
}

/// Protocol evidence for a feature on a given model (see docs/compatibility/FEATURE-STATUS.md).
public enum SupportLevel: String, Sendable {
    case verifiedHardware = "verified-hardware"
    case verifiedSimulation = "verified-simulation"
    case pendingHardware = "pending-hardware"
    case experimental
    case planned
    case unsupported

    public var isUsable: Bool {
        switch self {
        case .verifiedHardware, .verifiedSimulation, .pendingHardware, .experimental: return true
        case .planned, .unsupported: return false
        }
    }
}

public enum DeviceFeature: String, CaseIterable, Sendable {
    case battery, noiseCancelling, ambientSound, ambientLevel, focusOnVoice, listeningMode
    case speakToChat, wearDetection, autoPowerOff, eqPresets, eqCustomBands
    case multipointList, playbackSourceSwitch
}

public struct EqPresetSpec: Equatable, Sendable {
    public let code: UInt8
    public let name: String
}

/// The headphones' built-in graphic EQ. `frequenciesVerified == false` means the band
/// frequencies are client labels, not values reported by the device.
public struct EqualizerSpec: Equatable, Sendable {
    public let bandFrequenciesHz: [Double]
    public let frequenciesVerified: Bool
    public let minStep: Int
    public let maxStep: Int
    public let wireOffset: Int
    public let stepDb: Double
    public let clearBassRange: ClosedRange<Int>?
    public let presets: [EqPresetSpec]

    public var bandCount: Int { bandFrequenciesHz.count }
}

public struct DeviceCapabilities: Equatable, Sendable {
    public let model: HeadphoneModel
    public let features: [DeviceFeature: SupportLevel]
    public let equalizer: EqualizerSpec?
    public let ambientLevelRange: ClosedRange<Int>

    public func level(_ feature: DeviceFeature) -> SupportLevel { features[feature] ?? .unsupported }
    public func supports(_ feature: DeviceFeature) -> Bool { level(feature).isUsable }
}

public enum DeviceCatalog {
    private static let classicPresets: [EqPresetSpec] = [
        (0x00, "Off"), (0x10, "Bright"), (0x11, "Excited"), (0x12, "Mellow"), (0x13, "Relaxed"),
        (0x14, "Vocal"), (0x15, "Treble Boost"), (0x16, "Bass Boost"), (0x17, "Speech"),
        (0xa0, "Manual"), (0xa1, "Custom 1"), (0xa2, "Custom 2"),
    ].map { EqPresetSpec(code: $0.0, name: $0.1) }

    /// 5 bands + Clear Bass, ±10 steps (XM3-XM5 generation).
    private static let fiveBandEqualizer = EqualizerSpec(
        bandFrequenciesHz: [400, 1_000, 2_500, 6_300, 16_000],
        frequenciesVerified: false,
        minStep: -10, maxStep: 10, wireOffset: 10, stepDb: 1,
        clearBassRange: -10...10,
        presets: classicPresets
    )

    public static let xm6Equalizer = EqualizerSpec(
        bandFrequenciesHz: [31, 63, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000],
        frequenciesVerified: false,
        minStep: -6, maxStep: 6, wireOffset: 6, stepDb: 1,
        clearBassRange: nil,
        presets: [(0x00, "Off"), (0x30, "Heavy"), (0x31, "Clear"), (0x32, "Hard"), (0x33, "Soft"), (0xa0, "Custom")]
            .map { EqPresetSpec(code: $0.0, name: $0.1) }
    )

    /// The WH-1000XM3 reported these exact centres in its own EQ extended info
    /// (`5B 01 06 10 0000 01 0190 01 03E8 01 09C4 01 189C 01 3E80`, mos9527 fw 4.5.2 capture).
    private static let xm3Equalizer = EqualizerSpec(
        bandFrequenciesHz: fiveBandEqualizer.bandFrequenciesHz,
        frequenciesVerified: true,
        minStep: -10, maxStep: 10, wireOffset: 10, stepDb: 1,
        clearBassRange: -10...10,
        presets: classicPresets
    )

    private static func levels(_ level: SupportLevel, _ features: [DeviceFeature]) -> [DeviceFeature: SupportLevel] {
        Dictionary(uniqueKeysWithValues: features.map { ($0, level) })
    }

    private static func planned(_ features: [DeviceFeature]) -> [DeviceFeature: SupportLevel] {
        levels(.planned, features)
    }

    /// Merges per-level groups; a feature must appear once.
    private static func merge(_ groups: [DeviceFeature: SupportLevel]...) -> [DeviceFeature: SupportLevel] {
        groups.reduce(into: [:]) { result, group in result.merge(group) { _, _ in preconditionFailure("feature listed twice") } }
    }

    public static func capabilities(_ model: HeadphoneModel) -> DeviceCapabilities {
        switch model {
        case .wh1000xm6:
            return DeviceCapabilities(
                model: model,
                features: [
                    .battery: .verifiedHardware, .noiseCancelling: .verifiedHardware, .ambientSound: .verifiedHardware,
                    .ambientLevel: .verifiedHardware, .focusOnVoice: .pendingHardware, .listeningMode: .pendingHardware,
                    .speakToChat: .pendingHardware, .wearDetection: .verifiedHardware, .autoPowerOff: .verifiedHardware,
                    .eqPresets: .verifiedHardware, .eqCustomBands: .verifiedHardware, .multipointList: .verifiedHardware,
                    .playbackSourceSwitch: .pendingHardware,
                ],
                equalizer: xm6Equalizer,
                ambientLevelRange: 1...20
            )
        // Levels mirror android DeviceCatalog.kt; evidence in docs/compatibility/OTHER-MODELS.md.
        // None of these models has been tested on hardware by this project.
        case .wh1000xm5:
            return DeviceCapabilities(
                model: model,
                features: merge(
                    levels(.pendingHardware, [.battery, .noiseCancelling, .ambientSound, .ambientLevel, .focusOnVoice,
                                              .speakToChat, .eqCustomBands]),
                    levels(.experimental, [.eqPresets]),
                    planned([.wearDetection, .autoPowerOff])),
                equalizer: fiveBandEqualizer, ambientLevelRange: 1...20)
        case .wh1000xm4:
            return DeviceCapabilities(
                model: model,
                features: merge(
                    levels(.pendingHardware, [.battery, .noiseCancelling, .ambientSound, .ambientLevel, .focusOnVoice,
                                              .eqPresets, .eqCustomBands]),
                    levels(.experimental, [.speakToChat]),
                    planned([.wearDetection, .autoPowerOff])),
                equalizer: fiveBandEqualizer, ambientLevelRange: 1...20)
        case .wh1000xm3:
            return DeviceCapabilities(
                model: model,
                features: merge(
                    levels(.pendingHardware, [.battery, .noiseCancelling, .ambientSound, .ambientLevel, .focusOnVoice]),
                    levels(.experimental, [.eqPresets, .eqCustomBands]),
                    planned([.autoPowerOff])),
                equalizer: xm3Equalizer, ambientLevelRange: 1...20)
        case .wh1000xm2:
            return DeviceCapabilities(
                model: model,
                features: levels(.experimental, [.battery, .noiseCancelling, .ambientSound, .ambientLevel, .focusOnVoice,
                                                 .eqPresets, .eqCustomBands]),
                equalizer: fiveBandEqualizer, ambientLevelRange: 1...20)
        case .mdr1000x:
            return DeviceCapabilities(
                model: model,
                features: planned([.battery, .noiseCancelling, .ambientSound]),
                equalizer: nil, ambientLevelRange: 1...20)
        }
    }
}
