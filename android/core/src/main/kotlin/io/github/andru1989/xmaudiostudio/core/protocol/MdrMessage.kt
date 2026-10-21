package io.github.andru1989.xmaudiostudio.core.protocol

/**
 * Outer frame type byte of a Sony MDR message. Values match the Swift
 * `SonyMessageType` and are covered by shared/protocol-fixtures/frames.json.
 */
enum class MessageType(val code: Int) {
    ACK(0x01),
    COMMAND_1(0x0c),
    COMMAND_2(0x0e);

    companion object {
        fun fromCode(code: Int): MessageType? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One framed message of Sony's RFCOMM control protocol.
 *
 * Wire format (big-endian): `0x3e [TYPE SEQ LEN(4) PAYLOAD CHECKSUM]-escaped 0x3c`.
 * Header, trailer and escape bytes inside the body are written as `0x3d, b & 0xef`.
 * The checksum is the low byte of the sum of TYPE..PAYLOAD.
 */
class MdrMessage(val type: MessageType, val sequenceNumber: Int, val payload: ByteArray) {

    fun encode(): ByteArray {
        val length = payload.size
        val body = ByteArray(6 + length)
        body[0] = type.code.toByte()
        body[1] = sequenceNumber.toByte()
        body[2] = (length ushr 24).toByte()
        body[3] = (length ushr 16).toByte()
        body[4] = (length ushr 8).toByte()
        body[5] = length.toByte()
        payload.copyInto(body, 6)

        val out = ArrayList<Byte>(body.size + 4)
        out.add(HEADER.toByte())
        escapeInto(body, out)
        escapeInto(byteArrayOf(checksum(body).toByte()), out)
        out.add(TRAILER.toByte())
        return out.toByteArray()
    }

    override fun equals(other: Any?): Boolean =
        other is MdrMessage && type == other.type && sequenceNumber == other.sequenceNumber &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int = (type.hashCode() * 31 + sequenceNumber) * 31 + payload.contentHashCode()

    override fun toString(): String = "MdrMessage($type, seq=$sequenceNumber, ${payload.toHex()})"

    companion object {
        const val HEADER = 0x3e
        const val TRAILER = 0x3c
        const val ESCAPE = 0x3d
        private const val ESCAPE_MASK = 0xef

        /** Decodes a complete raw frame; null if malformed or the checksum fails. */
        fun decode(rawFrame: ByteArray): MdrMessage? {
            if (rawFrame.size < 2 || rawFrame.u(0) != HEADER || rawFrame.u(rawFrame.size - 1) != TRAILER) {
                return null
            }
            val f = unescape(rawFrame)
            // header + type + seq + length(4) + checksum + trailer
            if (f.size < 9) return null
            val expected = checksum(f.copyOfRange(1, f.size - 2))
            if (f.u(f.size - 2) != expected) return null
            val type = MessageType.fromCode(f.u(1)) ?: return null
            val length = (f.u(3).toLong() shl 24) or (f.u(4).toLong() shl 16) or
                (f.u(5).toLong() shl 8) or f.u(6).toLong()
            if (length != (f.size - 9).toLong()) return null
            return MdrMessage(type, f.u(2), f.copyOfRange(7, 7 + length.toInt()))
        }

        internal fun checksum(bytes: ByteArray): Int {
            var sum = 0
            for (b in bytes) sum += b.toInt() and 0xff
            return sum and 0xff
        }

        private fun escapeInto(bytes: ByteArray, out: MutableList<Byte>) {
            for (b in bytes) {
                val v = b.toInt() and 0xff
                if (v == HEADER || v == TRAILER || v == ESCAPE) {
                    out.add(ESCAPE.toByte())
                    out.add((v and ESCAPE_MASK).toByte())
                } else {
                    out.add(b)
                }
            }
        }

        internal fun unescape(bytes: ByteArray): ByteArray {
            val out = ArrayList<Byte>(bytes.size)
            var i = 0
            while (i < bytes.size) {
                if (bytes.u(i) == ESCAPE) {
                    i++
                    if (i >= bytes.size) break
                    out.add((bytes.u(i) or ESCAPE_MASK.inv() and 0xff).toByte())
                } else {
                    out.add(bytes[i])
                }
                i++
            }
            return out.toByteArray()
        }
    }
}
