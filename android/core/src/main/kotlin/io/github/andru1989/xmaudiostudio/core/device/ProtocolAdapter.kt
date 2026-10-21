package io.github.andru1989.xmaudiostudio.core.device

import io.github.andru1989.xmaudiostudio.core.model.AmbientSoundState
import io.github.andru1989.xmaudiostudio.core.model.BgmRoomSize
import io.github.andru1989.xmaudiostudio.core.model.ListeningMode
import io.github.andru1989.xmaudiostudio.core.model.ProtocolVersion
import io.github.andru1989.xmaudiostudio.core.protocol.MdrCommands
import io.github.andru1989.xmaudiostudio.core.protocol.MdrEvent
import io.github.andru1989.xmaudiostudio.core.protocol.MdrEventDecoder
import io.github.andru1989.xmaudiostudio.core.protocol.MessageType

class Outgoing(val type: MessageType, val payload: ByteArray)

/**
 * Model/generation-specific translation between intents and MDR payloads. The session
 * (transport, ACKs, lifecycle) is shared; only adapters differ per generation.
 */
interface ProtocolAdapter {
    val expectedProtocol: ProtocolVersion

    /** Reads issued after every (re)connect, before any pending intent is written. */
    fun stateQueries(): List<Outgoing>

    /** Encodes [command] against the device's freshly read [state]; empty if unsupported. */
    fun encode(command: DeviceCommand, state: HeadphoneState): List<Outgoing>

    fun decode(type: MessageType, payload: ByteArray): MdrEvent?

    companion object {
        /** Adapters that exist today. Models without one are never sent a single byte. */
        fun forModel(model: HeadphoneModel): ProtocolAdapter? = when (model) {
            HeadphoneModel.WH_1000XM6 -> Xm6Adapter
            else -> null
        }
    }
}

/** WH-1000XM6 (MDR v2). Byte layouts in shared/protocol-spec/PROTOCOL.md. */
object Xm6Adapter : ProtocolAdapter {
    override val expectedProtocol = ProtocolVersion.V2

    private fun c1(payload: ByteArray) = Outgoing(MessageType.COMMAND_1, payload)
    private fun c2(payload: ByteArray) = Outgoing(MessageType.COMMAND_2, payload)

    override fun stateQueries(): List<Outgoing> = listOf(
        c1(MdrCommands.modelNameGet()),
        c1(MdrCommands.firmwareVersionGet()),
        // 0x19 carries the real NC/ambient state on firmware 3.x; 0x15/0x17 cover older firmware.
        c1(MdrCommands.ambientSoundGet(0x15)),
        c1(MdrCommands.ambientSoundGet(0x17)),
        c1(MdrCommands.ambientSoundGet(0x19)),
        c1(MdrCommands.batteryGet()),
        c1(MdrCommands.speakToChatEnabledGet()),
        c1(MdrCommands.speakToChatConfigGet()),
        c1(MdrCommands.automaticPowerOffGet()),
        c1(MdrCommands.pauseWhenTakenOffGet()),
        c1(MdrCommands.equalizerGet(0x04)),
        c1(MdrCommands.equalizerGet(0x00)),
        c1(MdrCommands.equalizerCapabilityGet()),
        c1(MdrCommands.bgmModeGet()),
        c1(MdrCommands.upmixCinemaGet()),
        c2(MdrCommands.deviceListGet()),
    )

    override fun encode(command: DeviceCommand, state: HeadphoneState): List<Outgoing> = when (command) {
        is DeviceCommand.SetAmbientSound -> {
            val base = state.ambientSound ?: AmbientSoundState()
            listOf(c1(MdrCommands.ambientSoundSet(base.copy(mode = command.mode, focusOnVoice = command.focusOnVoice, level = command.level.coerceIn(1, 20)))))
        }
        is DeviceCommand.SetEqualizerPreset ->
            listOf(c1(MdrCommands.equalizerPresetSet(command.code, state.equalizer?.subtype ?: 0x04)))
        is DeviceCommand.SetEqualizerBands ->
            listOf(c1(MdrCommands.equalizerBandsSet(command.steps, state.equalizer?.subtype ?: 0x04)))
        is DeviceCommand.SetListeningMode -> {
            val room = state.bgmRoomSize ?: BgmRoomSize.MIDDLE
            listOf(
                c1(MdrCommands.bgmModeSet(command.mode == ListeningMode.BACKGROUND_MUSIC, room)),
                c1(MdrCommands.upmixCinemaSet(command.mode == ListeningMode.CINEMA)),
            )
        }
        is DeviceCommand.SetBgmRoomSize ->
            listOf(c1(MdrCommands.bgmModeSet(state.listeningMode == ListeningMode.BACKGROUND_MUSIC, command.roomSize)))
        is DeviceCommand.SetSpeakToChat -> listOf(c1(MdrCommands.speakToChatEnabledSet(command.enabled)))
        is DeviceCommand.SetSpeakToChatConfig -> listOf(c1(MdrCommands.speakToChatConfigSet(command.config)))
        is DeviceCommand.SetAutomaticPowerOff -> listOf(c1(MdrCommands.automaticPowerOffSet(command.mode)))
        is DeviceCommand.SetPauseWhenTakenOff -> listOf(c1(MdrCommands.pauseWhenTakenOffSet(command.enabled)))
        is DeviceCommand.SwitchPlayback -> {
            val payload = MdrCommands.sourceSwitchSet(command.macAddress)
            if (payload == null) emptyList() else listOf(c2(payload), c2(MdrCommands.deviceListGet()))
        }
        is DeviceCommand.Raw -> listOf(Outgoing(command.type, command.payload))
    }

    override fun decode(type: MessageType, payload: ByteArray): MdrEvent? = MdrEventDecoder.decode(payload, type)
}
