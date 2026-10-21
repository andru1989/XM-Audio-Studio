package io.github.andru1989.xmaudiostudio.core.protocol

import io.github.andru1989.xmaudiostudio.core.model.AmbientSoundMode
import io.github.andru1989.xmaudiostudio.core.model.AmbientSoundState
import io.github.andru1989.xmaudiostudio.core.model.BatteryStatus
import io.github.andru1989.xmaudiostudio.core.model.EqualizerCapability
import io.github.andru1989.xmaudiostudio.core.model.EqualizerState
import io.github.andru1989.xmaudiostudio.core.model.SpeakToChatConfig
import io.github.andru1989.xmaudiostudio.core.model.SpeakToChatSensitivity
import io.github.andru1989.xmaudiostudio.core.model.SpeakToChatTimeout
import io.github.andru1989.xmaudiostudio.core.protocol.MdrV1Commands.Op as V1Op

/**
 * MDR v1 (WH-1000XM2 / XM3 / XM4) payload builders and decoders, table 1 (frame type 0x0C).
 *
 * v1 is a *different command table* from v2, not a dialect of it: opcodes are reused with other
 * meanings and on/off polarity differs (v1: 0x01 = on). Above all, **v1 opcode 0x22 is
 * COMMON_SET_POWER_OFF** (it is the battery GET on v2). Nothing here borrows a v2 builder except
 * the handshake and identification requests, which are byte-identical in both families
 * (`00 00`, `04 01`, `04 02`; XM3 fw 4.5.2 capture).
 *
 * Sources (facts only): opcode/struct tables of mos9527/SonyHeadphonesClient `ProtocolV1T1.hpp`
 * (MIT, generated from Sound Connect metadata), its WH-1000XM3 fw 4.5.2 capture, and the
 * hardware notes of marconvcm/sony-device-center (MIT, WH-1000XM4 fw 3.0.1). Byte tables:
 * shared/protocol-spec/PROTOCOL.md §5; fixtures: shared/protocol-fixtures/events-v1.json and commands-v1.json.
 */
object MdrV1Commands {

    /** v1 table-1 opcodes used by this app (payload[0]). */
    object Op {
        const val BATTERY_GET = 0x10
        const val BATTERY_RET = 0x11
        const val BATTERY_NOTIFY = 0x13

        /** COMMON_SET_POWER_OFF. Listed only so code and tests can refuse it; never built here. */
        const val POWER_OFF = 0x22

        const val EQ_CAPABILITY_GET = 0x50
        const val EQ_CAPABILITY_RET = 0x51
        const val EQ_RET = 0x57
        const val EQ_NOTIFY = 0x59

        const val NCASM_GET = 0x66
        const val NCASM_RET = 0x67
        const val NCASM_SET = 0x68
        const val NCASM_NOTIFY = 0x69

        const val SYSTEM_GET = 0xf6
        const val SYSTEM_RET = 0xf7
        const val SYSTEM_SET = 0xf8
        const val SYSTEM_NOTIFY = 0xf9
        const val SYSTEM_EXT_GET = 0xfa
        const val SYSTEM_EXT_RET = 0xfb
        const val SYSTEM_EXT_SET = 0xfc
        const val SYSTEM_EXT_NOTIFY = 0xfd
    }

    /** v1 inquired types (payload[1]). Note they are shifted against v2 (v1 EQ is 0x01, not 0x00). */
    object Type {
        const val BATTERY_SINGLE = 0x00
        const val NOISE_CANCELLING_AND_AMBIENT_SOUND = 0x02
        const val PRESET_EQ = 0x01
        const val SMART_TALKING_MODE = 0x05
        const val ENGLISH = 0x01
    }

    // region Battery: GET 10 00 -> RET 11 00 <level> <charging> / NTFY 13 00 <level> <charging>

    fun batteryGet(): ByteArray = bytes(Op.BATTERY_GET, Type.BATTERY_SINGLE)

    /** Charging byte: 00 not charging, 01 charging, F0 unknown (treated as not charging). */
    fun decodeBattery(p: ByteArray): BatteryStatus? {
        if (p.size != 4 || (p.u(0) != Op.BATTERY_RET && p.u(0) != Op.BATTERY_NOTIFY)) return null
        if (p.u(1) != Type.BATTERY_SINGLE) return null
        val level = p.u(2)
        if (level > 100) return null
        return BatteryStatus(level = level, isCharging = p.u(3) == 0x01)
    }

