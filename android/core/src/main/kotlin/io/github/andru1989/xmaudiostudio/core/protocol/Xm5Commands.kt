package io.github.andru1989.xmaudiostudio.core.protocol

import io.github.andru1989.xmaudiostudio.core.model.AmbientSoundMode
import io.github.andru1989.xmaudiostudio.core.model.AmbientSoundState
import io.github.andru1989.xmaudiostudio.core.model.BatteryStatus
import io.github.andru1989.xmaudiostudio.core.model.EqualizerCapability
import io.github.andru1989.xmaudiostudio.core.model.EqualizerState
import io.github.andru1989.xmaudiostudio.core.model.SpeakToChatConfig

/**
 * WH-1000XM5 (MDR v2, table 1). Same command table as the XM6 ([MdrCommands]) with the
 * XM5's own inquired types:
 * - NC/ambient `0x17`, exactly 7 bytes `<op> 17 <update> <effect> <0 NC / 1 ambient> <voice> <level>`
 *   (WF-1000XM5 capture `67 17 01 00 01 00 14`; sony-device-center XM5 fw 2.3.1 read/write).
 * - EQ `0x00`, Clear Bass + 5 bands, `wire = steps + 10` ([ClearBassEqualizer]).
 * - Battery `22 00` and speak-to-chat `F6/F8 0C`, `FA/FC 0C` are byte-identical to the XM6
 *   and delegate to [MdrCommands] (WF-1000XM5 captures `F7 0C 01 01`, `FB 0C 00 00`).
 *
 * Auto power off, pause-when-taken-off, listening modes and multipoint are deliberately absent:
 * no capture or report documents them for the WH-1000XM5 (see docs/compatibility/OTHER-MODELS.md).
 */
object Xm5Commands {
    const val NCASM_TYPE = 0x17
    const val EQ_TYPE = 0x00

    // region Noise cancelling / ambient sound (0x17, 7 bytes)

    fun ambientSoundGet(): ByteArray = bytes(Opcode.NCASM_GET, NCASM_TYPE)

    fun ambientSoundSet(mode: AmbientSoundMode, focusOnVoice: Boolean, level: Int): ByteArray = bytes(
        Opcode.NCASM_SET,
        NCASM_TYPE,
        0x01, // value change status: committed
        if (mode == AmbientSoundMode.OFF) 0x00 else 0x01,
        if (mode == AmbientSoundMode.AMBIENT_SOUND) 0x01 else 0x00,
        if (focusOnVoice) 0x01 else 0x00,
        level.coerceIn(1, 20),
    )

    fun decodeAmbientSound(p: ByteArray): AmbientSoundState? {
        if (p.size != 7 || (p.u(0) != Opcode.NCASM_RET && p.u(0) != Opcode.NCASM_NOTIFY)) return null
        if (p.u(1) != NCASM_TYPE) return null
        val committed = boolFromByte(p.u(2)) ?: return null
        val effectOn = boolFromByte(p.u(3)) ?: return null
        val ambient = boolFromByte(p.u(4)) ?: return null
        val focusOnVoice = boolFromByte(p.u(5)) ?: return null
        val level = p.u(6)
        if (level !in 0..20) return null
        val mode = when {
            !effectOn -> AmbientSoundMode.OFF
            ambient -> AmbientSoundMode.AMBIENT_SOUND
            else -> AmbientSoundMode.NOISE_CANCELLING
        }
        return AmbientSoundState(mode = mode, focusOnVoice = focusOnVoice, level = level, subtype = NCASM_TYPE, isUpdate = committed)
    }

    // endregion
    // region Battery (single, type 0x00 or 0x08): identical to the XM6

    fun batteryGet(): ByteArray = MdrCommands.batteryGet()

    fun decodeBattery(p: ByteArray): BatteryStatus? =
        if (p.isNotEmpty() && MdrCommands.isBatteryReply(p.u(0))) MdrCommands.decodeBattery(p) else null

