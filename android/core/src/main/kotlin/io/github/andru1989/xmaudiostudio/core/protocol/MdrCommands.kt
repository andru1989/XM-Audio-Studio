package io.github.andru1989.xmaudiostudio.core.protocol

import io.github.andru1989.xmaudiostudio.core.model.AmbientSoundMode
import io.github.andru1989.xmaudiostudio.core.model.AmbientSoundState
import io.github.andru1989.xmaudiostudio.core.model.AutomaticPowerOffMode
import io.github.andru1989.xmaudiostudio.core.model.BatteryStatus
import io.github.andru1989.xmaudiostudio.core.model.BgmRoomSize
import io.github.andru1989.xmaudiostudio.core.model.EqualizerCapability
import io.github.andru1989.xmaudiostudio.core.model.EqualizerPreset
import io.github.andru1989.xmaudiostudio.core.model.EqualizerState
import io.github.andru1989.xmaudiostudio.core.model.MultipointDevice
import io.github.andru1989.xmaudiostudio.core.model.ProtocolVersion
import io.github.andru1989.xmaudiostudio.core.model.SpeakToChatConfig
import io.github.andru1989.xmaudiostudio.core.model.SpeakToChatSensitivity
import io.github.andru1989.xmaudiostudio.core.model.SpeakToChatTimeout

/**
 * Payload builders and decoders for the MDR v2 command families used by the
 * WH-1000XM6. A line-for-line port of macos/Sources/SonyHeadphonesKit/Protocol/Commands.swift;
 * both are held to the same bytes by shared/protocol-fixtures.
 */
object MdrCommands {

    // region Handshake

    fun init(): ByteArray = bytes(Opcode.INIT_REQUEST, 0x00)

    /** The init reply's length is the only reliable v1/v2 discriminator. */
    fun protocolVersion(payload: ByteArray): ProtocolVersion {
        if (payload.isEmpty() || payload.u(0) != Opcode.INIT_REPLY) return ProtocolVersion.UNKNOWN
        return when (payload.size) {
            4 -> ProtocolVersion.V1
            8 -> ProtocolVersion.V2
            else -> ProtocolVersion.UNKNOWN
        }
    }

    // endregion
    // region Identification (identical on v1 and v2)

    fun modelNameGet(): ByteArray = bytes(Opcode.DEVICE_INFO_GET, Subtype.DEVICE_INFO_MODEL_NAME)

    fun firmwareVersionGet(): ByteArray = bytes(Opcode.DEVICE_INFO_GET, Subtype.DEVICE_INFO_FIRMWARE)

    /** `05 <01|02> <len> <ASCII>`; returns (type, text). */
    fun decodeDeviceInfo(p: ByteArray): Pair<Int, String>? {
        if (p.size < 3 || p.u(0) != Opcode.DEVICE_INFO_RET) return null
        val type = p.u(1)
        if (type != Subtype.DEVICE_INFO_MODEL_NAME && type != Subtype.DEVICE_INFO_FIRMWARE) return null
        val length = p.u(2)
        if (length == 0 || p.size < 3 + length) return null
        val text = String(p.copyOfRange(3, 3 + length), Charsets.US_ASCII).trim()
        if (text.isEmpty() || text.any { it.code < 0x20 || it.code > 0x7e }) return null
        return type to text
    }

    // endregion
    // region Ambient sound control

    fun ambientSoundGet(subtype: Int = Subtype.NCASM_STANDARD): ByteArray = bytes(Opcode.NCASM_GET, subtype)

    /** [committed] is the value-change status byte: 0x00 while a slider is dragged, 0x01 on release. */
    fun ambientSoundSet(state: AmbientSoundState, committed: Boolean = true): ByteArray {
        val out = mutableListOf(
            Opcode.NCASM_SET,
            if (state.subtype == 0x19) 0x17 else state.subtype,
            if (committed) 0x01 else 0x00,
            if (state.mode == AmbientSoundMode.OFF) 0x00 else 0x01,
            if (state.mode == AmbientSoundMode.AMBIENT_SOUND) 0x01 else 0x00,
        )
        if (state.hasWindNoiseByte && state.subtype != 0x19) out += 0x02 // 0x02 normal, 0x03 wind reduction
        out += if (state.focusOnVoice) 0x01 else 0x00
        out += state.level.coerceIn(1, 20)
        return bytes(*out.toIntArray())
    }

