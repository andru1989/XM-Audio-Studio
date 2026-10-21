package io.github.andru1989.xmaudiostudio.core.presets

import io.github.andru1989.xmaudiostudio.core.device.DeviceCapabilities
import io.github.andru1989.xmaudiostudio.core.device.DeviceCommand
import io.github.andru1989.xmaudiostudio.core.device.Feature
import io.github.andru1989.xmaudiostudio.core.device.HeadphoneState
import io.github.andru1989.xmaudiostudio.core.model.AmbientSoundMode
import io.github.andru1989.xmaudiostudio.core.model.AutomaticPowerOffMode
import io.github.andru1989.xmaudiostudio.core.model.BgmRoomSize
import io.github.andru1989.xmaudiostudio.core.model.ListeningMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * "Escenas": a profile (format version 2) may carry the headphone settings next to its EQ.
 * Same rules as macos/Sources/AudioStudioKit/Scenes.swift; both are checked against
 * shared/preset-schema/examples. See docs/architecture/SCENES.md.
 */

@Serializable
enum class SceneNoiseMode(val mode: AmbientSoundMode) {
    @SerialName("noiseCancelling") NOISE_CANCELLING(AmbientSoundMode.NOISE_CANCELLING),
    @SerialName("ambientSound") AMBIENT_SOUND(AmbientSoundMode.AMBIENT_SOUND),
    @SerialName("off") OFF(AmbientSoundMode.OFF);

    companion object {
        fun of(mode: AmbientSoundMode) = entries.first { it.mode == mode }
    }
}

@Serializable
enum class SceneListeningMode(val mode: ListeningMode) {
    @SerialName("standard") STANDARD(ListeningMode.STANDARD),
    @SerialName("backgroundMusic") BACKGROUND_MUSIC(ListeningMode.BACKGROUND_MUSIC),
    @SerialName("cinema") CINEMA(ListeningMode.CINEMA);

    companion object {
        fun of(mode: ListeningMode) = entries.first { it.mode == mode }
    }
}

@Serializable
enum class SceneRoomSize(val size: BgmRoomSize) {
    @SerialName("small") SMALL(BgmRoomSize.SMALL),
    @SerialName("middle") MIDDLE(BgmRoomSize.MIDDLE),
    @SerialName("large") LARGE(BgmRoomSize.LARGE);

    companion object {
        fun of(size: BgmRoomSize) = entries.first { it.size == size }
    }
}

@Serializable
enum class SceneAutoPowerOff(val mode: AutomaticPowerOffMode) {
    @SerialName("whenTakenOff") WHEN_TAKEN_OFF(AutomaticPowerOffMode.WHEN_TAKEN_OFF),
    @SerialName("off") OFF(AutomaticPowerOffMode.OFF);

    companion object {
        fun of(mode: AutomaticPowerOffMode) = entries.first { it.mode == mode }
    }
}

/** [level] and [focusOnVoice] are optional; applying keeps what the headphones have for a missing one. */
@Serializable
data class SceneNoiseControl(val mode: SceneNoiseMode, val level: Int? = null, val focusOnVoice: Boolean? = null)

/** Headphone settings beyond the EQ. Every field is optional: only what the headphones reported is captured. */
@Serializable
data class HeadphoneScene(
    val noiseControl: SceneNoiseControl? = null,
    val listeningMode: SceneListeningMode? = null,
    val bgmRoomSize: SceneRoomSize? = null,
    val speakToChat: Boolean? = null,
    val pauseWhenTakenOff: Boolean? = null,
    val autoPowerOff: SceneAutoPowerOff? = null,
) {
    val isEmpty: Boolean
        get() = noiseControl == null && listeningMode == null && bgmRoomSize == null &&
            speakToChat == null && pauseWhenTakenOff == null && autoPowerOff == null
}

/** One setting a scene would change, for the confirmation shown before applying it. */
sealed interface SceneChange {
    data class NoiseMode(val from: AmbientSoundMode?, val to: AmbientSoundMode) : SceneChange
    data class AmbientLevel(val from: Int?, val to: Int) : SceneChange
    data class FocusOnVoice(val from: Boolean?, val to: Boolean) : SceneChange
    data class Listening(val from: ListeningMode?, val to: ListeningMode) : SceneChange
    data class RoomSize(val from: BgmRoomSize?, val to: BgmRoomSize) : SceneChange
    data class SpeakToChat(val from: Boolean?, val to: Boolean) : SceneChange
    data class PauseWhenTakenOff(val from: Boolean?, val to: Boolean) : SceneChange
    data class AutoPowerOff(val from: AutomaticPowerOffMode?, val to: AutomaticPowerOffMode) : SceneChange
}

object HeadphoneScenes {
    /** Level written when neither the scene nor the headphones give a usable one (same as the dashboard). */
    const val FALLBACK_AMBIENT_LEVEL = 15

