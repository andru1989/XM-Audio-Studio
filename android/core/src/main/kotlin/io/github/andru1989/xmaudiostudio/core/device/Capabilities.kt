package io.github.andru1989.xmaudiostudio.core.device

/**
 * How much we trust a feature on a given model. Mirrors the status labels required
 * by docs/compatibility/FEATURE-STATUS.md; the UI only enables [isUsable] features.
 */
enum class SupportLevel(val id: String) {
    /** Implemented and confirmed on real hardware (by us or by the upstream author, with a capture). */
    VERIFIED_HARDWARE("verified-hardware"),
    /** Implemented and covered by simulated/fixture tests; not yet confirmed on hardware by us. */
    VERIFIED_SIMULATION("verified-simulation"),
    /** Implemented from protocol references, pending a real-hardware test. */
    PENDING_HARDWARE("pending-hardware"),
    EXPERIMENTAL("experimental"),
    /** Planned, not implemented. */
    PLANNED("planned"),
    UNSUPPORTED("unsupported");

    val isUsable: Boolean get() = this == VERIFIED_HARDWARE || this == VERIFIED_SIMULATION || this == PENDING_HARDWARE || this == EXPERIMENTAL

    companion object {
        fun fromId(id: String) = entries.first { it.id == id }
    }
}

enum class Feature(val id: String) {
    BATTERY("battery"),
    NOISE_CANCELLING("noiseCancelling"),
    AMBIENT_SOUND("ambientSound"),
    AMBIENT_LEVEL("ambientLevel"),
    FOCUS_ON_VOICE("focusOnVoice"),
    LISTENING_MODE("listeningMode"),
    SPEAK_TO_CHAT("speakToChat"),
    WEAR_DETECTION("wearDetection"),
    AUTO_POWER_OFF("autoPowerOff"),
    EQ_PRESETS("eqPresets"),
    EQ_CUSTOM_BANDS("eqCustomBands"),
    MULTIPOINT_LIST("multipointList"),
    PLAYBACK_SOURCE_SWITCH("playbackSourceSwitch");

    companion object {
        fun fromId(id: String) = entries.first { it.id == id }
    }
}

/**
 * The headphones' built-in graphic EQ as far as the protocol is concerned.
 * [bandFrequenciesHz] are used for drawing and for the AutoEQ fit; [frequenciesVerified]
 * says whether they were confirmed against the official app/hardware or are an assumption.
 */
data class EqualizerSpec(
    val bandCount: Int,
    val bandFrequenciesHz: List<Double>,
    val frequenciesVerified: Boolean,
    val minStep: Int,
    val maxStep: Int,
    /** Wire value = step + offset. */
    val wireOffset: Int,
    /** dB per step as displayed; unverified for XM6. */
    val stepDb: Double,
    /** Separate Clear Bass control (XM3-XM5 style), in steps. */
    val clearBassRange: IntRange? = null,
    val presets: List<EqPresetSpec>,
)

data class EqPresetSpec(val code: Int, val name: String)

data class DeviceCapabilities(
    val model: HeadphoneModel,
    val features: Map<Feature, SupportLevel>,
    val equalizer: EqualizerSpec?,
    val ambientLevelRange: IntRange,
) {
    fun level(feature: Feature): SupportLevel = features[feature] ?: SupportLevel.UNSUPPORTED
    fun supports(feature: Feature): Boolean = level(feature).isUsable
}
