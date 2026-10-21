package io.github.andru1989.xmaudiostudio.data

import io.github.andru1989.xmaudiostudio.core.protocol.toHex
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Optional hex trace of the control channel for diagnosing protocol issues. Off by default.
 * It contains only protocol bytes (which can include paired device names and MAC addresses in
 * the multipoint list); it is never uploaded anywhere and can be shared manually by the user.
 */
class ProtocolLog(private val file: File) {
    @Volatile var enabled = false
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(direction: String, bytes: ByteArray) {
        if (!enabled) return
        if (file.length() > MAX_BYTES) file.delete()
        file.appendText("${time.format(Date())} $direction ${bytes.toHex()}\n")
    }

    fun read(): String = if (file.exists()) file.readText() else ""

    fun clear() {
        file.delete()
    }

    private companion object {
        const val MAX_BYTES = 2_000_000L
    }
}
