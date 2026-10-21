package io.github.andru1989.xmaudiostudio.core.device

import io.github.andru1989.xmaudiostudio.core.device.Feature.AMBIENT_LEVEL
import io.github.andru1989.xmaudiostudio.core.device.Feature.AMBIENT_SOUND
import io.github.andru1989.xmaudiostudio.core.device.Feature.AUTO_POWER_OFF
import io.github.andru1989.xmaudiostudio.core.device.Feature.BATTERY
import io.github.andru1989.xmaudiostudio.core.device.Feature.EQ_CUSTOM_BANDS
import io.github.andru1989.xmaudiostudio.core.device.Feature.EQ_PRESETS
import io.github.andru1989.xmaudiostudio.core.device.Feature.FOCUS_ON_VOICE
import io.github.andru1989.xmaudiostudio.core.device.Feature.LISTENING_MODE
import io.github.andru1989.xmaudiostudio.core.device.Feature.MULTIPOINT_LIST
import io.github.andru1989.xmaudiostudio.core.device.Feature.NOISE_CANCELLING
import io.github.andru1989.xmaudiostudio.core.device.Feature.PLAYBACK_SOURCE_SWITCH
import io.github.andru1989.xmaudiostudio.core.device.Feature.SPEAK_TO_CHAT
import io.github.andru1989.xmaudiostudio.core.device.Feature.WEAR_DETECTION
import io.github.andru1989.xmaudiostudio.core.device.SupportLevel.EXPERIMENTAL
import io.github.andru1989.xmaudiostudio.core.device.SupportLevel.PENDING_HARDWARE
import io.github.andru1989.xmaudiostudio.core.device.SupportLevel.PLANNED
import io.github.andru1989.xmaudiostudio.core.device.SupportLevel.VERIFIED_HARDWARE

/**
 * Per-model capability matrix. Must match shared/protocol-spec/models.json exactly
 * (enforced by CatalogConformanceTest here and by the Swift suite).
 *
 * The level records protocol evidence for that model: VERIFIED_HARDWARE means the byte
 * layout was confirmed by a live capture on that model; PENDING_HARDWARE means it is
 * implemented from references (MIT clients' hardware notes, captures of the same layout);
 * EXPERIMENTAL means implemented by inference from a sibling model with no evidence for this
 * one; PLANNED means the model has the feature but its adapter does not implement it.
 * Evidence per model: docs/compatibility/OTHER-MODELS.md.
 */
object DeviceCatalog {

    private val classicPresets = listOf(
        EqPresetSpec(0x00, "Off"),
        EqPresetSpec(0x10, "Bright"),
        EqPresetSpec(0x11, "Excited"),
        EqPresetSpec(0x12, "Mellow"),
        EqPresetSpec(0x13, "Relaxed"),
        EqPresetSpec(0x14, "Vocal"),
        EqPresetSpec(0x15, "Treble Boost"),
        EqPresetSpec(0x16, "Bass Boost"),
        EqPresetSpec(0x17, "Speech"),
        EqPresetSpec(0xa0, "Manual"),
        EqPresetSpec(0xa1, "Custom 1"),
        EqPresetSpec(0xa2, "Custom 2"),
    )

    /** 5 bands + Clear Bass, ±10 steps (XM3-XM5 generation). */
    private val fiveBandEq = EqualizerSpec(
        bandCount = 5,
        bandFrequenciesHz = listOf(400.0, 1_000.0, 2_500.0, 6_300.0, 16_000.0),
        frequenciesVerified = false,
        minStep = -10,
        maxStep = 10,
        wireOffset = 10,
        stepDb = 1.0,
        clearBassRange = -10..10,
        presets = classicPresets,
    )

    /**
     * The WH-1000XM3 reported these exact centres in its own EQ extended info
     * (`5B 01 06 10 0000 01 0190 01 03E8 01 09C4 01 189C 01 3E80`, mos9527 fw 4.5.2 capture).
     */
    private val xm3Equalizer = fiveBandEq.copy(frequenciesVerified = true)

    private fun levels(level: SupportLevel, vararg features: Feature) = features.associateWith { level }

    val xm6Equalizer = EqualizerSpec(
        bandCount = 10,
        bandFrequenciesHz = listOf(31.0, 63.0, 125.0, 250.0, 500.0, 1_000.0, 2_000.0, 4_000.0, 8_000.0, 16_000.0),
        frequenciesVerified = false,
        minStep = -6,
        maxStep = 6,
        wireOffset = 6,
        stepDb = 1.0,
        presets = listOf(
            EqPresetSpec(0x00, "Off"),
            EqPresetSpec(0x30, "Heavy"),
            EqPresetSpec(0x31, "Clear"),
            EqPresetSpec(0x32, "Hard"),
            EqPresetSpec(0x33, "Soft"),
            EqPresetSpec(0xa0, "Custom"),
        ),
    )

