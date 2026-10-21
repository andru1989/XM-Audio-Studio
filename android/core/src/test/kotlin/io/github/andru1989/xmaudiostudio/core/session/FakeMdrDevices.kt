package io.github.andru1989.xmaudiostudio.core.session

import io.github.andru1989.xmaudiostudio.core.protocol.FrameParser
import io.github.andru1989.xmaudiostudio.core.protocol.MdrMessage
import io.github.andru1989.xmaudiostudio.core.protocol.MessageType
import io.github.andru1989.xmaudiostudio.core.protocol.bytes
import io.github.andru1989.xmaudiostudio.core.protocol.hexToBytes
import io.github.andru1989.xmaudiostudio.core.protocol.u
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A minimal byte-level headset for session tests: ACKs every message, then answers through
 * [respond]. Everything it reports is invented test data shaped like the shared fixtures.
 */
open class FakeMdrDevice(private val scope: CoroutineScope, private val latencyMs: Long = 15) : ControlChannel {
    var openCount = 0
        private set
    var isOpen = false
        private set
    val received = mutableListOf<MdrMessage>()
    val commands: List<MdrMessage> get() = received.filter { it.type != MessageType.ACK }

    private var listener: ControlChannel.Listener? = null
    private val parser = FrameParser()
    private var deviceSeq = 0

    /** Reply for one request, or null to only ACK it. */
    open fun respond(type: MessageType, p: ByteArray): ByteArray? = null

    override fun open(address: String, listener: ControlChannel.Listener) {
        this.listener = listener
        openCount++
        parser.reset()
        scope.launch {
            delay(latencyMs)
            isOpen = true
            listener.onOpened()
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
            emit(MdrMessage(MessageType.ACK, message.sequenceNumber xor 0x01, ByteArray(0)))
            respond(message.type, message.payload)?.let { emitCommand(message.type, it) }
        }
    }

    fun emitCommand(type: MessageType, payload: ByteArray) {
        emit(MdrMessage(type, deviceSeq, payload))
        deviceSeq = deviceSeq xor 0x01
    }

    private fun emit(message: MdrMessage) {
        val l = listener ?: return
        val frame = message.encode()
        scope.launch {
            delay(latencyMs)
            if (isOpen) l.onData(frame)
        }
    }
}

/** An MDR v1 headset (XM3-like; speak-to-chat answered as on an XM4). */
class FakeV1Headphones(scope: CoroutineScope) : FakeMdrDevice(scope) {
    var initReply = bytes(0x01, 0x00, 0x40, 0x10)
    var poweredOff = false
    var battery = 100
    var ncEffect = 0x01
    var ncValue = 0x02
    var asmId = 0x00
    var ambientLevel = 0
    var eqPreset = 0x00
    var eqWire = MutableList(6) { 10 }
    var stcOn = false
    var stcConfig = listOf(0x00, 0x00, 0x01) // sensitivity, voice focus, timeout

    private fun nc(op: Int) = bytes(op, 0x02, ncEffect, 0x02, ncValue, 0x01, asmId, ambientLevel)
    private fun eq(op: Int) = bytes(op, 0x01, eqPreset, 0x06, *eqWire.toIntArray())

    override fun respond(type: MessageType, p: ByteArray): ByteArray? {
        if (type != MessageType.COMMAND_1) return null
        return when (p.u(0)) {
            0x00 -> initReply
            0x04 -> when (p.u(1)) {
                0x01 -> bytes(0x05, 0x01, 0x0a) + "WH-1000XM3".toByteArray()
                0x02 -> bytes(0x05, 0x02, 0x05) + "4.5.2".toByteArray()
                else -> null
            }
            0x10 -> bytes(0x11, 0x00, battery, 0x00)
            0x22 -> { poweredOff = true; null }
            0x50 -> hexToBytes("51 01 06 15 0c 00 00 10 00 11 00 12 00 13 00 14 00 15 00 16 00 17 00 a0 00 a1 00 a2 00")
            0x56 -> if (p.u(1) == 0x01) eq(0x57) else null
            0x58 -> {
                eqPreset = p.u(2)
                if (p.u(3) == 6) eqWire = (0 until 6).map { p.u(4 + it) }.toMutableList()
                eq(0x59)
            }
            0x66 -> if (p.u(1) == 0x02) nc(0x67) else null
            0x68 -> {
                ncEffect = if (p.u(2) == 0x00) 0x00 else 0x01
                ncValue = p.u(4)
                asmId = p.u(6)
                ambientLevel = p.u(7)
                nc(0x69)
            }
            0xf6 -> if (p.u(1) == 0x05) bytes(0xf7, 0x05, 0x00, if (stcOn) 1 else 0) else null
            0xf8 -> if (p.u(1) == 0x05) { stcOn = p.u(3) == 1; bytes(0xf9, 0x05, 0x01, p.u(3)) } else null
            0xfa -> if (p.u(1) == 0x05) bytes(0xfb, 0x05, 0x00, *stcConfig.toIntArray()) else null
            0xfc -> if (p.u(1) == 0x05) { stcConfig = listOf(p.u(3), p.u(4), p.u(5)); bytes(0xfd, 0x05, 0x00, *stcConfig.toIntArray()) } else null
            else -> null
        }
    }
}

/** A WH-1000XM5-like headset (MDR v2, NC 0x17, EQ 0x00). */
class FakeXm5Headphones(scope: CoroutineScope) : FakeMdrDevice(scope) {
    var eqWire = mutableListOf(0x14, 0x0b, 0x0a, 0x0d, 0x09, 0x0c)
    var eqPreset = 0xa0
    var nc = bytes(0x01, 0x01, 0x00, 0x00, 0x0a) // update, effect, ambient, voice, level

    override fun respond(type: MessageType, p: ByteArray): ByteArray? {
        if (type != MessageType.COMMAND_1) return null
        return when (p.u(0)) {
            0x00 -> bytes(0x01, 0x00, 0x03, 0x00, 0x30, 0x18, 0x00, 0x00)
            0x22 -> if (p.u(1) == 0x00) bytes(0x23, 0x00, 0x46, 0x00) else null
            0x66 -> if (p.u(1) == 0x17) bytes(0x67, 0x17) + nc else null
            0x68 -> { nc = p.copyOfRange(2, 7); bytes(0x69, 0x17) + nc }
            0x56 -> if (p.u(1) == 0x00) bytes(0x57, 0x00, eqPreset, 0x06, *eqWire.toIntArray()) else null
            0x58 -> {
                eqPreset = p.u(2)
                if (p.u(3) == 6) eqWire = (0 until 6).map { p.u(4 + it) }.toMutableList()
                bytes(0x59, 0x00, eqPreset, 0x06, *eqWire.toIntArray())
            }
            0xf6 -> if (p.u(1) == 0x0c) bytes(0xf7, 0x0c, 0x01, 0x01) else null
            0xfa -> if (p.u(1) == 0x0c) bytes(0xfb, 0x0c, 0x00, 0x00) else null
            else -> null
        }
    }
}
