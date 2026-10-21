package io.github.andru1989.xmaudiostudio.core.device

import io.github.andru1989.xmaudiostudio.core.model.AmbientSoundState
import io.github.andru1989.xmaudiostudio.core.model.AutomaticPowerOffMode
import io.github.andru1989.xmaudiostudio.core.model.BatteryStatus
import io.github.andru1989.xmaudiostudio.core.model.BgmRoomSize
import io.github.andru1989.xmaudiostudio.core.model.DeviceInfo
import io.github.andru1989.xmaudiostudio.core.model.EqualizerCapability
import io.github.andru1989.xmaudiostudio.core.model.EqualizerState
import io.github.andru1989.xmaudiostudio.core.model.ListeningMode
import io.github.andru1989.xmaudiostudio.core.model.MultipointDevice
import io.github.andru1989.xmaudiostudio.core.model.ProtocolVersion
import io.github.andru1989.xmaudiostudio.core.model.SpeakToChatConfig
import io.github.andru1989.xmaudiostudio.core.protocol.MdrEvent

/** Everything we know about the headphones. Null means "not reported (yet)". */
data class HeadphoneState(
    val protocolVersion: ProtocolVersion = ProtocolVersion.UNKNOWN,
    val ambientSound: AmbientSoundState? = null,
    val battery: BatteryStatus? = null,
    val speakToChatEnabled: Boolean? = null,
    val speakToChatConfig: SpeakToChatConfig? = null,
    val automaticPowerOff: AutomaticPowerOffMode? = null,
    val pauseWhenTakenOff: Boolean? = null,
    val equalizer: EqualizerState? = null,
    val bgmEnabled: Boolean? = null,
    val bgmRoomSize: BgmRoomSize? = null,
    val cinemaEnabled: Boolean? = null,
    val devices: List<MultipointDevice>? = null,
    val deviceInfo: DeviceInfo = DeviceInfo(),
    val equalizerCapability: EqualizerCapability? = null,
) {
    val listeningMode: ListeningMode?
        get() = when {
            bgmEnabled == null && cinemaEnabled == null -> null
            bgmEnabled == true -> ListeningMode.BACKGROUND_MUSIC
            cinemaEnabled == true -> ListeningMode.CINEMA
            else -> ListeningMode.STANDARD
        }

    /** Folds a device report into the state. */
    fun apply(event: MdrEvent): HeadphoneState = when (event) {
        is MdrEvent.ProtocolInfo -> copy(protocolVersion = event.version)
        // XM6 firmware 3.0.0 answers 0x17 reads with a zero-update placeholder; the
        // real state arrives on 0x19, so placeholders must not overwrite it. On the XM5
        // (0x17 is its real type) update=0 means "slider still moving"; the commit follows.
        is MdrEvent.AmbientSound ->
            if (event.state.subtype == 0x17 && !event.state.isUpdate) this else copy(ambientSound = event.state)
        is MdrEvent.Battery -> copy(battery = event.status)
        is MdrEvent.SpeakToChatEnabled -> copy(speakToChatEnabled = event.enabled)
        is MdrEvent.SpeakToChat -> copy(speakToChatConfig = event.config)
        is MdrEvent.AutomaticPowerOff -> copy(automaticPowerOff = event.mode)
        is MdrEvent.PauseWhenTakenOff -> copy(pauseWhenTakenOff = event.enabled)
        is MdrEvent.Equalizer -> copy(equalizer = event.state)
        is MdrEvent.BgmMode -> copy(bgmEnabled = event.enabled, bgmRoomSize = event.roomSize)
        is MdrEvent.UpmixCinema -> copy(cinemaEnabled = event.enabled)
        is MdrEvent.DeviceList -> copy(devices = event.devices)
        is MdrEvent.ModelName -> copy(deviceInfo = deviceInfo.copy(modelName = event.name))
        is MdrEvent.FirmwareVersion -> copy(deviceInfo = deviceInfo.copy(firmwareVersion = event.version))
        is MdrEvent.EqCapability -> copy(equalizerCapability = event.capability)
    }

    /** The state the UI should show once [command] is accepted. */
    fun applyOptimistic(command: DeviceCommand): HeadphoneState = when (command) {
        is DeviceCommand.SetAmbientSound -> copy(
            ambientSound = (ambientSound ?: AmbientSoundState()).copy(
                mode = command.mode, focusOnVoice = command.focusOnVoice, level = command.level.coerceIn(0, 20),
            ),
        )
        is DeviceCommand.SetEqualizerPreset ->
            copy(equalizer = (equalizer ?: EqualizerState(command.code)).copy(presetCode = command.code))
        is DeviceCommand.SetEqualizerBands -> {
            val current = equalizer ?: EqualizerState(0xa0)
            copy(equalizer = current.copy(presetCode = 0xa0, bands = command.steps, clearBass = command.clearBass ?: current.clearBass))
        }
        is DeviceCommand.SetListeningMode -> copy(
            bgmEnabled = command.mode == ListeningMode.BACKGROUND_MUSIC,
            cinemaEnabled = command.mode == ListeningMode.CINEMA,
        )
        is DeviceCommand.SetBgmRoomSize -> copy(bgmRoomSize = command.roomSize)
        is DeviceCommand.SetSpeakToChat -> copy(speakToChatEnabled = command.enabled)
        is DeviceCommand.SetSpeakToChatConfig -> copy(
            // v1's voice-focus byte is not edited by the UI; keep the value the device reported.
            speakToChatConfig = command.config.copy(voiceFocus = command.config.voiceFocus ?: speakToChatConfig?.voiceFocus),
        )
        is DeviceCommand.SetAutomaticPowerOff -> copy(automaticPowerOff = command.mode)
        is DeviceCommand.SetPauseWhenTakenOff -> copy(pauseWhenTakenOff = command.enabled)
        is DeviceCommand.SwitchPlayback -> copy(
            devices = devices?.map { it.copy(isPlayback = it.macAddress == command.macAddress) },
        )
        is DeviceCommand.Raw -> this
    }
}
