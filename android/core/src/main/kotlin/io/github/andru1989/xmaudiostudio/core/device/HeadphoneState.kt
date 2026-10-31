package io.github.andru1989.xmaudiostudio.core.device

import io.github.andru1989.xmaudiostudio.core.model.AmbientSoundState
import io.github.andru1989.xmaudiostudio.core.model.AudioCodec
import io.github.andru1989.xmaudiostudio.core.model.AudioStreamType
import io.github.andru1989.xmaudiostudio.core.model.AutomaticPowerOffMode
import io.github.andru1989.xmaudiostudio.core.model.BatteryStatus
import io.github.andru1989.xmaudiostudio.core.model.BgmRoomSize
import io.github.andru1989.xmaudiostudio.core.model.ConnectionPriority
import io.github.andru1989.xmaudiostudio.core.model.DeviceInfo
import io.github.andru1989.xmaudiostudio.core.model.EqualizerCapability
import io.github.andru1989.xmaudiostudio.core.model.EqualizerState
import io.github.andru1989.xmaudiostudio.core.model.InterruptedPlayback
import io.github.andru1989.xmaudiostudio.core.model.ListeningMode
import io.github.andru1989.xmaudiostudio.core.model.MultipointDevice
import io.github.andru1989.xmaudiostudio.core.model.NowPlaying
import io.github.andru1989.xmaudiostudio.core.model.PlaybackAction
import io.github.andru1989.xmaudiostudio.core.model.PlaybackState
import io.github.andru1989.xmaudiostudio.core.model.PlaybackStatus
import io.github.andru1989.xmaudiostudio.core.model.SourceSwitchResult
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
    /** Multipoint list as shown: [reportedDevices] with a pending switch ([switchingTo]) applied. */
    val devices: List<MultipointDevice>? = null,
    /** Multipoint list exactly as the headphones last reported it. */
    val reportedDevices: List<MultipointDevice>? = null,
    /** MAC of a source switch sent but not yet confirmed (`3d 01`) nor expired. */
    val switchingTo: String? = null,
    /** Why the last source switch was refused; cleared by the next switch or a success. */
    val sourceSwitchFailure: SourceSwitchResult? = null,
    /** Play state of the device holding the playback right (`a3/a5 01`). */
    val playbackStatus: PlaybackStatus? = null,
    /** Track names that device shares (`a7/a9 01`). */
    val nowPlaying: NowPlaying? = null,
    /** The source the user last chose here; a move to it is never an interruption. */
    val requestedSource: String? = null,
    /** Music that stopped because the headphones handed the playback right to another device. */
    val interruptedPlayback: InterruptedPlayback? = null,
    val deviceInfo: DeviceInfo = DeviceInfo(),
    val equalizerCapability: EqualizerCapability? = null,
    val connectionPriority: ConnectionPriority? = null,
    /** Priorities the headphones offer (`e1 05`); null until reported. */
    val connectionPriorities: List<ConnectionPriority>? = null,
    /**
     * LE Audio or classic, as the last priority notification said. A notification saying "none"
     * (no stream switch) keeps what was known; null means never reported.
     */
    val audioStream: AudioStreamType? = null,
    /** Raw codec byte (`13 02`); see [audioCodec]. */
    val audioCodecCode: Int? = null,
) {
    val listeningMode: ListeningMode?
        get() = when {
            bgmEnabled == null && cinemaEnabled == null -> null
            bgmEnabled == true -> ListeningMode.BACKGROUND_MUSIC
            cinemaEnabled == true -> ListeningMode.CINEMA
            else -> ListeningMode.STANDARD
        }

    /** Null both when unreported and when the byte is outside the reference table (check [audioCodecCode]). */
    val audioCodec: AudioCodec? get() = audioCodecCode?.let(AudioCodec::fromCode)

    /** The device the headphones' buttons control (playback right), as shown. */
    val playbackDevice: MultipointDevice? get() = devices?.firstOrNull { it.isPlayback }

    /** Ends a source switch that was never confirmed: the reported list is the truth again. */
    fun expireSourceSwitch(macAddress: String): HeadphoneState =
        if (switchingTo != macAddress) this else copy(switchingTo = null, devices = reportedDevices ?: devices)

    /**
     * A new list from the headphones. A move of the playback right away from a device that was
     * playing, to a device nobody picked here, is remembered as an interruption.
     */
    private fun withReportedList(list: List<MultipointDevice>): HeadphoneState {
        val oldHolder = reportedDevices?.firstOrNull { it.isPlayback }
        val newHolder = list.firstOrNull { it.isPlayback }
        val moved = oldHolder != null && newHolder != null && oldHolder.macAddress != newHolder.macAddress
        val toChosen = newHolder != null && newHolder.macAddress == requestedSource
        val interrupted = when {
            switchingTo != null || toChosen -> null
            moved && playbackStatus?.state == PlaybackState.PLAYING &&
                list.any { it.macAddress == oldHolder!!.macAddress && it.isConnected } ->
                InterruptedPlayback(oldHolder!!.macAddress, oldHolder.name, newHolder!!.macAddress)
            // The source came back, or the interrupted device left: nothing to bring back.
            interruptedPlayback != null && (newHolder?.macAddress == interruptedPlayback.fromMac ||
                list.none { it.macAddress == interruptedPlayback.fromMac && it.isConnected }) -> null
            else -> interruptedPlayback
        }
        return copy(
            reportedDevices = list,
            devices = withSwitch(list, switchingTo),
            interruptedPlayback = interrupted,
            // Once the right moved elsewhere on its own, the user's earlier choice no longer applies.
            requestedSource = requestedSource.takeIf { switchingTo != null || newHolder == null || toChosen },
        )
    }

    /** Writable on the wire, and offered by the headphones when they reported their list. */
    fun canWrite(priority: ConnectionPriority): Boolean =
        priority.isWritable && connectionPriorities?.contains(priority) != false

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
        // While a switch is pending, a list read before the headphones finished switching must
        // not flip the UI back to the old source for a moment.
        is MdrEvent.DeviceList -> withReportedList(event.devices)
        is MdrEvent.SourceSwitched -> if (event.result == SourceSwitchResult.SUCCESS) {
            val confirmed = (reportedDevices ?: devices)?.let { withSwitch(it, event.macAddress) }
            copy(reportedDevices = confirmed, devices = confirmed, switchingTo = null, sourceSwitchFailure = null, interruptedPlayback = null)
        } else {
            copy(
                devices = reportedDevices ?: devices,
                switchingTo = null,
                requestedSource = null,
                sourceSwitchFailure = event.result.takeIf { switchingTo == null || switchingTo == event.macAddress } ?: sourceSwitchFailure,
            )
        }
        // Something plays again: whatever stopped is no longer worth bringing back.
        is MdrEvent.Playback -> copy(
            playbackStatus = event.status,
            interruptedPlayback = interruptedPlayback.takeIf { event.status.state != PlaybackState.PLAYING },
        )
        is MdrEvent.Track -> copy(nowPlaying = event.nowPlaying)
        is MdrEvent.ModelName -> copy(deviceInfo = deviceInfo.copy(modelName = event.name))
        is MdrEvent.FirmwareVersion -> copy(deviceInfo = deviceInfo.copy(firmwareVersion = event.version))
        is MdrEvent.EqCapability -> copy(equalizerCapability = event.capability)
        is MdrEvent.ConnectionPriorityCapability -> copy(connectionPriorities = event.priorities)
        is MdrEvent.ConnectionPriorityReport -> copy(
            connectionPriority = event.priority,
            audioStream = event.switchingStream?.takeIf { it != AudioStreamType.NONE } ?: audioStream,
        )
        is MdrEvent.AudioCodecReport -> copy(audioCodecCode = event.code)
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
        // A priority that cannot be written is never sent, so it must not be shown as applied.
        is DeviceCommand.SetConnectionPriority ->
            if (canWrite(command.priority)) copy(connectionPriority = command.priority) else this
        is DeviceCommand.SwitchPlayback -> copy(
            devices = (reportedDevices ?: devices)?.let { withSwitch(it, command.macAddress) },
            switchingTo = command.macAddress,
            requestedSource = command.macAddress,
            sourceSwitchFailure = null,
            interruptedPlayback = null,
        )
        // Shown as a switch only once it resolves to this device on the state at hand.
        is DeviceCommand.ClaimPlayback ->
            PlaybackSourceAdvisor.claimTarget(this, command.localAddress, command.localName)
                ?.let { applyOptimistic(DeviceCommand.SwitchPlayback(it.macAddress)) } ?: this
        // Shown at once; the status the headphones report next reconciles it.
        is DeviceCommand.ControlPlayback -> when (command.action) {
            PlaybackAction.PLAY -> copy(playbackStatus = (playbackStatus ?: PlaybackStatus(PlaybackState.UNKNOWN)).copy(state = PlaybackState.PLAYING))
            PlaybackAction.PAUSE -> copy(playbackStatus = (playbackStatus ?: PlaybackStatus(PlaybackState.UNKNOWN)).copy(state = PlaybackState.PAUSED))
            PlaybackAction.NEXT, PlaybackAction.PREVIOUS -> this
        }
        is DeviceCommand.Raw -> this
    }
}

/**
 * [devices] with the playback flag moved to [macAddress], when that device is listed and connected.
 * The headphones report the source per connection status, so a pending switch is shown on top of it.
 */
internal fun withSwitch(devices: List<MultipointDevice>, macAddress: String?): List<MultipointDevice> {
    if (macAddress == null || devices.none { it.macAddress == macAddress && it.isConnected }) return devices
    return devices.map { it.copy(isPlayback = it.macAddress == macAddress) }
}
