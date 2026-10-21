package io.github.andru1989.xmaudiostudio.core.protocol

import io.github.andru1989.xmaudiostudio.core.device.DeviceCommand
import io.github.andru1989.xmaudiostudio.core.device.HeadphoneState
import io.github.andru1989.xmaudiostudio.core.device.Xm6Adapter
import io.github.andru1989.xmaudiostudio.core.model.AudioCodec
import io.github.andru1989.xmaudiostudio.core.model.AudioStreamType
import io.github.andru1989.xmaudiostudio.core.model.ConnectionPriority
import io.github.andru1989.xmaudiostudio.core.model.ConnectionPriority.LOW_LATENCY
import io.github.andru1989.xmaudiostudio.core.model.ConnectionPriority.SOUND_QUALITY
import io.github.andru1989.xmaudiostudio.core.model.ConnectionPriority.STABLE_CONNECTION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Connection priority (AUDIO type 0x05, live XM6 fw 3.1.5 layouts from Anneo22/xm6-control) and
 * the codec indicator (`12 02`, mos9527 reference). Mirrors macos/.../ConnectionPriorityTests.swift.
 */
class ConnectionPriorityTest {
    // Captured on an XM6 fw 3.1.5, independent of the builders.
    private val capability = bytes(0xe1, 0x05, 0x03, 0x00, 0x01, 0x02, 0x00)

    private fun decode(p: ByteArray, type: MessageType = MessageType.COMMAND_1) = MdrEventDecoder.decode(p, type)

    @Test
    fun decodesTheCapturedCapability() {
        assertEquals(listOf(SOUND_QUALITY, STABLE_CONNECTION, LOW_LATENCY), MdrCommands.decodeConnectionPriorityCapability(capability))
        assertEquals(MdrEvent.ConnectionPriorityCapability(listOf(SOUND_QUALITY, STABLE_CONNECTION, LOW_LATENCY)), decode(capability))
        // A valid empty list means "none offered", not invented defaults.
        assertEquals(emptyList<ConnectionPriority>(), MdrCommands.decodeConnectionPriorityCapability(bytes(0xe1, 0x05, 0x00, 0x00)))
        assertEquals(listOf(STABLE_CONNECTION), MdrCommands.decodeConnectionPriorityCapability(bytes(0xe1, 0x05, 0x01, 0x01, 0x00)))
    }

    @Test
    fun rejectsEveryMalformedCapability() {
        for (length in capability.indices) {
            assertNull("truncated to $length", MdrCommands.decodeConnectionPriorityCapability(capability.copyOf(length)))
        }
        val invalid = listOf(
            capability + bytes(0x00), // trailing byte
            bytes(0xe1, 0x05, 0x04, 0x00, 0x01, 0x02, 0x00), // wrong count
            bytes(0xe1, 0x05, 0x02, 0x00, 0x01, 0x02, 0x00), // wrong count (too small)
            bytes(0xe1, 0x05, 0x03, 0x00, 0x01, 0x03, 0x00), // unknown mode
            bytes(0xe1, 0x05, 0x03, 0x00, 0x01, 0x01, 0x00), // duplicate mode
            bytes(0xe1, 0x05, 0x03, 0x00, 0x01, 0x02, 0x01), // exclusive count without its list
            bytes(0xe1, 0x05, 0x03, 0x00, 0x01, 0x02, 0x01, 0x00), // non-empty exclusive list
            bytes(0xe1, 0x04, 0x03, 0x00, 0x01, 0x02, 0x00), // other inquired type
            bytes(0xe0, 0x05, 0x03, 0x00, 0x01, 0x02, 0x00), // request opcode
        )
        for (p in invalid) {
            assertNull(p.toHex(), MdrCommands.decodeConnectionPriorityCapability(p))
            assertNull(p.toHex(), decode(p))
        }
        // Table-2 frames never reach table-1 decoders.
        assertNull(decode(capability, MessageType.COMMAND_2))
    }

