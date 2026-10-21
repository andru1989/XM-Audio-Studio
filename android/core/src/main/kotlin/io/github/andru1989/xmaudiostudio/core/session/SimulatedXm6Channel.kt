package io.github.andru1989.xmaudiostudio.core.session

import io.github.andru1989.xmaudiostudio.core.protocol.FrameParser
import io.github.andru1989.xmaudiostudio.core.protocol.MdrMessage
import io.github.andru1989.xmaudiostudio.core.protocol.MessageType
import io.github.andru1989.xmaudiostudio.core.protocol.bytes
import io.github.andru1989.xmaudiostudio.core.protocol.u
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A WH-1000XM6 emulated at the byte level, for demo mode and session tests.
 *
 * It answers with the payload layouts documented in shared/protocol-spec/PROTOCOL.md,
 * including firmware 3.x quirks (0x17 placeholder, real state on 0x19, classic EQ query
 * ignored). Everything it reports is invented; the UI must label it as a simulation.
 */
class SimulatedXm6Channel(
    private val scope: CoroutineScope,
    private val latencyMs: Long = 15,
) : ControlChannel {

    var ancOn = true
    var ambient = false
    var focusOnVoice = false
    var ambientLevel = 12
    var battery = 72
    var charging = false
    var eqPreset = 0xa0
    var eqBands = mutableListOf(1, 1, 0, 0, 0, 0, 0, 1, 1, 0)
    var bgmEnabled = false
    var bgmRoom = 0x01
    var cinemaEnabled = false
    var speakToChatEnabled = false
    var stcSensitivity = 0x00
    var stcTimeout = 0x01
    var autoPowerOff = 0x11
    var pauseWhenTakenOff = true
    var playbackIndex = 0
    /** 0x00 sound quality, 0x01 stable connection (0x02 low latency is advertised but not writable). */
    var connectionPriority = 0x00
    /** LDAC. */
    var audioCodec = 0x10
    val devices = listOf("AA:BB:CC:DD:EE:01" to "MacBook Pro", "AA:BB:CC:DD:EE:02" to "Pixel")

    /** Set to make open() fail, e.g. to simulate another device holding the channel. */
    var refuseConnections = false
    /** Init reply payload; the default is an 8-byte (MDR v2) reply. */
    var initReply = bytes(0x01, 0x00, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00)
    var openCount = 0
        private set
    var isOpen = false
        private set
    val received = mutableListOf<MdrMessage>()

    private var listener: ControlChannel.Listener? = null
    private val parser = FrameParser()
    private var deviceSeq = 0

    override fun open(address: String, listener: ControlChannel.Listener) {
        this.listener = listener
        openCount++
        parser.reset()
        scope.launch {
            delay(latencyMs)
            if (refuseConnections) {
                listener.onFailed(ChannelError.ConnectFailed("Simulated: channel busy"))
            } else {
                isOpen = true
                listener.onOpened()
            }
        }
    }

    override fun close() {
        isOpen = false
        listener = null
    }

    override fun write(bytes: ByteArray) {
        if (!isOpen) return
        for (message in parser.feed(bytes)) {
            received += message
            if (message.type == MessageType.ACK) continue
            // ACK first, as the real headset does, then any reply.
            emit(MdrMessage(MessageType.ACK, message.sequenceNumber xor 0x01, ByteArray(0)))
            respond(message.type, message.payload)
        }
    }

    /** The headset closes our channel, as when a phone's Sound Connect takes control. */
    fun simulateRemoteTakeover() {
        val l = listener ?: return
        isOpen = false
        scope.launch { l.onClosed() }
    }

    /** Pressing the NC/AMB button on the headphones: NC -> ambient -> off -> NC. */
    fun simulateNoiseControlButton() {
        when {
            ancOn && !ambient -> ambient = true
            ancOn && ambient -> ancOn = false
            else -> { ancOn = true; ambient = false }
        }
        emitCommand(MessageType.COMMAND_1, ambientReport(0x69))
    }

    private fun emit(message: MdrMessage) {
        val l = listener ?: return
        val frame = message.encode()
        scope.launch {
            delay(latencyMs)
            if (isOpen) l.onData(frame)
        }
    }

    private fun emitCommand(type: MessageType, payload: ByteArray) {
        emit(MdrMessage(type, deviceSeq, payload))
        deviceSeq = deviceSeq xor 0x01
    }

    private fun ambientReport(opcode: Int) = bytes(
        opcode, 0x19, 0x01, if (ancOn) 0x01 else 0x00, if (ambient) 0x01 else 0x00,
        if (focusOnVoice) 0x01 else 0x00, ambientLevel, 0x00, 0x00,
    )

    private fun eqReport(opcode: Int) = bytes(opcode, 0x04, eqPreset, eqBands.size, *eqBands.map { it + 6 }.toIntArray())

    private fun deviceList(opcode: Int): ByteArray {
        var out = bytes(opcode, 0x02, devices.size)
        devices.forEachIndexed { i, (mac, name) ->
            val status = if (i == playbackIndex) 0x02 else 0x01
            out += mac.toByteArray() + bytes(status, 0x00, 0x00, 0x00, name.length) + name.toByteArray()
        }
        return out + bytes(0x02)
    }

    private fun respond(type: MessageType, p: ByteArray) {
        val reply: ByteArray? = if (type == MessageType.COMMAND_2) {
            when {
                p.u(0) == 0x36 && p.u(1) == 0x02 -> deviceList(0x37)
                p.u(0) == 0x3c && p.u(1) == 0x01 && p.size == 19 -> {
                    val mac = String(p.copyOfRange(2, 19))
                    devices.indexOfFirst { it.first == mac }.takeIf { it >= 0 }?.let { playbackIndex = it }
                    deviceList(0x39)
                }
                else -> null
            }
        } else {
            when (p.u(0)) {
                0x00 -> initReply
                0x22 -> bytes(0x23, 0x00, battery, if (charging) 1 else 0)
                0x66 -> when (p.u(1)) {
                    0x17 -> bytes(0x67, 0x17, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00) // fw 3.x placeholder
                    0x19 -> ambientReport(0x67)
                    else -> null // fw 3.x ignores 0x15
                }
                0x68 -> {
                    ancOn = p.u(3) == 0x01
                    ambient = p.u(4) == 0x01
                    focusOnVoice = p.u(p.size - 2) == 0x01
                    ambientLevel = p.u(p.size - 1)
                    ambientReport(0x69)
                }
                0x56 -> if (p.u(1) == 0x04) eqReport(0x57) else null
                0x58 -> {
                    eqPreset = p.u(2)
                    if (p.u(2) == 0xa0 && p.size >= 4 + p.u(3)) {
                        eqBands = (0 until p.u(3)).map { p.u(4 + it) - 6 }.toMutableList()
                    }
                    eqReport(0x59)
                }
                0x12 -> if (p.u(1) == 0x02) bytes(0x13, 0x02, audioCodec) else null
                // Capability as captured on an XM6 fw 3.1.5: quality, stable, low latency; no exclusive list.
                0xe0 -> if (p.u(1) == 0x05) bytes(0xe1, 0x05, 0x03, 0x00, 0x01, 0x02, 0x00) else null
                0xe6 -> when (p.u(1)) {
                    0x09 -> bytes(0xe7, 0x09, if (bgmEnabled) 0x00 else 0x01, bgmRoom)
                    0x04 -> bytes(0xe7, 0x04, if (cinemaEnabled) 0x00 else 0x01)
                    0x05 -> bytes(0xe7, 0x05, connectionPriority)
                    else -> null
                }
                0xe8 -> when (p.u(1)) {
                    0x09 -> {
                        bgmEnabled = p.u(2) == 0x00
                        bgmRoom = p.u(3)
                        bytes(0xe9, 0x09, p.u(2), bgmRoom)
                    }
                    0x04 -> {
                        cinemaEnabled = p.u(2) == 0x00
                        bytes(0xe9, 0x04, p.u(2))
                    }
                    // Like the real XM6, only `e8 05 <00|01> 01` is taken; anything else is ignored.
                    0x05 -> if (p.size == 4 && p.u(3) == 0x01 && p.u(2) in 0x00..0x01) {
                        connectionPriority = p.u(2)
                        bytes(0xe9, 0x05, connectionPriority, 0x02) // stream: classic
                    } else {
                        null
                    }
                    else -> null
                }
                0xf6 -> when (p.u(1)) {
                    0x0c -> bytes(0xf7, 0x0c, if (speakToChatEnabled) 0x00 else 0x01, 0x01)
                    0x01 -> bytes(0xf7, 0x01, if (pauseWhenTakenOff) 0x00 else 0x01)
                    else -> null
                }
                0xf8 -> when (p.u(1)) {
                    0x0c -> {
                        speakToChatEnabled = p.u(2) == 0x00
                        bytes(0xf9, 0x0c, p.u(2), 0x01)
                    }
                    0x01 -> {
                        pauseWhenTakenOff = p.u(2) == 0x00
                        bytes(0xf9, 0x01, p.u(2))
                    }
                    else -> null
                }
                0xfa -> if (p.u(1) == 0x0c) bytes(0xfb, 0x0c, stcSensitivity, stcTimeout) else null
                0xfc -> {
                    stcSensitivity = p.u(2)
                    stcTimeout = p.u(3)
                    bytes(0xfd, 0x0c, stcSensitivity, stcTimeout)
                }
                0x26 -> if (p.u(1) == 0x05) bytes(0x27, 0x05, autoPowerOff, 0x00) else null
                0x28 -> {
                    // XM6 only accepts 0x10/0x11; anything else is re-announced unchanged.
                    if (p.u(2) == 0x10 || p.u(2) == 0x11) autoPowerOff = p.u(2)
                    bytes(0x29, 0x05, autoPowerOff, 0x00)
                }
                else -> null
            }
        }
        reply?.let { emitCommand(type, it) }
    }
}