    // endregion
    // region Noise cancelling / ambient sound, inquired type 0x02 (8 bytes)
    //
    // [2] NcAsmEffect 00 off, 01 on, 10 adjusting, 11 adjustment complete
    // [3] NC setting type 00 on/off, 01 level adjustment, 02 dual/single/off
    // [4] NC value 00 off (= ambient sound), 01 single (wind-noise reduction), 02 dual (= NC)
    // [5] ASM setting type 00 on/off, 01 level adjustment
    // [6] ASM id 00 normal, 01 voice (Focus on Voice)
    // [7] ambient level 0..20

    private const val EFFECT_OFF = 0x00
    private const val EFFECT_ADJUSTING = 0x10
    private const val EFFECT_ADJUSTMENT_COMPLETE = 0x11
    private const val LEVEL_ADJUSTMENT = 0x01
    private const val NC_VALUE_OFF = 0x00
    private const val NC_VALUE_DUAL = 0x02

    fun ambientSoundGet(): ByteArray = bytes(Op.NCASM_GET, Type.NOISE_CANCELLING_AND_AMBIENT_SOUND)

    /**
     * `68 02 <effect> 01 <ncValue> 01 <asmId> <level>`: the layout the MIT clients send to the
     * XM3/XM4 (sony-device-center `ProtocolV1.cpp`, Plutoberth, mos9527). Noise cancelling is
     * "dual" with level 0; ambient sound is NC value "off" with the level (1..20).
     */
    fun ambientSoundSet(mode: AmbientSoundMode, focusOnVoice: Boolean, level: Int): ByteArray = bytes(
        Op.NCASM_SET,
        Type.NOISE_CANCELLING_AND_AMBIENT_SOUND,
        if (mode == AmbientSoundMode.OFF) EFFECT_OFF else EFFECT_ADJUSTMENT_COMPLETE,
        LEVEL_ADJUSTMENT,
        if (mode == AmbientSoundMode.NOISE_CANCELLING) NC_VALUE_DUAL else NC_VALUE_OFF,
        LEVEL_ADJUSTMENT,
        if (focusOnVoice) 0x01 else 0x00,
        if (mode == AmbientSoundMode.AMBIENT_SOUND) level.coerceIn(1, 20) else 0,
    )

    fun decodeAmbientSound(p: ByteArray): AmbientSoundState? {
        if (p.size != 8 || (p.u(0) != Op.NCASM_RET && p.u(0) != Op.NCASM_NOTIFY)) return null
        if (p.u(1) != Type.NOISE_CANCELLING_AND_AMBIENT_SOUND) return null
        val effect = p.u(2)
        if (effect !in setOf(0x00, 0x01, EFFECT_ADJUSTING, EFFECT_ADJUSTMENT_COMPLETE)) return null
        if (p.u(3) > 0x02 || p.u(4) > 0x02 || p.u(5) > 0x01) return null
        val focusOnVoice = boolFromByte(p.u(6)) ?: return null
        val level = p.u(7)
        if (level !in 0..20) return null
        val mode = when {
            effect == EFFECT_OFF -> AmbientSoundMode.OFF
            p.u(4) == NC_VALUE_OFF -> AmbientSoundMode.AMBIENT_SOUND
            else -> AmbientSoundMode.NOISE_CANCELLING // dual, or single (wind-noise reduction)
        }
        return AmbientSoundState(
            mode = mode,
            focusOnVoice = focusOnVoice,
            level = level,
            subtype = Type.NOISE_CANCELLING_AND_AMBIENT_SOUND,
            isUpdate = effect != EFFECT_ADJUSTING,
        )
    }

    // endregion
    // region Equalizer, inquired type 0x01 (PRESET_EQ): Clear Bass + 5 bands, +10

    fun equalizerGet(): ByteArray = ClearBassEqualizer.get(Type.PRESET_EQ)

    fun equalizerCapabilityGet(): ByteArray = bytes(Op.EQ_CAPABILITY_GET, Type.PRESET_EQ, Type.ENGLISH)

    fun equalizerPresetSet(code: Int): ByteArray = ClearBassEqualizer.presetSet(Type.PRESET_EQ, code)

    /** Null unless exactly five bands are given. */
    fun equalizerBandsSet(bands: List<Int>, clearBass: Int): ByteArray? =
        ClearBassEqualizer.bandsSet(Type.PRESET_EQ, bands, clearBass)

    fun decodeEqualizer(p: ByteArray): EqualizerState? = ClearBassEqualizer.decode(p, Type.PRESET_EQ)

    /** Same layout as v2 (XM3 capture `51 01 06 15 0c ...`), restricted to type 0x01. */
    fun decodeEqualizerCapability(p: ByteArray): EqualizerCapability? {
        if (p.size < 2 || p.u(0) != Op.EQ_CAPABILITY_RET || p.u(1) != Type.PRESET_EQ) return null
        return MdrCommands.decodeEqualizerCapability(p)
    }