    @Test
    fun decodesRepliesAndNotifications() {
        for (priority in ConnectionPriority.entries) {
            assertEquals(priority to null, MdrCommands.decodeConnectionPriority(bytes(0xe7, 0x05, priority.code)))
            assertEquals(MdrEvent.ConnectionPriorityReport(priority, null), decode(bytes(0xe7, 0x05, priority.code)))
            for (stream in AudioStreamType.entries) {
                val p = bytes(0xe9, 0x05, priority.code, stream.code)
                assertEquals(priority to stream, MdrCommands.decodeConnectionPriority(p))
                assertEquals(MdrEvent.ConnectionPriorityReport(priority, stream), decode(p))
            }
        }
        // Live captures: stable and quality notifications with no stream switch.
        assertEquals(STABLE_CONNECTION to AudioStreamType.NONE, MdrCommands.decodeConnectionPriority(bytes(0xe9, 0x05, 0x01, 0x00)))
        assertEquals(SOUND_QUALITY to AudioStreamType.NONE, MdrCommands.decodeConnectionPriority(bytes(0xe9, 0x05, 0x00, 0x00)))
    }

    @Test
    fun rejectsEveryMalformedReport() {
        val invalid = listOf(
            bytes(0xe7), bytes(0xe7, 0x05),
            bytes(0xe7, 0x05, 0x00, 0x00), // reply with a stream byte
            bytes(0xe7, 0x05, 0x03), // unknown mode
            bytes(0xe7, 0x06, 0x00), // other inquired type
            bytes(0xe9, 0x05, 0x01), // notify without stream
            bytes(0xe9, 0x05, 0x01, 0x03), // unknown stream
            bytes(0xe9, 0x05, 0x03, 0x00), // unknown mode
            bytes(0xe9, 0x05, 0x01, 0x00, 0x00), // too long
            bytes(0xe8, 0x05, 0x00), // our own write
            bytes(0xe3, 0x05, 0x00, 0x00), // other opcode
        )
        for (p in invalid) {
            assertNull(p.toHex(), MdrCommands.decodeConnectionPriority(p))
            assertNull(p.toHex(), decode(p))
        }
        assertNull(decode(bytes(0xe7, 0x05, 0x00), MessageType.COMMAND_2))
        // BGM / cinema still decode on the same opcodes.
        assertTrue(decode(bytes(0xe7, 0x09, 0x01, 0x01)) is MdrEvent.BgmMode)
        assertTrue(decode(bytes(0xe9, 0x04, 0x00)) is MdrEvent.UpmixCinema)
    }

    @Test
    fun buildsQueriesAndOnlyTheLiveVerifiedWrites() {
        assertEquals("e0 05", MdrCommands.connectionPriorityCapabilityGet().toHex())
        assertEquals("e6 05", MdrCommands.connectionPriorityGet().toHex())
        assertEquals("e8 05 00 01", MdrCommands.connectionPrioritySet(SOUND_QUALITY)?.toHex())
        assertEquals("e8 05 01 01", MdrCommands.connectionPrioritySet(STABLE_CONNECTION)?.toHex())
        // Advertised by the XM6 but rejected live: never built.
        assertNull(MdrCommands.connectionPrioritySet(LOW_LATENCY))
        assertEquals(listOf(SOUND_QUALITY, STABLE_CONNECTION), ConnectionPriority.entries.filter { it.isWritable })
        assertEquals("12 02", MdrCommands.audioCodecGet().toHex())
    }

