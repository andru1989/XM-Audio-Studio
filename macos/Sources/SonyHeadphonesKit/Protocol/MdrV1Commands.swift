import Foundation

/// MDR v1 (WH-1000XM2 / XM3 / XM4) payload builders and decoders, table 1 (frame type 0x0C).
///
/// Codec parity with `android/core/.../protocol/MdrV1Commands.kt` only: the macOS
/// `HeadphonesController` does **not** use these yet (it drives the WH-1000XM6 alone).
///
/// v1 is a *different command table* from v2: opcodes are reused with other meanings and on/off
/// polarity differs (v1: 0x01 = on). **v1 opcode 0x22 is COMMON_SET_POWER_OFF** (the v2 battery
/// GET). Nothing here borrows a v2 builder except the byte-identical handshake and identification
/// requests (`00 00`, `04 01`, `04 02`). Facts from mos9527/SonyHeadphonesClient (MIT: v1 tables,
/// WH-1000XM3 fw 4.5.2 capture) and marconvcm/sony-device-center (MIT: WH-1000XM4 notes).
public enum MdrV1Commands {

    /// v1 table-1 opcodes (payload[0]).
    public enum Op {
        public static let batteryGet: UInt8 = 0x10
        public static let batteryRet: UInt8 = 0x11
        public static let batteryNotify: UInt8 = 0x13
        /// COMMON_SET_POWER_OFF. Listed only so code and tests can refuse it; never built here.
        public static let powerOff: UInt8 = 0x22
        public static let eqCapabilityGet: UInt8 = 0x50
        public static let eqCapabilityRet: UInt8 = 0x51
        public static let eqRet: UInt8 = 0x57
        public static let eqNotify: UInt8 = 0x59
        public static let ncAsmGet: UInt8 = 0x66
        public static let ncAsmRet: UInt8 = 0x67
        public static let ncAsmSet: UInt8 = 0x68
        public static let ncAsmNotify: UInt8 = 0x69
        public static let systemGet: UInt8 = 0xf6
        public static let systemRet: UInt8 = 0xf7
        public static let systemSet: UInt8 = 0xf8
        public static let systemNotify: UInt8 = 0xf9
        public static let systemExtGet: UInt8 = 0xfa
        public static let systemExtRet: UInt8 = 0xfb
        public static let systemExtSet: UInt8 = 0xfc
        public static let systemExtNotify: UInt8 = 0xfd
    }

    /// v1 inquired types (payload[1]); shifted against v2 (v1 EQ is 0x01, not 0x00).
    public enum InquiredType {
        public static let batterySingle: UInt8 = 0x00
        public static let noiseCancellingAndAmbientSound: UInt8 = 0x02
        public static let presetEq: UInt8 = 0x01
        public static let smartTalkingMode: UInt8 = 0x05
        public static let english: UInt8 = 0x01
    }

    // MARK: - Battery: GET 10 00 -> RET 11 00 <level> <charging> / NTFY 13 00 <level> <charging>

    public static func buildBatteryGet() -> [UInt8] {
        [Op.batteryGet, InquiredType.batterySingle]
    }

    /// Charging byte: 00 not charging, 01 charging, F0 unknown (treated as not charging).
    public static func decodeBattery(_ p: [UInt8]) -> BatteryStatus? {
        guard p.count == 4, p[0] == Op.batteryRet || p[0] == Op.batteryNotify,
              p[1] == InquiredType.batterySingle, p[2] <= 100 else { return nil }
        return BatteryStatus(level: Int(p[2]), isCharging: p[3] == 0x01)
    }

    // MARK: - Noise cancelling / ambient sound, inquired type 0x02 (8 bytes)
    //
    // [2] effect 00 off, 01 on, 10 adjusting, 11 adjustment complete
    // [3] NC setting type 00..02   [4] NC value 00 off (= ambient), 01 single (wind), 02 dual (= NC)
    // [5] ASM setting type 00..01  [6] ASM id 00 normal, 01 voice   [7] ambient level 0..20

