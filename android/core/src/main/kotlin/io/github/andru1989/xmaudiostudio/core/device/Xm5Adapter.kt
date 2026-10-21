package io.github.andru1989.xmaudiostudio.core.device

import io.github.andru1989.xmaudiostudio.core.model.ProtocolVersion
import io.github.andru1989.xmaudiostudio.core.protocol.MdrCommands
import io.github.andru1989.xmaudiostudio.core.protocol.MdrEvent
import io.github.andru1989.xmaudiostudio.core.protocol.MessageType
import io.github.andru1989.xmaudiostudio.core.protocol.Xm5Commands
import io.github.andru1989.xmaudiostudio.core.protocol.Xm5EventDecoder

/**
 * WH-1000XM5 (MDR v2): NC/ambient 0x17, battery `22 00`, EQ type 0x00 (Clear Bass + 5 bands)
 * and speak-to-chat. Built from references and sibling-model captures only: **never tested on a
 * WH-1000XM5 by this project**. Features without documentation for this model (auto power off,
 * pause when taken off, listening modes, multipoint) encode to nothing.
 */
object Xm5Adapter : ProtocolAdapter {
    override val id = "xm5"
    override val expectedProtocol = ProtocolVersion.V2

    private fun c1(payload: ByteArray) = Outgoing(MessageType.COMMAND_1, payload)

    override fun stateQueries(): List<Outgoing> = listOf(
        c1(MdrCommands.modelNameGet()),
        c1(MdrCommands.firmwareVersionGet()),
        c1(Xm5Commands.ambientSoundGet()),
        c1(Xm5Commands.batteryGet()),
        c1(Xm5Commands.speakToChatEnabledGet()),
        c1(Xm5Commands.speakToChatConfigGet()),
        c1(Xm5Commands.equalizerGet()),
        c1(Xm5Commands.equalizerCapabilityGet()),
    )

    override fun encode(command: DeviceCommand, state: HeadphoneState): List<Outgoing> = when (command) {
        is DeviceCommand.SetAmbientSound ->
            listOf(c1(Xm5Commands.ambientSoundSet(command.mode, command.focusOnVoice, command.level)))
        is DeviceCommand.SetEqualizerPreset -> listOf(c1(Xm5Commands.equalizerPresetSet(command.code)))
        is DeviceCommand.SetEqualizerBands -> {
            val clearBass = command.clearBass ?: state.equalizer?.clearBass ?: 0
            listOfNotNull(Xm5Commands.equalizerBandsSet(command.steps, clearBass)?.let(::c1))
        }
        is DeviceCommand.SetSpeakToChat -> listOf(c1(Xm5Commands.speakToChatEnabledSet(command.enabled)))
        is DeviceCommand.SetSpeakToChatConfig -> listOf(c1(Xm5Commands.speakToChatConfigSet(command.config)))
        is DeviceCommand.Raw -> listOf(Outgoing(command.type, command.payload))
        // Not documented for the WH-1000XM5: nothing is sent.
        is DeviceCommand.SetListeningMode, is DeviceCommand.SetBgmRoomSize, is DeviceCommand.SetAutomaticPowerOff,
        is DeviceCommand.SetPauseWhenTakenOff, is DeviceCommand.SwitchPlayback -> emptyList()
    }

    override fun decode(type: MessageType, payload: ByteArray): MdrEvent? = Xm5EventDecoder.decode(payload, type)
}
