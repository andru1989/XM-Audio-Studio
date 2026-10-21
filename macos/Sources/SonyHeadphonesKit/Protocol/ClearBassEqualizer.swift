import Foundation

/// The six-byte equalizer layout of the 5-band + Clear Bass generation:
///
///     57/59 <type> <preset> 06 <ClearBass> <400> <1k> <2.5k> <6.3k> <16k>    every byte = steps + 10 (-10...+10)
///     58    <type> <preset> 00                                              select a preset (no band bytes)
///     58    <type> A0       06 <ClearBass> <400> <1k> <2.5k> <6.3k> <16k>    custom curve
///
/// Used by the WH-1000XM3/XM4 (MDR v1, inquired type 0x01) and the WH-1000XM5 (MDR v2, inquired
/// type 0x00). The EQEBB opcodes 0x56-0x59 are the same numbers in both command tables, so only
/// the inquired type differs and every caller passes it explicitly. Mirrors
/// `android/core/.../protocol/ClearBassEqualizer.kt`; both are held to
/// `shared/protocol-fixtures/*-v1.json` and `*-xm5.json`.
enum ClearBassEqualizer {
    static let offset = 10
    static let stepRange = -10...10
    /// Graphic bands; Clear Bass travels as an extra first byte.
    static let bandCount = 5
    private static let wireCount = bandCount + 1
    private static let customPreset: UInt8 = 0xa0

    static func get(type: UInt8) -> [UInt8] {
        [Opcode.equalizerGet, type]
    }

    static func presetSet(type: UInt8, code: UInt8) -> [UInt8] {
        [Opcode.equalizerSet, type, code, 0x00]
    }

    /// Nil unless exactly `bandCount` bands are given: never sends a malformed curve.
    static func bandsSet(type: UInt8, bands: [Int], clearBass: Int) -> [UInt8]? {
        guard bands.count == bandCount else { return nil }
        func wire(_ steps: Int) -> UInt8 { UInt8(min(max(steps, stepRange.lowerBound), stepRange.upperBound) + offset) }
        return [Opcode.equalizerSet, type, customPreset, UInt8(wireCount), wire(clearBass)] + bands.map(wire)
    }

    static func decode(_ payload: [UInt8], type: UInt8) -> EqualizerState? {
        guard payload.count == 4 + wireCount,
              payload[0] == Opcode.equalizerRet || payload[0] == Opcode.equalizerNotify,
              payload[1] == type, Int(payload[3]) == wireCount else { return nil }
        let steps = payload[4...].map { Int($0) - offset }
        guard steps.allSatisfy(stepRange.contains) else { return nil }
        return EqualizerState(presetCode: payload[2], bands: Array(steps.dropFirst()), subtype: type, clearBass: steps[0])
    }
}

/// `05 01|02 <len> <ASCII>`: identical on MDR v1 and v2 (XM3 and WF-1000XM5 captures).
func decodeIdentityEvent(_ payload: [UInt8]) -> HeadphonesEvent? {
    guard let (type, text) = SonyCommands.decodeDeviceInfo(payload) else { return nil }
    return type == Subtype.deviceInfoModelName ? .modelName(text) : .firmwareVersion(text)
}
