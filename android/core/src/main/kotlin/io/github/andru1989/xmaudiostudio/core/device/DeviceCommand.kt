package io.github.andru1989.xmaudiostudio.core.device

import io.github.andru1989.xmaudiostudio.core.model.AmbientSoundMode
import io.github.andru1989.xmaudiostudio.core.model.AutomaticPowerOffMode
import io.github.andru1989.xmaudiostudio.core.model.BgmRoomSize
import io.github.andru1989.xmaudiostudio.core.model.ConnectionPriority
import io.github.andru1989.xmaudiostudio.core.model.ListeningMode
import io.github.andru1989.xmaudiostudio.core.model.PlaybackAction
import io.github.andru1989.xmaudiostudio.core.model.SpeakToChatConfig
import io.github.andru1989.xmaudiostudio.core.protocol.MessageType

/**
 * A user intent, independent of model and wire format. Intents (not bytes) are queued
 * while the control channel is being acquired, and are encoded only after the session
 * has read the device's real state, so a stale UI can never write stale companion values.
 */
sealed interface DeviceCommand {
    /** Commands with the same key replace each other while unsent (latest intent wins). */
    val coalesceKey: String

    data class SetAmbientSound(val mode: AmbientSoundMode, val focusOnVoice: Boolean, val level: Int) : DeviceCommand {
        override val coalesceKey get() = "ambient"
    }

    data class SetEqualizerPreset(val code: Int) : DeviceCommand {
        override val coalesceKey get() = "eq"
    }

    /**
     * Custom curve in device steps, low band first. [clearBass] (steps) only applies to models
     * whose [EqualizerSpec.clearBassRange] is set; null keeps the value the device reported.
     */
    data class SetEqualizerBands(val steps: List<Int>, val clearBass: Int? = null) : DeviceCommand {
        override val coalesceKey get() = "eq"
    }

    data class SetListeningMode(val mode: ListeningMode) : DeviceCommand {
        override val coalesceKey get() = "listeningMode"
    }

    data class SetBgmRoomSize(val roomSize: BgmRoomSize) : DeviceCommand {
        override val coalesceKey get() = "bgmRoom"
    }

    data class SetSpeakToChat(val enabled: Boolean) : DeviceCommand {
        override val coalesceKey get() = "stc"
    }

    data class SetSpeakToChatConfig(val config: SpeakToChatConfig) : DeviceCommand {
        override val coalesceKey get() = "stcConfig"
    }

    data class SetAutomaticPowerOff(val mode: AutomaticPowerOffMode) : DeviceCommand {
        override val coalesceKey get() = "apo"
    }

    data class SetPauseWhenTakenOff(val enabled: Boolean) : DeviceCommand {
        override val coalesceKey get() = "pause"
    }

    /**
     * Bluetooth connection priority. Only [ConnectionPriority.isWritable] values the headphones
     * offer are encoded; anything else is dropped without sending a byte. May briefly drop the audio.
     */
    data class SetConnectionPriority(val priority: ConnectionPriority) : DeviceCommand {
        override val coalesceKey get() = "priority"
    }

    /**
     * Gives the playback right (where the headphones' buttons act) to the connected device [macAddress].
     * [resumePlayback]: once the headphones confirm the switch, send play to that device.
     */
    data class SwitchPlayback(val macAddress: String, val resumePlayback: Boolean = false) : DeviceCommand {
        override val coalesceKey get() = "source"
    }

    /**
     * "This device just started playing: give it the audio." Resolved only when it is sent, against
     * the list read after (re)connecting, so it works with the channel released: the entry for this
     * device is found by [localAddress] or a unique [localName], and nothing is written when it
     * already has the playback right, is not connected, or a call is in progress. The newest device
     * to start playing wins (docs/decisions/ADR-0011).
     */
    data class ClaimPlayback(val localName: String?, val localAddress: String? = null) : DeviceCommand {
        override val coalesceKey get() = "source"
    }

    /** Play/pause/skip on the device holding the playback right, relayed by the headphones. */
    data class ControlPlayback(val action: PlaybackAction) : DeviceCommand {
        // Play/pause replace each other while unsent (latest wins); so do the skips.
        override val coalesceKey get() = if (action == PlaybackAction.NEXT || action == PlaybackAction.PREVIOUS) "skip" else "playPause"
    }

    /** Developer probe only; never coalesced. */
    class Raw(val type: MessageType, val payload: ByteArray) : DeviceCommand {
        override val coalesceKey: String = "raw-${System.identityHashCode(this)}"
    }
}