    /**
     * The scene the headphones report now, limited to what [capabilities] supports.
     * Null when nothing usable was reported.
     */
    fun capture(state: HeadphoneState, capabilities: DeviceCapabilities): HeadphoneScene? {
        val noise = state.ambientSound
            ?.takeIf { capabilities.supports(Feature.NOISE_CANCELLING) }
            ?.takeIf { it.mode != AmbientSoundMode.AMBIENT_SOUND || capabilities.supports(Feature.AMBIENT_SOUND) }
            ?.let { a ->
                SceneNoiseControl(
                    mode = SceneNoiseMode.of(a.mode),
                    // MDR v1 reports 0 while noise cancelling is on: not a level that can be written back.
                    level = a.level.takeIf { capabilities.supports(Feature.AMBIENT_LEVEL) && it in capabilities.ambientLevelRange },
                    focusOnVoice = a.focusOnVoice.takeIf { capabilities.supports(Feature.FOCUS_ON_VOICE) },
                )
            }
        val listening = capabilities.supports(Feature.LISTENING_MODE)
        val scene = HeadphoneScene(
            noiseControl = noise,
            listeningMode = state.listeningMode?.takeIf { listening }?.let(SceneListeningMode::of),
            bgmRoomSize = state.bgmRoomSize?.takeIf { listening }?.let(SceneRoomSize::of),
            speakToChat = state.speakToChatEnabled?.takeIf { capabilities.supports(Feature.SPEAK_TO_CHAT) },
            pauseWhenTakenOff = state.pauseWhenTakenOff?.takeIf { capabilities.supports(Feature.WEAR_DETECTION) },
            autoPowerOff = state.automaticPowerOff?.takeIf { capabilities.supports(Feature.AUTO_POWER_OFF) }?.let(SceneAutoPowerOff::of),
        )
        return scene.takeUnless { it.isEmpty }
    }

    /** Null when valid, otherwise the reason (used by [ProfileCodec.validate]). */
    fun validate(scene: HeadphoneScene, capabilities: DeviceCapabilities): String? {
        val model = capabilities.model.displayName
        if (scene.isEmpty) return "scene is empty"
        scene.noiseControl?.let { nc ->
            if (!capabilities.supports(Feature.NOISE_CANCELLING)) return "scene.noiseControl: $model has no noise control"
            if (nc.mode == SceneNoiseMode.AMBIENT_SOUND && !capabilities.supports(Feature.AMBIENT_SOUND)) {
                return "scene.noiseControl: $model has no ambient sound mode"
            }
            nc.level?.let { level ->
                if (!capabilities.supports(Feature.AMBIENT_LEVEL)) return "scene.noiseControl.level: $model has no ambient level"
                val range = capabilities.ambientLevelRange
                if (level !in range) return "scene.noiseControl.level must be within ${range.first}..${range.last}"
            }
            if (nc.focusOnVoice != null && !capabilities.supports(Feature.FOCUS_ON_VOICE)) {
                return "scene.noiseControl.focusOnVoice: $model has no focus on voice"
            }
        }
        if (scene.listeningMode != null && !capabilities.supports(Feature.LISTENING_MODE)) return "scene.listeningMode: $model has no listening mode"
        if (scene.bgmRoomSize != null && !capabilities.supports(Feature.LISTENING_MODE)) return "scene.bgmRoomSize: $model has no listening mode"
        if (scene.speakToChat != null && !capabilities.supports(Feature.SPEAK_TO_CHAT)) return "scene.speakToChat: $model has no speak-to-chat"
        if (scene.pauseWhenTakenOff != null && !capabilities.supports(Feature.WEAR_DETECTION)) return "scene.pauseWhenTakenOff: $model has no wear detection"
        if (scene.autoPowerOff != null && !capabilities.supports(Feature.AUTO_POWER_OFF)) return "scene.autoPowerOff: $model has no automatic power off"
        return null
    }

    /** The intent that puts [setting] on the headphones. Clear Bass defaults to 0 where the model has it. */
    fun eqCommand(setting: DeviceEqSetting, capabilities: DeviceCapabilities): DeviceCommand? {
        setting.presetCode?.let { return DeviceCommand.SetEqualizerPreset(it) }
        val steps = setting.steps ?: return null
        val neutralClearBass = capabilities.equalizer?.clearBassRange?.let { 0 }
        return DeviceCommand.SetEqualizerBands(steps, clearBass = setting.clearBass ?: neutralClearBass)
    }

