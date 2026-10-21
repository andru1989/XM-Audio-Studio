package io.github.andru1989.xmaudiostudio.core.protocol

import io.github.andru1989.xmaudiostudio.core.model.EqualizerState

/**
 * The six-byte equalizer layout of the 5-band + Clear Bass generation:
 *
 * ```
 * 57/59 <type> <preset> 06 <ClearBass> <400> <1k> <2.5k> <6.3k> <16k>     every byte = steps + 10 (-10..+10)
 * 58    <type> <preset> 00                                               select a preset (no band bytes)
 * 58    <type> A0       06 <ClearBass> <400> <1k> <2.5k> <6.3k> <16k>     custom curve
 * ```
 *
 * Used by the WH-1000XM3/XM4 (MDR v1, inquired type 0x01) and the WH-1000XM5 (MDR v2, inquired
 * type 0x00). The EQEBB opcodes 0x56-0x59 are the same numbers in both command tables, so only
 * the inquired type differs, and every caller passes it explicitly. Evidence: XM3 capture
 * `57 01 00 06 0a 0a 0a 0a 0a 0a` (mos9527 tests, MIT), WF-1000XM5 capture
 * `57 00 a0 06 14 0b 0a 0d 09 0c`, writes from sony-device-center (MIT; XM4 fw 3.0.1 and XM5 fw
 * 2.3.1 reported working). See shared/protocol-spec/PROTOCOL.md §5.
 */
internal object ClearBassEqualizer {
    const val OFFSET = 10
    val STEP_RANGE = -10..10
    /** Graphic bands; Clear Bass travels as an extra first byte. */
    const val BAND_COUNT = 5
    private const val WIRE_COUNT = BAND_COUNT + 1
    private const val CUSTOM_PRESET = 0xa0

    fun get(type: Int): ByteArray = bytes(Opcode.EQ_GET, type)

    fun presetSet(type: Int, code: Int): ByteArray = bytes(Opcode.EQ_SET, type, code, 0x00)

    /** Null unless exactly [BAND_COUNT] bands are given: never sends a malformed curve. */
    fun bandsSet(type: Int, bands: List<Int>, clearBass: Int): ByteArray? {
        if (bands.size != BAND_COUNT) return null
        val out = mutableListOf(Opcode.EQ_SET, type, CUSTOM_PRESET, WIRE_COUNT, clearBass.coerceIn(STEP_RANGE) + OFFSET)
        for (gain in bands) out += gain.coerceIn(STEP_RANGE) + OFFSET
        return bytes(*out.toIntArray())
    }

    fun decode(p: ByteArray, type: Int): EqualizerState? {
        if (p.size != 4 + WIRE_COUNT || (p.u(0) != Opcode.EQ_RET && p.u(0) != Opcode.EQ_NOTIFY)) return null
        if (p.u(1) != type || p.u(3) != WIRE_COUNT) return null
        val steps = (0 until WIRE_COUNT).map { p.u(4 + it) - OFFSET }
        if (steps.any { it !in STEP_RANGE }) return null
        return EqualizerState(presetCode = p.u(2), bands = steps.drop(1), subtype = type, clearBass = steps[0])
    }
}

/** `05 01|02 <len> <ASCII>`: identical on MDR v1 and v2 (XM3 and WF-1000XM5 captures). */
internal fun decodeIdentity(payload: ByteArray): MdrEvent? {
    val (infoType, text) = MdrCommands.decodeDeviceInfo(payload) ?: return null
    return if (infoType == Subtype.DEVICE_INFO_MODEL_NAME) MdrEvent.ModelName(text) else MdrEvent.FirmwareVersion(text)
}