    // endregion
    // region Speak-to-Chat ("smart talking mode", SYSTEM type 0x05). WH-1000XM4 only. Not inverted.

    fun speakToChatEnabledGet(): ByteArray = bytes(Op.SYSTEM_GET, Type.SMART_TALKING_MODE)

    /** `F8 05 01 <00 off / 01 on>` (parameter type 01 = mode on/off). */
    fun speakToChatEnabledSet(enabled: Boolean): ByteArray =
        bytes(Op.SYSTEM_SET, Type.SMART_TALKING_MODE, 0x01, if (enabled) 0x01 else 0x00)

    /**
     * RET `F7 05 00 <v>` (setting type on/off) and NTFY `F9 05 01 <v>` (mode on/off). A first
     * byte of 02 reports a talking session in progress, which implies the mode is on
     * (WH-1000XM4 observation, sony-device-center).
     */
    fun decodeSpeakToChatEnabled(p: ByteArray): Boolean? {
        if (p.size != 4 || (p.u(0) != Op.SYSTEM_RET && p.u(0) != Op.SYSTEM_NOTIFY)) return null
        if (p.u(1) != Type.SMART_TALKING_MODE) return null
        return when (p.u(2)) {
            0x00, 0x01 -> boolFromByte(p.u(3))
            0x02 -> true
            else -> null
        }
    }

    fun speakToChatConfigGet(): ByteArray = bytes(Op.SYSTEM_EXT_GET, Type.SMART_TALKING_MODE)

    /** `FC 05 00 <sensitivity> <voiceFocus> <timeout>`; an unknown voice focus is written as off. */
    fun speakToChatConfigSet(config: SpeakToChatConfig): ByteArray = bytes(
        Op.SYSTEM_EXT_SET,
        Type.SMART_TALKING_MODE,
        0x00,
        config.sensitivity.code,
        if (config.voiceFocus == true) 0x01 else 0x00,
        config.timeout.code,
    )

    /** RET/NTFY `FB|FD 05 00 <sensitivity> <voiceFocus> <timeout>`. */
    fun decodeSpeakToChatConfig(p: ByteArray): SpeakToChatConfig? {
        if (p.size != 6 || (p.u(0) != Op.SYSTEM_EXT_RET && p.u(0) != Op.SYSTEM_EXT_NOTIFY)) return null
        if (p.u(1) != Type.SMART_TALKING_MODE || p.u(2) != 0x00) return null
        val sensitivity = SpeakToChatSensitivity.fromCode(p.u(3)) ?: return null
        val voiceFocus = boolFromByte(p.u(4)) ?: return null
        val timeout = SpeakToChatTimeout.fromCode(p.u(5)) ?: return null
        return SpeakToChatConfig(sensitivity, timeout, voiceFocus)
    }

    // endregion
}

/** Decodes MDR v1 payloads. Only table 1 is used on v1; anything not listed returns null. */
object MdrV1EventDecoder {
    fun decode(payload: ByteArray, type: MessageType): MdrEvent? {
        if (payload.isEmpty() || type != MessageType.COMMAND_1) return null
        val c = MdrV1Commands
        return when (payload.u(0)) {
            Opcode.INIT_REPLY -> MdrEvent.ProtocolInfo(MdrCommands.protocolVersion(payload))
            Opcode.DEVICE_INFO_RET -> decodeIdentity(payload)
            V1Op.EQ_CAPABILITY_RET -> c.decodeEqualizerCapability(payload)?.let { MdrEvent.EqCapability(it) }
            V1Op.BATTERY_RET, V1Op.BATTERY_NOTIFY -> c.decodeBattery(payload)?.let { MdrEvent.Battery(it) }
            V1Op.NCASM_RET, V1Op.NCASM_NOTIFY -> c.decodeAmbientSound(payload)?.let { MdrEvent.AmbientSound(it) }
            V1Op.EQ_RET, V1Op.EQ_NOTIFY -> c.decodeEqualizer(payload)?.let { MdrEvent.Equalizer(it) }
            V1Op.SYSTEM_RET, V1Op.SYSTEM_NOTIFY -> c.decodeSpeakToChatEnabled(payload)?.let { MdrEvent.SpeakToChatEnabled(it) }
            V1Op.SYSTEM_EXT_RET, V1Op.SYSTEM_EXT_NOTIFY -> c.decodeSpeakToChatConfig(payload)?.let { MdrEvent.SpeakToChat(it) }
            else -> null
        }
    }
}