    /**
     * The intents that apply [scene] (and the profile's [eq], if given), in the order they must be sent.
     * The session encodes each one after reading the real state, so nothing stale is written.
     *
     * - The BGM room goes before the listening mode: once the room is acknowledged, the listening
     *   mode write carries it (the room byte travels with the BGM on/off flag).
     * - The firmware turns the EQ off while Background Music or Cinema is on, so the EQ is written
     *   while the mode is Standard: after switching to Standard, before switching away from it.
     *
     * [current] fills the parts of the noise-control write a scene left out.
     */
    fun commands(
        scene: HeadphoneScene,
        current: HeadphoneState,
        capabilities: DeviceCapabilities,
        eq: DeviceCommand? = null,
    ): List<DeviceCommand> = buildList {
        scene.noiseControl?.takeIf { capabilities.supports(Feature.NOISE_CANCELLING) }?.let { nc ->
            val reported = current.ambientSound
            add(
                DeviceCommand.SetAmbientSound(
                    mode = nc.mode.mode,
                    focusOnVoice = nc.focusOnVoice ?: reported?.focusOnVoice ?: false,
                    level = nc.level ?: reported?.level?.takeIf { it in capabilities.ambientLevelRange } ?: FALLBACK_AMBIENT_LEVEL,
                ),
            )
        }
        val listening = capabilities.supports(Feature.LISTENING_MODE)
        scene.bgmRoomSize?.takeIf { listening }?.let { add(DeviceCommand.SetBgmRoomSize(it.size)) }
        val mode = scene.listeningMode?.takeIf { listening }?.mode
        if (mode == ListeningMode.STANDARD) add(DeviceCommand.SetListeningMode(mode))
        eq?.let(::add)
        if (mode != null && mode != ListeningMode.STANDARD) add(DeviceCommand.SetListeningMode(mode))
        scene.speakToChat?.takeIf { capabilities.supports(Feature.SPEAK_TO_CHAT) }?.let { add(DeviceCommand.SetSpeakToChat(it)) }
        scene.pauseWhenTakenOff?.takeIf { capabilities.supports(Feature.WEAR_DETECTION) }?.let { add(DeviceCommand.SetPauseWhenTakenOff(it)) }
        scene.autoPowerOff?.takeIf { capabilities.supports(Feature.AUTO_POWER_OFF) }?.let { add(DeviceCommand.SetAutomaticPowerOff(it.mode)) }
    }

    /**
     * What applying [scene] changes compared with [current] (the EQ is listed by the caller).
     * The ambient level and focus on voice are listed only when the result is ambient sound, and
     * the room only when the result is Background Music: elsewhere they are written but not heard.
     */
    fun changes(scene: HeadphoneScene, current: HeadphoneState, capabilities: DeviceCapabilities): List<SceneChange> {
        val commands = commands(scene, current, capabilities)
        val ambient = commands.filterIsInstance<DeviceCommand.SetAmbientSound>().firstOrNull()
        val reported = current.ambientSound
        val listening = scene.listeningMode?.takeIf { capabilities.supports(Feature.LISTENING_MODE) }?.mode
        val resultingListening = listening ?: current.listeningMode
        return buildList {
            if (ambient != null) {
                if (ambient.mode != reported?.mode) add(SceneChange.NoiseMode(reported?.mode, ambient.mode))
                if (ambient.mode == AmbientSoundMode.AMBIENT_SOUND) {
                    if (scene.noiseControl?.level != null && ambient.level != reported?.level) add(SceneChange.AmbientLevel(reported?.level, ambient.level))
                    if (scene.noiseControl?.focusOnVoice != null && ambient.focusOnVoice != reported?.focusOnVoice) {
                        add(SceneChange.FocusOnVoice(reported?.focusOnVoice, ambient.focusOnVoice))
                    }
                }
            }
            if (listening != null && listening != current.listeningMode) add(SceneChange.Listening(current.listeningMode, listening))
            commands.filterIsInstance<DeviceCommand.SetBgmRoomSize>().firstOrNull()?.let {
                if (resultingListening == ListeningMode.BACKGROUND_MUSIC && it.roomSize != current.bgmRoomSize) add(SceneChange.RoomSize(current.bgmRoomSize, it.roomSize))
            }
            commands.filterIsInstance<DeviceCommand.SetSpeakToChat>().firstOrNull()?.let {
                if (it.enabled != current.speakToChatEnabled) add(SceneChange.SpeakToChat(current.speakToChatEnabled, it.enabled))
            }
            commands.filterIsInstance<DeviceCommand.SetPauseWhenTakenOff>().firstOrNull()?.let {
                if (it.enabled != current.pauseWhenTakenOff) add(SceneChange.PauseWhenTakenOff(current.pauseWhenTakenOff, it.enabled))
            }
            commands.filterIsInstance<DeviceCommand.SetAutomaticPowerOff>().firstOrNull()?.let {
                if (it.mode != current.automaticPowerOff) add(SceneChange.AutoPowerOff(current.automaticPowerOff, it.mode))
            }
        }
    }

    /** True when the scene leaves the headphones in a listening mode that turns their EQ off. */
    fun leavesEqInactive(scene: HeadphoneScene, current: HeadphoneState): Boolean {
        val result = scene.listeningMode?.mode ?: current.listeningMode
        return result != null && result != ListeningMode.STANDARD
    }
}
