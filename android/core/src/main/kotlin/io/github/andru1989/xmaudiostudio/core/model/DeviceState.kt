package io.github.andru1989.xmaudiostudio.core.model

enum class AmbientSoundMode { OFF, NOISE_CANCELLING, AMBIENT_SOUND }

data class AmbientSoundState(
    val mode: AmbientSoundMode = AmbientSoundMode.NOISE_CANCELLING,
    val focusOnVoice: Boolean = false,
    /** Raw reported level 0..20; writes use 1..20. Meaningful only in ambient mode. */
    val level: Int = 15,
    /**
     * Inquired-type byte the device reported. 0x15/0x17/0x22 are echoed on writes;
     * XM6 firmware 3.x reports real state on 0x19 and is written back as 0x17.
     */
    val subtype: Int = 0x15,
    /** Whether the reply carried the extra wind-noise byte (subtype 0x17, 8-byte payload). */
    val hasWindNoiseByte: Boolean = false,
    /** False for XM6 firmware 3.0.0's zero-update 0x17 placeholder replies. */
    val isUpdate: Boolean = true,
    /** The two trailing bytes of a 0x19 report; meaning unknown, kept for diagnostics. */
    val trailingBytes: List<Int> = emptyList(),
) {
    init {
        require(level in 0..20) { "Ambient level out of range: $level" }
    }
}

data class BatteryStatus(val level: Int, val isCharging: Boolean)

enum class ListeningMode { STANDARD, BACKGROUND_MUSIC, CINEMA }

enum class BgmRoomSize(val code: Int) {
    SMALL(0x00), MIDDLE(0x01), LARGE(0x02);

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code }
    }
}

enum class SpeakToChatSensitivity(val code: Int) {
    AUTO(0x00), HIGH(0x01), LOW(0x02);

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code }
    }
}

enum class SpeakToChatTimeout(val code: Int) {
    SHORT(0x00), STANDARD(0x01), LONG(0x02), OFF(0x03);

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code }
    }
}

data class SpeakToChatConfig(
    val sensitivity: SpeakToChatSensitivity,
    val timeout: SpeakToChatTimeout,
    /**
     * MDR v1 (WH-1000XM4) carries a "voice focus" flag in the same message; null on v2, whose
     * message has no such byte. Kept so that a write never clobbers the value read from the device.
     */
    val voiceFocus: Boolean? = null,
)

/** XM6 accepts only these two auto-power-off values (verified upstream by probe). */
enum class AutomaticPowerOffMode(val code: Int) {
    WHEN_TAKEN_OFF(0x10), OFF(0x11);

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code }
    }
}

/** XM6 firmware 3.x preset codes; other codes are kept raw in [EqualizerState.presetCode]. */
enum class EqualizerPreset(val code: Int) {
    OFF(0x00), HEAVY(0x30), CLEAR(0x31), HARD(0x32), SOFT(0x33), CUSTOM(0xa0);

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code }
    }
}

data class EqualizerState(
    val presetCode: Int,
    /** Band gains in device steps, low frequency first, wire offset already removed. */
    val bands: List<Int> = emptyList(),
    /** EQEBB inquired type the device used (XM6: 0x04, XM5: 0x00, MDR v1: 0x01); echoed on writes. */
    val subtype: Int = 0x04,
    /**
     * Clear Bass in device steps (offset removed) on the 5-band models (XM3-XM5), where it is
     * the first of the six band bytes. Null on models without it (XM6).
     */
    val clearBass: Int? = null,
) {
    val preset: EqualizerPreset? get() = EqualizerPreset.fromCode(presetCode)
}

data class MultipointDevice(
    val macAddress: String,
    val name: String,
    val connectedStatus: Int,
    val isPlayback: Boolean,
) {
    val isConnected: Boolean get() = connectedStatus != 0
}

enum class ProtocolVersion { V1, V2, UNKNOWN }

/** What the headphones report about themselves (04 01 / 04 02). */
data class DeviceInfo(val modelName: String? = null, val firmwareVersion: String? = null)

/** EQ capability reply (50 <type> 01): lets the device confirm band and step counts. */
data class EqualizerCapability(val subtype: Int, val bandCount: Int, val stepCount: Int, val presetCodes: List<Int>)
