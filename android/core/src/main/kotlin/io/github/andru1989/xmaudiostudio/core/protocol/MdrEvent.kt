package io.github.andru1989.xmaudiostudio.core.protocol

import io.github.andru1989.xmaudiostudio.core.model.AmbientSoundState
import io.github.andru1989.xmaudiostudio.core.model.AudioCodec
import io.github.andru1989.xmaudiostudio.core.model.AudioStreamType
import io.github.andru1989.xmaudiostudio.core.model.AutomaticPowerOffMode
import io.github.andru1989.xmaudiostudio.core.model.BatteryStatus
import io.github.andru1989.xmaudiostudio.core.model.BgmRoomSize
import io.github.andru1989.xmaudiostudio.core.model.ConnectionPriority
import io.github.andru1989.xmaudiostudio.core.model.EqualizerCapability
import io.github.andru1989.xmaudiostudio.core.model.EqualizerState
import io.github.andru1989.xmaudiostudio.core.model.MultipointDevice
import io.github.andru1989.xmaudiostudio.core.model.ProtocolVersion
import io.github.andru1989.xmaudiostudio.core.model.SpeakToChatConfig

/** A decoded, high-level event from an incoming COMMAND_1/COMMAND_2 payload. */
sealed interface MdrEvent {
    data class ProtocolInfo(val version: ProtocolVersion) : MdrEvent
    data class AmbientSound(val state: AmbientSoundState) : MdrEvent
    data class Battery(val status: BatteryStatus) : MdrEvent
    data class SpeakToChatEnabled(val enabled: Boolean) : MdrEvent
    data class SpeakToChat(val config: SpeakToChatConfig) : MdrEvent
    data class AutomaticPowerOff(val mode: AutomaticPowerOffMode) : MdrEvent
    data class PauseWhenTakenOff(val enabled: Boolean) : MdrEvent
    data class Equalizer(val state: EqualizerState) : MdrEvent
    data class BgmMode(val enabled: Boolean, val roomSize: BgmRoomSize) : MdrEvent
    data class UpmixCinema(val enabled: Boolean) : MdrEvent
    data class DeviceList(val devices: List<MultipointDevice>) : MdrEvent
    data class ModelName(val name: String) : MdrEvent
    data class FirmwareVersion(val version: String) : MdrEvent
    data class EqCapability(val capability: EqualizerCapability) : MdrEvent

    /** Priorities the headphones offer (`e1 05`), in the order reported. */
    data class ConnectionPriorityCapability(val priorities: List<ConnectionPriority>) : MdrEvent

    /** Current priority; [switchingStream] only comes with a notification (`e9 05`), null on a reply (`e7 05`). */
    data class ConnectionPriorityReport(val priority: ConnectionPriority, val switchingStream: AudioStreamType?) : MdrEvent

    /** Raw codec byte (`13 02` / `15 02`); [codec] is null when the byte is not in the reference table. */
    data class AudioCodecReport(val code: Int) : MdrEvent {
        val codec: AudioCodec? get() = AudioCodec.fromCode(code)
    }
}

/**
 * Dispatches a payload to the right decoder. Several opcodes are shared by different
 * features (disambiguated by the subtype byte), so candidates are tried in a fixed order
 * that matches macos/.../Protocol/HeadphonesEvent.swift; each decoder validates its own
 * subtype and returns null on mismatch.
 */
object MdrEventDecoder {
    fun decode(payload: ByteArray, type: MessageType): MdrEvent? {
        if (payload.isEmpty()) return null
        val op = payload.u(0)

        if (type == MessageType.COMMAND_2) {
            if (MdrCommands.isDeviceListReply(op)) {
                MdrCommands.decodeDeviceList(payload)?.let { return MdrEvent.DeviceList(it) }
            }
            return null
        }

        if (op == Opcode.INIT_REPLY) return MdrEvent.ProtocolInfo(MdrCommands.protocolVersion(payload))
        if (op == Opcode.DEVICE_INFO_RET) {
            val (infoType, text) = MdrCommands.decodeDeviceInfo(payload) ?: return null
            return if (infoType == Subtype.DEVICE_INFO_MODEL_NAME) MdrEvent.ModelName(text) else MdrEvent.FirmwareVersion(text)
        }
        if (op == Opcode.EQ_CAPABILITY_RET) {
            return MdrCommands.decodeEqualizerCapability(payload)?.let { MdrEvent.EqCapability(it) }
        }
        if (op == Opcode.AUDIO_CAPABILITY_RET) {
            return MdrCommands.decodeConnectionPriorityCapability(payload)?.let { MdrEvent.ConnectionPriorityCapability(it) }
        }
        if (MdrCommands.isCommonStatusReply(op)) {
            return MdrCommands.decodeAudioCodec(payload)?.let { MdrEvent.AudioCodecReport(it) }
        }
        if (MdrCommands.isAmbientSoundReply(op)) {
            MdrCommands.decodeAmbientSound(payload)?.let { return MdrEvent.AmbientSound(it) }
        }
        if (MdrCommands.isBatteryReply(op)) {
            MdrCommands.decodeBattery(payload)?.let { return MdrEvent.Battery(it) }
        }
        if (MdrCommands.isAutomaticPowerOffReply(op)) {
            MdrCommands.decodeAutomaticPowerOff(payload)?.let { return MdrEvent.AutomaticPowerOff(it) }
        }
        if (MdrCommands.isPauseWhenTakenOffReply(op)) {
            MdrCommands.decodePauseWhenTakenOff(payload)?.let { return MdrEvent.PauseWhenTakenOff(it) }
        }
        if (MdrCommands.isSpeakToChatEnabledReply(op)) {
            MdrCommands.decodeSpeakToChatEnabled(payload)?.let { return MdrEvent.SpeakToChatEnabled(it) }
        }
        if (MdrCommands.isSpeakToChatConfigReply(op)) {
            MdrCommands.decodeSpeakToChatConfig(payload)?.let { return MdrEvent.SpeakToChat(it) }
        }
        if (MdrCommands.isEqualizerReply(op)) {
            MdrCommands.decodeEqualizer(payload)?.let { return MdrEvent.Equalizer(it) }
        }
        if (MdrCommands.isAudioParamReply(op)) {
            MdrCommands.decodeConnectionPriority(payload)?.let { return MdrEvent.ConnectionPriorityReport(it.first, it.second) }
            MdrCommands.decodeBgmMode(payload)?.let { return MdrEvent.BgmMode(it.first, it.second) }
            MdrCommands.decodeUpmixCinema(payload)?.let { return MdrEvent.UpmixCinema(it) }
        }
        return null
    }
}