    private fun planned(vararg features: Feature) = levels(PLANNED, *features)

    val models: Map<HeadphoneModel, DeviceCapabilities> = mapOf(
        HeadphoneModel.WH_1000XM6 to DeviceCapabilities(
            model = HeadphoneModel.WH_1000XM6,
            features = mapOf(
                BATTERY to VERIFIED_HARDWARE,
                NOISE_CANCELLING to VERIFIED_HARDWARE,
                AMBIENT_SOUND to VERIFIED_HARDWARE,
                AMBIENT_LEVEL to VERIFIED_HARDWARE,
                FOCUS_ON_VOICE to PENDING_HARDWARE,
                LISTENING_MODE to PENDING_HARDWARE,
                SPEAK_TO_CHAT to PENDING_HARDWARE,
                WEAR_DETECTION to VERIFIED_HARDWARE,
                AUTO_POWER_OFF to VERIFIED_HARDWARE,
                EQ_PRESETS to VERIFIED_HARDWARE,
                EQ_CUSTOM_BANDS to VERIFIED_HARDWARE,
                MULTIPOINT_LIST to VERIFIED_HARDWARE,
                PLAYBACK_SOURCE_SWITCH to PENDING_HARDWARE,
            ),
            equalizer = xm6Equalizer,
            ambientLevelRange = 1..20,
        ),
        // Xm5Adapter. Preset codes are assumed from the v1 generation (unverified on the XM5).
        HeadphoneModel.WH_1000XM5 to DeviceCapabilities(
            model = HeadphoneModel.WH_1000XM5,
            features = levels(
                PENDING_HARDWARE,
                BATTERY, NOISE_CANCELLING, AMBIENT_SOUND, AMBIENT_LEVEL, FOCUS_ON_VOICE, SPEAK_TO_CHAT, EQ_CUSTOM_BANDS,
            ) + levels(EXPERIMENTAL, EQ_PRESETS) + planned(WEAR_DETECTION, AUTO_POWER_OFF),
            equalizer = fiveBandEq,
            ambientLevelRange = 1..20,
        ),
        // MdrV1Adapter. Speak-to-chat on/off is reported working on an XM4; its options read is not.
        HeadphoneModel.WH_1000XM4 to DeviceCapabilities(
            model = HeadphoneModel.WH_1000XM4,
            features = levels(
                PENDING_HARDWARE,
                BATTERY, NOISE_CANCELLING, AMBIENT_SOUND, AMBIENT_LEVEL, FOCUS_ON_VOICE, EQ_PRESETS, EQ_CUSTOM_BANDS,
            ) + levels(EXPERIMENTAL, SPEAK_TO_CHAT) + planned(WEAR_DETECTION, AUTO_POWER_OFF),
            equalizer = fiveBandEq,
            ambientLevelRange = 1..20,
        ),
        // MdrV1Adapter. EQ changes raised a connection-mode alert on the captured XM3, which
        // this app does not answer yet, hence experimental.
        HeadphoneModel.WH_1000XM3 to DeviceCapabilities(
            model = HeadphoneModel.WH_1000XM3,
            features = levels(
                PENDING_HARDWARE,
                BATTERY, NOISE_CANCELLING, AMBIENT_SOUND, AMBIENT_LEVEL, FOCUS_ON_VOICE,
            ) + levels(EXPERIMENTAL, EQ_PRESETS, EQ_CUSTOM_BANDS) + planned(AUTO_POWER_OFF),
            equalizer = xm3Equalizer,
            ambientLevelRange = 1..20,
        ),
        // MdrV1Adapter by inference only: no public XM2 capture exists.
        HeadphoneModel.WH_1000XM2 to DeviceCapabilities(
            model = HeadphoneModel.WH_1000XM2,
            features = levels(
                EXPERIMENTAL,
                BATTERY, NOISE_CANCELLING, AMBIENT_SOUND, AMBIENT_LEVEL, FOCUS_ON_VOICE, EQ_PRESETS, EQ_CUSTOM_BANDS,
            ),
            equalizer = fiveBandEq,
            ambientLevelRange = 1..20,
        ),
        HeadphoneModel.MDR_1000X to DeviceCapabilities(
            model = HeadphoneModel.MDR_1000X,
            features = planned(BATTERY, NOISE_CANCELLING, AMBIENT_SOUND),
            equalizer = null,
            ambientLevelRange = 1..20,
        ),
    )

    fun capabilities(model: HeadphoneModel): DeviceCapabilities = models.getValue(model)
}
