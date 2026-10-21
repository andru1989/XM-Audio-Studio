package io.github.andru1989.xmaudiostudio.core.device

/**
 * Sony 1000X over-ear generations. The catalogue is designed for all of them, but only
 * the XM6 has an implemented protocol adapter today; see [DeviceCatalog] for what each
 * model actually supports and how well it is verified.
 */
enum class HeadphoneModel(
    val id: String,
    val displayName: String,
    /** Substrings of the Bluetooth name that identify the model. */
    val namePatterns: List<String>,
    val protocol: ProtocolFamily,
) {
    MDR_1000X("mdr-1000x", "MDR-1000X", listOf("MDR-1000X"), ProtocolFamily.UNKNOWN),
    WH_1000XM2("wh-1000xm2", "WH-1000XM2", listOf("WH-1000XM2"), ProtocolFamily.MDR_V1),
    WH_1000XM3("wh-1000xm3", "WH-1000XM3", listOf("WH-1000XM3"), ProtocolFamily.MDR_V1),
    WH_1000XM4("wh-1000xm4", "WH-1000XM4", listOf("WH-1000XM4"), ProtocolFamily.MDR_V1),
    WH_1000XM5("wh-1000xm5", "WH-1000XM5", listOf("WH-1000XM5"), ProtocolFamily.MDR_V2),
    WH_1000XM6("wh-1000xm6", "WH-1000XM6", listOf("WH-1000XM6"), ProtocolFamily.MDR_V2);

    companion object {
        fun fromId(id: String): HeadphoneModel? = entries.firstOrNull { it.id == id }

        /**
         * Best guess from the paired device's Bluetooth name. Users can rename devices,
         * so this is only a hint; the protocol version from the init reply confirms it.
         */
        fun fromBluetoothName(name: String?): HeadphoneModel? {
            if (name == null) return null
            val upper = name.uppercase()
            // Longest pattern first so "WH-1000XM6" never matches a shorter prefix.
            return entries.sortedByDescending { m -> m.namePatterns.maxOf { it.length } }
                .firstOrNull { m -> m.namePatterns.any { upper.contains(it) } }
        }
    }
}

/** Which MDR command table a model speaks (decided by the init reply length). */
enum class ProtocolFamily { MDR_V1, MDR_V2, UNKNOWN }
