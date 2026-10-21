package io.github.andru1989.xmaudiostudio.core.protocol

/**
 * Reassembles [MdrMessage]s from an RFCOMM byte stream that may arrive fragmented.
 *
 * Scanning for a literal trailer is safe: the encoder escapes every header/trailer/escape
 * byte inside a frame, so the first unescaped trailer after a header ends that frame.
 * Corrupt frames (bad checksum) are dropped and parsing continues with the next one.
 */
class FrameParser {
    private var buffer = ByteArray(0)

    fun reset() {
        buffer = ByteArray(0)
    }

    fun feed(data: ByteArray): List<MdrMessage> {
        buffer += data
        val messages = mutableListOf<MdrMessage>()
        while (true) {
            val start = buffer.indexOfFirst { (it.toInt() and 0xff) == MdrMessage.HEADER }
            if (start < 0) {
                buffer = ByteArray(0)
                break
            }
            if (start > 0) buffer = buffer.copyOfRange(start, buffer.size)
            if (buffer.size <= 1) break

            var end = -1
            for (i in 1 until buffer.size) {
                if ((buffer[i].toInt() and 0xff) == MdrMessage.TRAILER) {
                    end = i
                    break
                }
            }
            if (end < 0) {
                // Incomplete frame: keep it for the next read unless the stream is
                // clearly desynchronised (no real frame comes close to this size).
                if (buffer.size > MAX_BUFFERED_BYTES) buffer = ByteArray(0)
                break
            }
            MdrMessage.decode(buffer.copyOfRange(0, end + 1))?.let(messages::add)
            buffer = buffer.copyOfRange(end + 1, buffer.size)
        }
        return messages
    }

    companion object {
        const val MAX_BUFFERED_BYTES = 8192
    }
}
