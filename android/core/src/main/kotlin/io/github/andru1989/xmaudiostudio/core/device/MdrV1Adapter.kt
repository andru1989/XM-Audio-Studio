package io.github.andru1989.xmaudiostudio.core.device

import io.github.andru1989.xmaudiostudio.core.model.ProtocolVersion
import io.github.andru1989.xmaudiostudio.core.model.SpeakToChatConfig
import io.github.andru1989.xmaudiostudio.core.model.SpeakToChatSensitivity
import io.github.andru1989.xmaudiostudio.core.model.SpeakToChatTimeout
import io.github.andru1989.xmaudiostudio.core.protocol.MdrCommands
import io.github.andru1989.xmaudiostudio.core.protocol.MdrEvent
import io.github.andru1989.xmaudiostudio.core.protocol.MdrV1Commands
import io.github.andru1989.xmaudiostudio.core.protocol.MdrV1EventDecoder
import io.github.andru1989.xmaudiostudio.core.protocol.MessageType
import io.github.andru1989.xmaudiostudio.core.protocol.u

/**
 * MDR v1: WH-1000XM4, WH-1000XM3 and WH-1000XM2 (table 1 only). Battery `10 00`, NC/ambient
 * type 0x02, EQ type 0x01 (Clear Bass + 5 bands) and, where the catalogue lists it (XM4),
 * speak-to-chat on SYSTEM type 0x05. **Never tested on hardware by this project**; see
 * docs/compatibility/OTHER-MODELS.md for the evidence behind each feature.
 *
 * Safety: v1 opcode 0x22 is POWER OFF. No builder here produces it, and [encode] drops even a
 * developer [DeviceCommand.Raw] payload that starts with it.
 */
class MdrV1Adapter(val model: HeadphoneModel) : ProtocolAdapter {
    init {
        require(model.protocol == ProtocolFamily.MDR_V1) { "${model.displayName} does not speak MDR v1" }
    }

    override val id = "mdr-v1"
    override val expectedProtocol = ProtocolVersion.V1

    private val capabilities get() = DeviceCatalog.capabilities(model)
    private val hasSpeakToChat get() = capabilities.supports(Feature.SPEAK_TO_CHAT)

    private fun c1(payload: ByteArray) = Outgoing(MessageType.COMMAND_1, payload)

    override fun stateQueries(): List<Outgoing> = buildList {
        add(c1(MdrCommands.modelNameGet()))
        add(c1(MdrCommands.firmwareVersionGet()))
        add(c1(MdrV1Commands.batteryGet()))
        add(c1(MdrV1Commands.ambientSoundGet()))
        add(c1(MdrV1Commands.equalizerGet()))
        add(c1(MdrV1Commands.equalizerCapabilityGet()))
        if (hasSpeakToChat) {
            add(c1(MdrV1Commands.speakToChatEnabledGet()))
            add(c1(MdrV1Commands.speakToChatConfigGet()))
        }
    }

    override fun encode(command: DeviceCommand, state: HeadphoneState): List<Outgoing> = when (command) {
        is DeviceCommand.SetAmbientSound ->
            listOf(c1(MdrV1Commands.ambientSoundSet(command.mode, command.focusOnVoice, command.level)))
        is DeviceCommand.SetEqualizerPreset -> listOf(c1(MdrV1Commands.equalizerPresetSet(command.code)))
        is DeviceCommand.SetEqualizerBands -> {
            val clearBass = command.clearBass ?: state.equalizer?.clearBass ?: 0
            listOfNotNull(MdrV1Commands.equalizerBandsSet(command.steps, clearBass)?.let(::c1))
        }
        is DeviceCommand.SetSpeakToChat -> if (!hasSpeakToChat) emptyList() else buildList {
            // On a WH-1000XM4, enabling without a mode-out time leaves a talking session open
            // forever (sony-device-center hardware note), so the current options are re-sent
            // first: the ones just read from the device, or Sound Connect's defaults.
            if (command.enabled) add(c1(MdrV1Commands.speakToChatConfigSet(state.speakToChatConfig ?: DEFAULT_STC_CONFIG)))
            add(c1(MdrV1Commands.speakToChatEnabledSet(command.enabled)))
        }
        is DeviceCommand.SetSpeakToChatConfig -> if (!hasSpeakToChat) emptyList() else {
            val voiceFocus = command.config.voiceFocus ?: state.speakToChatConfig?.voiceFocus ?: false
            listOf(c1(MdrV1Commands.speakToChatConfigSet(command.config.copy(voiceFocus = voiceFocus))))
        }
        is DeviceCommand.Raw ->
            if (command.type == MessageType.COMMAND_1 && command.payload.isNotEmpty() && command.payload.u(0) == MdrV1Commands.Op.POWER_OFF) {
                emptyList()
            } else {
                listOf(Outgoing(command.type, command.payload))
            }
        // Not implemented for MDR v1 (auto power off and pause-when-removed are documented but
        // not modelled yet; v1 has no listening modes or multipoint list here): nothing is sent.
        is DeviceCommand.SetListeningMode, is DeviceCommand.SetBgmRoomSize, is DeviceCommand.SetAutomaticPowerOff,
        is DeviceCommand.SetPauseWhenTakenOff, is DeviceCommand.SwitchPlayback -> emptyList()
    }

    override fun decode(type: MessageType, payload: ByteArray): MdrEvent? = MdrV1EventDecoder.decode(payload, type)

    companion object {
        /** Auto sensitivity, voice focus off, standard (~30 s) mode-out time: sony-device-center's XM4 write. */
        val DEFAULT_STC_CONFIG = SpeakToChatConfig(SpeakToChatSensitivity.AUTO, SpeakToChatTimeout.STANDARD, voiceFocus = false)
    }
}