    /**
     * Accepts the older 0x15/0x17/0x22 layouts (6-8 bytes) and XM6 firmware 3.x's nine-byte
     * 0x19 report: `[opcode, 0x19, update, on, ambient, voice, level, unknown, unknown]`.
     */
    fun decodeAmbientSound(p: ByteArray): AmbientSoundState? {
        if (p.size < 6) return null
        val subtype = p.u(1)
        when (subtype) {
            0x19 -> if (p.size != 9) return null
            0x15, 0x17, 0x22 -> if (p.size > 8) return null
            else -> return null
        }
        val hasWindNoiseByte = subtype == 0x17 && p.size > 7

        val mode = when (p.u(3)) {
            0x00 -> AmbientSoundMode.OFF
            0x01 -> when {
                hasWindNoiseByte && (p.u(5) == 0x03 || p.u(5) == 0x05) -> AmbientSoundMode.NOISE_CANCELLING
                subtype == 0x22 -> AmbientSoundMode.AMBIENT_SOUND
                p.u(4) == 0x01 -> AmbientSoundMode.AMBIENT_SOUND
                else -> AmbientSoundMode.NOISE_CANCELLING
            }
            else -> return null
        }
        val i = if (subtype == 0x19) 5 else p.size - 2
        val focusOnVoice = boolFromByte(p.u(i)) ?: return null
        val level = p.u(i + 1)
        if (level !in 0..20) return null
        return AmbientSoundState(
            mode = mode,
            focusOnVoice = focusOnVoice,
            level = level,
            subtype = subtype,
            hasWindNoiseByte = hasWindNoiseByte,
            isUpdate = p.u(2) != 0,
            trailingBytes = if (subtype == 0x19) listOf(p.u(7), p.u(8)) else emptyList(),
        )
    }

    fun isAmbientSoundReply(opcode: Int) = opcode == Opcode.NCASM_RET || opcode == Opcode.NCASM_NOTIFY

    // endregion
    // region Battery (over-ear models report a single battery)

    fun batteryGet(): ByteArray = bytes(Opcode.BATTERY_GET, 0x00)

    /** Type 0x00 `<level> <charging>` or 0x08 `<level> <charging> <threshold>`. */
    fun decodeBattery(p: ByteArray): BatteryStatus? {
        if (p.size < 4) return null
        if (p.u(1) != Subtype.BATTERY_SINGLE && p.u(1) != Subtype.BATTERY_SINGLE_WITH_THRESHOLD) return null
        val level = p.u(2)
        if (level > 100) return null
        return BatteryStatus(level = level, isCharging = p.u(3) == 1)
    }

    fun isBatteryReply(opcode: Int) = opcode == Opcode.BATTERY_RET || opcode == Opcode.BATTERY_NOTIFY

    // endregion
    // region Equalizer

    /** Gain range in device steps for the 10-band XM6 layout (wire value = gain + 6). */
    val BAND_GAIN_RANGE = -6..6
    const val DEFAULT_BAND_COUNT = 10
    private const val BAND_OFFSET = 6

    fun equalizerGet(subtype: Int = Subtype.EQ_PRESET_AND_ERRORCODE): ByteArray = bytes(Opcode.EQ_GET, subtype)

    fun equalizerPresetSet(code: Int, subtype: Int): ByteArray = bytes(Opcode.EQ_SET, subtype, code, 0x00)

    /** Writes a custom curve; the headphones switch to the custom preset implicitly. */
    fun equalizerBandsSet(bands: List<Int>, subtype: Int): ByteArray {
        val out = mutableListOf(Opcode.EQ_SET, subtype, EqualizerPreset.CUSTOM.code, bands.size)
        for (gain in bands) out += gain.coerceIn(BAND_GAIN_RANGE) + BAND_OFFSET
        return bytes(*out.toIntArray())
    }

    fun decodeEqualizer(p: ByteArray): EqualizerState? {
        if (p.size < 4) return null
        val subtype = p.u(1)
        if (subtype != 0x00 && subtype != 0x02 && subtype != 0x04) return null
        val bandCount = p.u(3)
        val bands = if (bandCount > 0 && p.size >= 4 + bandCount) {
            // 6-band replies use offset 10 (classic Clear Bass layout), 10-band use 6.
            val offset = if (bandCount == 6) 10 else BAND_OFFSET
            (0 until bandCount).map { p.u(4 + it) - offset }
        } else {
            emptyList()
        }
        return EqualizerState(presetCode = p.u(2), bands = bands, subtype = subtype)
    }