    private static let effectOff: UInt8 = 0x00
    private static let effectAdjusting: UInt8 = 0x10
    private static let effectAdjustmentComplete: UInt8 = 0x11
    private static let levelAdjustment: UInt8 = 0x01

    public static func buildAmbientSoundGet() -> [UInt8] {
        [Op.ncAsmGet, InquiredType.noiseCancellingAndAmbientSound]
    }

    /// `68 02 <effect> 01 <ncValue> 01 <asmId> <level>` (sony-device-center `ProtocolV1.cpp`,
    /// Plutoberth, mos9527). Noise cancelling is "dual" with level 0; ambient sound is NC value
    /// "off" with the level (1...20).
    public static func buildAmbientSoundSet(mode: AmbientSoundMode, focusOnVoice: Bool, level: Int) -> [UInt8] {
        [
            Op.ncAsmSet,
            InquiredType.noiseCancellingAndAmbientSound,
            mode == .off ? effectOff : effectAdjustmentComplete,
            levelAdjustment,
            mode == .noiseCancelling ? 0x02 : 0x00,
            levelAdjustment,
            focusOnVoice ? 0x01 : 0x00,
            mode == .ambientSound ? UInt8(max(1, min(20, level))) : 0x00,
        ]
    }

    public static func decodeAmbientSound(_ p: [UInt8]) -> AmbientSoundState? {
        guard p.count == 8, p[0] == Op.ncAsmRet || p[0] == Op.ncAsmNotify,
              p[1] == InquiredType.noiseCancellingAndAmbientSound else { return nil }
        let effect = p[2]
        guard [0x00, 0x01, effectAdjusting, effectAdjustmentComplete].contains(effect),
              p[3] <= 0x02, p[4] <= 0x02, p[5] <= 0x01,
              let focusOnVoice = boolFromByte(p[6]), p[7] <= 20 else { return nil }
        let mode: AmbientSoundMode
        if effect == effectOff {
            mode = .off
        } else if p[4] == 0x00 {
            mode = .ambientSound
        } else {
            mode = .noiseCancelling // dual, or single (wind-noise reduction)
        }
        return AmbientSoundState(
            mode: mode,
            focusOnVoice: focusOnVoice,
            level: Int(p[7]),
            subtype: InquiredType.noiseCancellingAndAmbientSound,
            hasWindNoiseByte: false,
            isUpdate: effect != effectAdjusting
        )
    }

    // MARK: - Equalizer, inquired type 0x01 (PRESET_EQ): Clear Bass + 5 bands, +10

    public static func buildEqualizerGet() -> [UInt8] {
        ClearBassEqualizer.get(type: InquiredType.presetEq)
    }

    public static func buildEqualizerCapabilityGet() -> [UInt8] {
        [Op.eqCapabilityGet, InquiredType.presetEq, InquiredType.english]
    }

    public static func buildEqualizerPresetSet(code: UInt8) -> [UInt8] {
        ClearBassEqualizer.presetSet(type: InquiredType.presetEq, code: code)
    }

    /// Nil unless exactly five bands are given.
    public static func buildEqualizerBandsSet(bands: [Int], clearBass: Int) -> [UInt8]? {
        ClearBassEqualizer.bandsSet(type: InquiredType.presetEq, bands: bands, clearBass: clearBass)
    }

    public static func decodeEqualizer(_ p: [UInt8]) -> EqualizerState? {
        ClearBassEqualizer.decode(p, type: InquiredType.presetEq)
    }

    /// Same layout as v2 (XM3 capture `51 01 06 15 0c ...`), restricted to type 0x01.
    public static func decodeEqualizerCapability(_ p: [UInt8]) -> EqualizerCapability? {
        guard p.count >= 2, p[0] == Op.eqCapabilityRet, p[1] == InquiredType.presetEq else { return nil }
        return SonyCommands.decodeEqualizerCapability(p)
    }

    // MARK: - Speak-to-Chat ("smart talking mode", SYSTEM type 0x05). WH-1000XM4 only. Not inverted.

