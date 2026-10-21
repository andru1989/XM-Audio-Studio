package io.github.andru1989.xmaudiostudio.core.protocol

import io.github.andru1989.xmaudiostudio.core.SharedFixtures
import io.github.andru1989.xmaudiostudio.core.int
import io.github.andru1989.xmaudiostudio.core.str
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs shared/protocol-fixtures/frames.json; the Swift suite runs the same file. */
class FrameConformanceTest {
    private val fixtures = SharedFixtures.json("protocol-fixtures/frames.json")

    private fun typeOf(name: String) = when (name) {
        "ack" -> MessageType.ACK
        "command1" -> MessageType.COMMAND_1
        "command2" -> MessageType.COMMAND_2
        else -> error("unknown type $name")
    }

    @Test
    fun encodesEveryValidFrame() {
        val cases = fixtures["valid"]!!.jsonArray
        assertTrue(cases.isNotEmpty())
        for (case in cases.map { it.jsonObject }) {
            val message = MdrMessage(typeOf(case["type"]!!.str), case["seq"]!!.int, hexToBytes(case["payload"]!!.str))
            assertEquals(case["name"]!!.str, case["frame"]!!.str, message.encode().toHex())
        }
    }

    @Test
    fun decodesEveryValidFrame() {
        for (case in fixtures["valid"]!!.jsonArray.map { it.jsonObject }) {
            val decoded = MdrMessage.decode(hexToBytes(case["frame"]!!.str))
            val name = case["name"]!!.str
            requireNotNull(decoded) { "failed to decode: $name" }
            assertEquals(name, typeOf(case["type"]!!.str), decoded.type)
            assertEquals(name, case["seq"]!!.int, decoded.sequenceNumber)
            assertArrayEquals(name, hexToBytes(case["payload"]!!.str), decoded.payload)
        }
    }

    @Test
    fun rejectsInvalidFrames() {
        for (case in fixtures["invalid"]!!.jsonArray.map { it.jsonObject }) {
            assertNull(case["name"]!!.str, MdrMessage.decode(hexToBytes(case["frame"]!!.str)))
        }
    }

    @Test
    fun reassemblesStreams() {
        for (case in fixtures["streams"]!!.jsonArray.map { it.jsonObject }) {
            val parser = FrameParser()
            val payloads = case["chunks"]!!.jsonArray.flatMap { parser.feed(hexToBytes(it.str)) }.map { it.payload.toHex() }
            val expected = case["expectPayloads"]!!.jsonArray.map { it.str }
            assertEquals(case["name"]!!.str, expected, payloads)
        }
    }

    @Test
    fun dropsRunawayBufferWithoutTrailer() {
        val parser = FrameParser()
        val junk = ByteArray(FrameParser.MAX_BUFFERED_BYTES + 10) { 0x11 }
        junk[0] = MdrMessage.HEADER.toByte()
        assertTrue(parser.feed(junk).isEmpty())
        // A valid frame after the reset is still parsed.
        val frame = MdrMessage(MessageType.COMMAND_1, 0, bytes(0x22, 0x00)).encode()
        assertEquals(1, parser.feed(frame).size)
    }
}