    fun isEqualizerReply(opcode: Int) = opcode == Opcode.EQ_RET || opcode == Opcode.EQ_NOTIFY

    /** Capability query in English (`lang` 0x01). */
    fun equalizerCapabilityGet(subtype: Int = Subtype.EQ_PRESET_AND_ERRORCODE): ByteArray =
        bytes(Opcode.EQ_CAPABILITY_GET, subtype, 0x01)

    /** `51 <type> <bands> <steps> <presetCount> (<id> <nameLen> <name>)*`; names are often empty. */
    fun decodeEqualizerCapability(p: ByteArray): EqualizerCapability? {
        if (p.size < 5 || p.u(0) != Opcode.EQ_CAPABILITY_RET) return null
        val subtype = p.u(1)
        // Type 0x03 carries an extra step byte before the preset list; not used by the 1000X line.
        if (subtype == 0x03) return null
        val count = p.u(4)
        val presets = mutableListOf<Int>()
        var i = 5
        repeat(count) {
            if (i + 2 > p.size) return null
            presets += p.u(i)
            i += 2 + p.u(i + 1)
        }
        if (i > p.size) return null
        return EqualizerCapability(subtype, bandCount = p.u(2), stepCount = p.u(3), presetCodes = presets)
    }

    // endregion
    // region Listening mode (BGM / Upmix Cinema). Enable flags are inverted: 0x00 = on.

    fun bgmModeGet(subtype: Int = Subtype.BGM_MODE): ByteArray = bytes(Opcode.AUDIO_GET, subtype)

    fun bgmModeSet(enabled: Boolean, roomSize: BgmRoomSize, subtype: Int = Subtype.BGM_MODE): ByteArray =
        bytes(Opcode.AUDIO_SET, subtype, if (enabled) 0x00 else 0x01, roomSize.code)

    fun upmixCinemaGet(): ByteArray = bytes(Opcode.AUDIO_GET, Subtype.UPMIX_CINEMA)

    fun upmixCinemaSet(enabled: Boolean): ByteArray =
        bytes(Opcode.AUDIO_SET, Subtype.UPMIX_CINEMA, if (enabled) 0x00 else 0x01)

    fun decodeBgmMode(p: ByteArray): Pair<Boolean, BgmRoomSize>? {
        if (p.size < 4 || (p.u(1) != 0x09 && p.u(1) != 0x03)) return null
        val room = BgmRoomSize.fromCode(p.u(3)) ?: return null
        return (p.u(2) == 0x00) to room
    }

    fun decodeUpmixCinema(p: ByteArray): Boolean? {
        if (p.size < 3 || p.u(1) != Subtype.UPMIX_CINEMA) return null
        return p.u(2) == 0x00
    }

    fun isAudioParamReply(opcode: Int) = opcode == Opcode.AUDIO_RET || opcode == Opcode.AUDIO_NOTIFY

    // endregion
    // region Multipoint device list and playback source switch (COMMAND_2)

    fun deviceListGet(): ByteArray = bytes(Opcode.PERI_GET, 0x02)

    fun sourceSwitchSet(macAddress: String): ByteArray? {
        val mac = macAddress.toByteArray(Charsets.UTF_8)
        if (mac.size != 17) return null
        return bytes(Opcode.PERI_SET_EXTENDED, 0x01) + mac
    }

    /**
     * `[opcode, 0x02, count, entries..., playbackStatus]`; each entry is a 17-char ASCII MAC,
     * connected status, 3-byte class of device (type 0x02 only), name length and UTF-8 name.
     */
    fun decodeDeviceList(p: ByteArray): List<MultipointDevice>? {
        if (p.size < 4 || (p.u(1) != 0x02 && p.u(1) != 0x00)) return null
        val withClassOfDevice = p.u(1) == 0x02
        val count = p.u(2)
        if (count <= 0 || count >= 16) return emptyList()
        val playbackStatus = p.u(p.size - 1)

        val devices = mutableListOf<MultipointDevice>()
        var i = 3
        repeat(count) {
            if (i + 17 > p.size) return null
            val mac = String(p.copyOfRange(i, i + 17), Charsets.UTF_8)
            i += 17
            if (i >= p.size) return null
            val status = p.u(i)
            i += 1
            if (withClassOfDevice) i += 3
            if (i >= p.size) return null
            val nameLength = p.u(i)
            i += 1
            if (i + nameLength > p.size) return null
            val name = String(p.copyOfRange(i, i + nameLength), Charsets.UTF_8)
            i += nameLength
            devices += MultipointDevice(mac, name, status, isPlayback = status != 0 && status == playbackStatus)
        }
        return devices
    }

