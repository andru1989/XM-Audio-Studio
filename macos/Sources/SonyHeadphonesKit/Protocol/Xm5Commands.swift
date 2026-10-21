import Foundation

/// WH-1000XM5 (MDR v2, table 1): the XM6 command table with the XM5's own inquired types.
///
/// Codec parity with `android/core/.../protocol/Xm5Commands.kt` only: the macOS
/// `HeadphonesController` does **not** use these yet (it drives the WH-1000XM6 alone).
/// - NC/ambient `0x17`, exactly 7 bytes `<op> 17 <update> <effect> <0 NC / 1 ambient> <voice> <level>`.
/// - EQ `0x00`, Clear Bass + 5 bands, `wire = steps + 10` (`ClearBassEqualizer`).
/// - Battery `22 00` and speak-to-chat `F6/F8 0C`, `FA/FC 0C` are byte-identical to the XM6
///   and delegate to `SonyCommands`.
/// Auto power off, pause-when-taken-off, listening modes and multipoint are deliberately absent:
/// nothing documents them for the WH-1000XM5.
public enum Xm5Commands {
    public static let ambientSoundType: UInt8 = 0x17
    public static let equalizerType: UInt8 = 0x00

    // MARK: - Noise cancelling / ambient sound (0x17, 7 bytes)

    public static func buildAmbientSoundGet() -> [UInt8] {
        [Opcode.ambientSoundControlGet, ambientSoundType]
    }

    public static func buildAmbientSoundSet(mode: AmbientSoundMode, focusOnVoice: Bool, level: Int) -> [UInt8] {
        [
            Opcode.ambientSoundControlSet,
            ambientSoundType,
            0x01, // value change status: committed
            mode == .off ? 0x00 : 0x01,
            mode == .ambientSound ? 0x01 : 0x00,
            focusOnVoice ? 0x01 : 0x00,
            UInt8(max(1, min(20, level))),
        ]
    }

    public static func decodeAmbientSound(_ p: [UInt8]) -> AmbientSoundState? {
        guard p.count == 7,
              p[0] == Opcode.ambientSoundControlRet || p[0] == Opcode.ambientSoundControlNotify,
              p[1] == ambientSoundType,
              let committed = boolFromByte(p[2]),
              let effectOn = boolFromByte(p[3]),
              let ambient = boolFromByte(p[4]),
              let focusOnVoice = boolFromByte(p[5]),
              p[6] <= 20 else { return nil }
        let mode: AmbientSoundMode = !effectOn ? .off : (ambient ? .ambientSound : .noiseCancelling)
        return AmbientSoundState(
            mode: mode,
            focusOnVoice: focusOnVoice,
            level: Int(p[6]),
            subtype: ambientSoundType,
            hasWindNoiseByte: false,
            isUpdate: committed
        )
    }

    // MARK: - Battery (single, type 0x00 or 0x08): identical to the XM6

    public static func buildBatteryGet() -> [UInt8] {
        SonyCommands.buildBatteryGet()
    }

    public static func decodeBattery(_ p: [UInt8]) -> BatteryStatus? {
        guard let opcode = p.first, SonyCommands.isBatteryReply(opcode: opcode) else { return nil }
        return SonyCommands.decodeBattery(p)
    }

    // MARK: - Equalizer (0x00): Clear Bass + 5 bands, +10

    public static func buildEqualizerGet() -> [UInt8] {
        ClearBassEqualizer.get(type: equalizerType)
    }

    public static func buildEqualizerCapabilityGet() -> [UInt8] {
        SonyCommands.buildEqualizerCapabilityGet(subtype: equalizerType)
    }

    public static func buildEqualizerPresetSet(code: UInt8) -> [UInt8] {
        ClearBassEqualizer.presetSet(type: equalizerType, code: code)
    }

    /// Nil unless exactly five bands are given.
    public static func buildEqualizerBandsSet(bands: [Int], clearBass: Int) -> [UInt8]? {
        ClearBassEqualizer.bandsSet(type: equalizerType, bands: bands, clearBass: clearBass)
    }

    public static func decodeEqualizer(_ p: [UInt8]) -> EqualizerState? {
        ClearBassEqualizer.decode(p, type: equalizerType)
    }

    public static func decodeEqualizerCapability(_ p: [UInt8]) -> EqualizerCapability? {
        guard p.count >= 2, p[0] == Opcode.equalizerCapabilityRet, p[1] == equalizerType else { return nil }
        return SonyCommands.decodeEqualizerCapability(p)
    }

    // MARK: - Speak-to-Chat (TYPE2, 0x0C): identical to the XM6, enable flag inverted (00 = on)

    public static func buildSpeakToChatEnabledGet() -> [UInt8] {
        SonyCommands.buildSpeakToChatEnabledGet()
    }

    public static func buildSpeakToChatEnabledSet(_ enabled: Bool) -> [UInt8] {
        SonyCommands.buildSpeakToChatEnabledSet(enabled)
    }

    public static func decodeSpeakToChatEnabled(_ p: [UInt8]) -> Bool? {
        guard let opcode = p.first, SonyCommands.isSpeakToChatEnabledReply(opcode: opcode) else { return nil }
        return SonyCommands.decodeSpeakToChatEnabled(p)
    }

    public static func buildSpeakToChatConfigGet() -> [UInt8] {
        SonyCommands.buildSpeakToChatConfigGet()
    }

    public static func buildSpeakToChatConfigSet(_ config: SpeakToChatConfigState) -> [UInt8] {
        SonyCommands.buildSpeakToChatConfigSet(config)
    }

    public static func decodeSpeakToChatConfig(_ p: [UInt8]) -> SpeakToChatConfigState? {
        guard let opcode = p.first, SonyCommands.isSpeakToChatConfigReply(opcode: opcode) else { return nil }
        return SonyCommands.decodeSpeakToChatConfig(p)
    }
}

/// Decodes WH-1000XM5 payloads; only the features `Xm5Commands` implements, everything else is nil.
public enum Xm5EventDecoder {
    public static func decode(payload: [UInt8], messageType: SonyMessageType) -> HeadphonesEvent? {
        guard messageType == .command1, let opcode = payload.first else { return nil }
        typealias C = Xm5Commands
        switch opcode {
        case Opcode.initReply:
            return .protocolInfo(SonyCommands.protocolVersion(fromInitReplyPayload: payload))
        case Opcode.deviceInfoRet:
            return decodeIdentityEvent(payload)
        case Opcode.equalizerCapabilityRet:
            return C.decodeEqualizerCapability(payload).map { .equalizerCapability($0) }
        case Opcode.batteryLevelReply, Opcode.batteryLevelNotify:
            return C.decodeBattery(payload).map { .battery($0) }
        case Opcode.ambientSoundControlRet, Opcode.ambientSoundControlNotify:
            return C.decodeAmbientSound(payload).map { .ambientSound($0) }
        case Opcode.equalizerRet, Opcode.equalizerNotify:
            return C.decodeEqualizer(payload).map { .equalizer($0) }
        case Opcode.buttonModeFamilyRet, Opcode.buttonModeFamilyNotify:
            return C.decodeSpeakToChatEnabled(payload).map { .speakToChatEnabled($0) }
        case Opcode.chatConfigFamilyRet, Opcode.chatConfigFamilyNotify:
            return C.decodeSpeakToChatConfig(payload).map { .speakToChatConfig($0) }
        default:
            return nil
        }
    }
}