    public static func buildSpeakToChatEnabledGet() -> [UInt8] {
        [Op.systemGet, InquiredType.smartTalkingMode]
    }

    /// `F8 05 01 <00 off / 01 on>` (parameter type 01 = mode on/off).
    public static func buildSpeakToChatEnabledSet(_ enabled: Bool) -> [UInt8] {
        [Op.systemSet, InquiredType.smartTalkingMode, 0x01, enabled ? 0x01 : 0x00]
    }

    /// RET `F7 05 00 <v>` and NTFY `F9 05 01 <v>`; a first byte of 02 reports a talking session
    /// in progress, which implies the mode is on (WH-1000XM4 observation, sony-device-center).
    public static func decodeSpeakToChatEnabled(_ p: [UInt8]) -> Bool? {
        guard p.count == 4, p[0] == Op.systemRet || p[0] == Op.systemNotify,
              p[1] == InquiredType.smartTalkingMode else { return nil }
        switch p[2] {
        case 0x00, 0x01: return boolFromByte(p[3])
        case 0x02: return true
        default: return nil
        }
    }

    public static func buildSpeakToChatConfigGet() -> [UInt8] {
        [Op.systemExtGet, InquiredType.smartTalkingMode]
    }

    /// `FC 05 00 <sensitivity> <voiceFocus> <timeout>`; an unknown voice focus is written as off.
    public static func buildSpeakToChatConfigSet(_ config: SpeakToChatConfigState) -> [UInt8] {
        [Op.systemExtSet, InquiredType.smartTalkingMode, 0x00,
         config.sensitivity.rawValue, config.voiceFocus == true ? 0x01 : 0x00, config.timeout.rawValue]
    }

    /// RET/NTFY `FB|FD 05 00 <sensitivity> <voiceFocus> <timeout>`.
    public static func decodeSpeakToChatConfig(_ p: [UInt8]) -> SpeakToChatConfigState? {
        guard p.count == 6, p[0] == Op.systemExtRet || p[0] == Op.systemExtNotify,
              p[1] == InquiredType.smartTalkingMode, p[2] == 0x00,
              let sensitivity = SpeakToChatSensitivity(rawValue: p[3]),
              let voiceFocus = boolFromByte(p[4]),
              let timeout = SpeakToChatTimeout(rawValue: p[5]) else { return nil }
        return SpeakToChatConfigState(sensitivity: sensitivity, timeout: timeout, voiceFocus: voiceFocus)
    }
}

/// Decodes MDR v1 payloads. Only table 1 is used on v1; anything not listed returns nil.
public enum MdrV1EventDecoder {
    public static func decode(payload: [UInt8], messageType: SonyMessageType) -> HeadphonesEvent? {
        guard messageType == .command1, let opcode = payload.first else { return nil }
        typealias C = MdrV1Commands
        typealias Op = MdrV1Commands.Op
        switch opcode {
        case Opcode.initReply:
            return .protocolInfo(SonyCommands.protocolVersion(fromInitReplyPayload: payload))
        case Opcode.deviceInfoRet:
            return decodeIdentityEvent(payload)
        case Op.eqCapabilityRet:
            return C.decodeEqualizerCapability(payload).map { .equalizerCapability($0) }
        case Op.batteryRet, Op.batteryNotify:
            return C.decodeBattery(payload).map { .battery($0) }
        case Op.ncAsmRet, Op.ncAsmNotify:
            return C.decodeAmbientSound(payload).map { .ambientSound($0) }
        case Op.eqRet, Op.eqNotify:
            return C.decodeEqualizer(payload).map { .equalizer($0) }
        case Op.systemRet, Op.systemNotify:
            return C.decodeSpeakToChatEnabled(payload).map { .speakToChatEnabled($0) }
        case Op.systemExtRet, Op.systemExtNotify:
            return C.decodeSpeakToChatConfig(payload).map { .speakToChatConfig($0) }
        default:
            return nil
        }
    }
}
