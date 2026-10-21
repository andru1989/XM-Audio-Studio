package io.github.andru1989.xmaudiostudio.core.protocol

/** Builds a byte array from unsigned values (0..255), mirroring Swift's `[UInt8]` literals. */
fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

/** Unsigned read of one byte. */
fun ByteArray.u(index: Int): Int = this[index].toInt() and 0xff

fun ByteArray.toHex(separator: String = " "): String =
    joinToString(separator) { "%02x".format(it.toInt() and 0xff) }

/** Parses "3e 0c 00" / "3e0c00" style hex into bytes. */
fun hexToBytes(hex: String): ByteArray {
    val clean = hex.filter { !it.isWhitespace() }
    require(clean.length % 2 == 0) { "Odd hex length: $hex" }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

internal fun boolFromByte(b: Int): Boolean? = when (b) {
    0x00 -> false
    0x01 -> true
    else -> null
}