    fun isDeviceListReply(opcode: Int) = opcode == Opcode.PERI_RET || opcode == Opcode.PERI_NOTIFY

    // endregion
    // region Speak-to-Chat (enable flag inverted on v2: 0x00 = enabled)

    fun speakToChatEnabledGet(): ByteArray = bytes(Opcode.SYSTEM_GET, Subtype.SPEAK_TO_CHAT_ENABLED)

    fun speakToChatEnabledSet(enabled: Boolean): ByteArray =
        bytes(Opcode.SYSTEM_SET, Subtype.SPEAK_TO_CHAT_ENABLED, if (enabled) 0x00 else 0x01, 0x01)

    fun decodeSpeakToChatEnabled(p: ByteArray): Boolean? {
        if (p.size != 4 || p.u(1) != Subtype.SPEAK_TO_CHAT_ENABLED) return null
        val disabled = boolFromByte(p.u(2)) ?: return null
        return !disabled
    }

    fun isSpeakToChatEnabledReply(opcode: Int) = opcode == Opcode.SYSTEM_RET || opcode == Opcode.SYSTEM_NOTIFY

    fun speakToChatConfigGet(): ByteArray = bytes(Opcode.SYSTEM_EXT_GET, Subtype.SPEAK_TO_CHAT_CONFIG)

    fun speakToChatConfigSet(config: SpeakToChatConfig): ByteArray =
        bytes(Opcode.SYSTEM_EXT_SET, Subtype.SPEAK_TO_CHAT_CONFIG, config.sensitivity.code, config.timeout.code)

    fun decodeSpeakToChatConfig(p: ByteArray): SpeakToChatConfig? {
        if (p.size != 4 || p.u(1) != Subtype.SPEAK_TO_CHAT_CONFIG) return null
        val sensitivity = SpeakToChatSensitivity.fromCode(p.u(2)) ?: return null
        val timeout = SpeakToChatTimeout.fromCode(p.u(3)) ?: return null
        return SpeakToChatConfig(sensitivity, timeout)
    }

    fun isSpeakToChatConfigReply(opcode: Int) = opcode == Opcode.SYSTEM_EXT_RET || opcode == Opcode.SYSTEM_EXT_NOTIFY

    // endregion
    // region Automatic power off (POWER family, subtype 0x05)

    fun automaticPowerOffGet(): ByteArray = bytes(Opcode.POWER_GET, Subtype.AUTOMATIC_POWER_OFF)

    fun automaticPowerOffSet(mode: AutomaticPowerOffMode): ByteArray =
        bytes(Opcode.POWER_SET, Subtype.AUTOMATIC_POWER_OFF, mode.code, 0x00)

    fun decodeAutomaticPowerOff(p: ByteArray): AutomaticPowerOffMode? {
        if (p.size != 4 || p.u(1) != Subtype.AUTOMATIC_POWER_OFF) return null
        return AutomaticPowerOffMode.fromCode(p.u(2)) // p[3] is "last selected element"
    }

    fun isAutomaticPowerOffReply(opcode: Int) = opcode == Opcode.POWER_RET || opcode == Opcode.POWER_NOTIFY

    // endregion
    // region Pause when taken off (XM6 answers from the SYSTEM family, inverted flag)

    fun pauseWhenTakenOffGet(): ByteArray = bytes(Opcode.SYSTEM_GET, Subtype.PAUSE_WHEN_TAKEN_OFF)

    fun pauseWhenTakenOffSet(enabled: Boolean): ByteArray =
        bytes(Opcode.SYSTEM_SET, Subtype.PAUSE_WHEN_TAKEN_OFF, if (enabled) 0x00 else 0x01)

    fun decodePauseWhenTakenOff(p: ByteArray): Boolean? {
        if (p.size != 3 || p.u(1) != Subtype.PAUSE_WHEN_TAKEN_OFF) return null
        val disabled = boolFromByte(p.u(2)) ?: return null
        return !disabled
    }

    fun isPauseWhenTakenOffReply(opcode: Int) =
        opcode == Opcode.POWER_RET || opcode == Opcode.POWER_NOTIFY ||
            opcode == Opcode.SYSTEM_RET || opcode == Opcode.SYSTEM_NOTIFY

    // endregion
}