    @Test
    fun decodesCodecsAndKeepsUnknownBytes() {
        val expected = mapOf(0x01 to AudioCodec.SBC, 0x02 to AudioCodec.AAC, 0x10 to AudioCodec.LDAC, 0x20 to AudioCodec.APTX, 0x21 to AudioCodec.APTX_HD, 0x30 to AudioCodec.LC3)
        for ((code, codec) in expected) {
            for (op in listOf(0x13, 0x15)) {
                val event = decode(bytes(op, 0x02, code)) as MdrEvent.AudioCodecReport
                assertEquals(code, event.code)
                assertEquals(codec, event.codec)
            }
        }
        val unknown = decode(bytes(0x13, 0x02, 0x99)) as MdrEvent.AudioCodecReport
        assertEquals(0x99, unknown.code)
        assertNull(unknown.codec)
        for (p in listOf(bytes(0x13, 0x02), bytes(0x13, 0x02, 0x10, 0x00), bytes(0x13, 0x01, 0x10), bytes(0x14, 0x02, 0x10), bytes(0x12, 0x02, 0x10))) {
            assertNull(p.toHex(), MdrCommands.decodeAudioCodec(p))
            assertNull(p.toHex(), decode(p))
        }
        assertNull(decode(bytes(0x13, 0x02, 0x10), MessageType.COMMAND_2))
    }

    @Test
    fun stateFoldsReportsAndKeepsTheKnownStream() {
        var s = HeadphoneState()
        s = s.apply(MdrEvent.ConnectionPriorityCapability(listOf(SOUND_QUALITY, STABLE_CONNECTION, LOW_LATENCY)))
        s = s.apply(MdrEvent.ConnectionPriorityReport(SOUND_QUALITY, null))
        assertEquals(SOUND_QUALITY, s.connectionPriority)
        assertNull(s.audioStream)
        s = s.apply(MdrEvent.ConnectionPriorityReport(STABLE_CONNECTION, AudioStreamType.LE_AUDIO))
        assertEquals(STABLE_CONNECTION, s.connectionPriority)
        assertEquals(AudioStreamType.LE_AUDIO, s.audioStream)
        // "No switch" does not erase what is known.
        s = s.apply(MdrEvent.ConnectionPriorityReport(SOUND_QUALITY, AudioStreamType.NONE))
        assertEquals(AudioStreamType.LE_AUDIO, s.audioStream)
        s = s.apply(MdrEvent.ConnectionPriorityReport(SOUND_QUALITY, AudioStreamType.CLASSIC))
        assertEquals(AudioStreamType.CLASSIC, s.audioStream)
        s = s.apply(MdrEvent.AudioCodecReport(0x10))
        assertEquals(AudioCodec.LDAC, s.audioCodec)
        s = s.apply(MdrEvent.AudioCodecReport(0x77))
        assertEquals(0x77, s.audioCodecCode)
        assertNull(s.audioCodec)
    }

    @Test
    fun optimisticStateAndEncodingNeverApplyAnUnwritablePriority() {
        val offered = HeadphoneState(
            connectionPriority = SOUND_QUALITY,
            connectionPriorities = listOf(SOUND_QUALITY, STABLE_CONNECTION, LOW_LATENCY),
        )
        val stable = DeviceCommand.SetConnectionPriority(STABLE_CONNECTION)
        assertEquals(STABLE_CONNECTION, offered.applyOptimistic(stable).connectionPriority)
        assertEquals(listOf("e8 05 01 01"), Xm6Adapter.encode(stable, offered).map { it.payload.toHex() })

        val lowLatency = DeviceCommand.SetConnectionPriority(LOW_LATENCY)
        assertEquals(SOUND_QUALITY, offered.applyOptimistic(lowLatency).connectionPriority)
        assertTrue(Xm6Adapter.encode(lowLatency, offered).isEmpty())

        // Not offered by these headphones: not written either.
        val qualityOnly = offered.copy(connectionPriorities = listOf(SOUND_QUALITY))
        assertTrue(Xm6Adapter.encode(stable, qualityOnly).isEmpty())
        assertEquals(SOUND_QUALITY, qualityOnly.applyOptimistic(stable).connectionPriority)
        // Capability not reported (yet): the live-verified writes are allowed.
        assertEquals(listOf("e8 05 00 01"), Xm6Adapter.encode(DeviceCommand.SetConnectionPriority(SOUND_QUALITY), HeadphoneState()).map { it.payload.toHex() })
        assertEquals("priority", stable.coalesceKey)
    }
}