    // endregion
    // region Equalizer (0x00): Clear Bass + 5 bands, +10

    fun equalizerGet(): ByteArray = ClearBassEqualizer.get(EQ_TYPE)

    fun equalizerCapabilityGet(): ByteArray = MdrCommands.equalizerCapabilityGet(EQ_TYPE)

    fun equalizerPresetSet(code: Int): ByteArray = ClearBassEqualizer.presetSet(EQ_TYPE, code)

    /** Null unless exactly five bands are given. */
    fun equalizerBandsSet(bands: List<Int>, clearBass: Int): ByteArray? = ClearBassEqualizer.bandsSet(EQ_TYPE, bands, clearBass)

    fun decodeEqualizer(p: ByteArray): EqualizerState? = ClearBassEqualizer.decode(p, EQ_TYPE)

    fun decodeEqualizerCapability(p: ByteArray): EqualizerCapability? {
        if (p.size < 2 || p.u(0) != Opcode.EQ_CAPABILITY_RET || p.u(1) != EQ_TYPE) return null
        return MdrCommands.decodeEqualizerCapability(p)
    }

    // endregion
    // region Speak-to-Chat (TYPE2, 0x0C): identical to the XM6, enable flag inverted (00 = on)

    fun speakToChatEnabledGet(): ByteArray = MdrCommands.speakToChatEnabledGet()

    fun speakToChatEnabledSet(enabled: Boolean): ByteArray = MdrCommands.speakToChatEnabledSet(enabled)

    fun decodeSpeakToChatEnabled(p: ByteArray): Boolean? =
        if (p.isNotEmpty() && MdrCommands.isSpeakToChatEnabledReply(p.u(0))) MdrCommands.decodeSpeakToChatEnabled(p) else null

    fun speakToChatConfigGet(): ByteArray = MdrCommands.speakToChatConfigGet()

    fun speakToChatConfigSet(config: SpeakToChatConfig): ByteArray = MdrCommands.speakToChatConfigSet(config)

    fun decodeSpeakToChatConfig(p: ByteArray): SpeakToChatConfig? =
        if (p.isNotEmpty() && MdrCommands.isSpeakToChatConfigReply(p.u(0))) MdrCommands.decodeSpeakToChatConfig(p) else null

    // endregion
}

/** Decodes WH-1000XM5 payloads; only the features [Xm5Commands] implements, everything else is null. */
object Xm5EventDecoder {
    fun decode(payload: ByteArray, type: MessageType): MdrEvent? {
        if (payload.isEmpty() || type != MessageType.COMMAND_1) return null
        val c = Xm5Commands
        return when (payload.u(0)) {
            Opcode.INIT_REPLY -> MdrEvent.ProtocolInfo(MdrCommands.protocolVersion(payload))
            Opcode.DEVICE_INFO_RET -> decodeIdentity(payload)
            Opcode.EQ_CAPABILITY_RET -> c.decodeEqualizerCapability(payload)?.let { MdrEvent.EqCapability(it) }
            Opcode.BATTERY_RET, Opcode.BATTERY_NOTIFY -> c.decodeBattery(payload)?.let { MdrEvent.Battery(it) }
            Opcode.NCASM_RET, Opcode.NCASM_NOTIFY -> c.decodeAmbientSound(payload)?.let { MdrEvent.AmbientSound(it) }
            Opcode.EQ_RET, Opcode.EQ_NOTIFY -> c.decodeEqualizer(payload)?.let { MdrEvent.Equalizer(it) }
            Opcode.SYSTEM_RET, Opcode.SYSTEM_NOTIFY -> c.decodeSpeakToChatEnabled(payload)?.let { MdrEvent.SpeakToChatEnabled(it) }
            Opcode.SYSTEM_EXT_RET, Opcode.SYSTEM_EXT_NOTIFY -> c.decodeSpeakToChatConfig(payload)?.let { MdrEvent.SpeakToChat(it) }
            else -> null
        }
    }
}
