package io.github.andru1989.xmaudiostudio.core.eq

import kotlin.math.log10

/** Parsers for AutoEq / Equalizer APO text formats. Kept in step with Swift `AutoEqParser`. */
object AutoEqParser {

    class ParseException(message: String) : Exception(message)

    private val preampRegex = Regex("""^\s*Preamp:\s*(-?\d+(?:\.\d+)?)\s*dB""", RegexOption.IGNORE_CASE)
    private val filterRegex = Regex(
        """^\s*Filter\s*\d*\s*:\s*(ON|OFF)\s+([A-Z]+)\s+Fc\s+(\d+(?:\.\d+)?)\s*Hz(?:\s+Gain\s+(-?\d+(?:\.\d+)?)\s*dB)?(?:\s+Q\s+(\d+(?:\.\d+)?))?""",
        RegexOption.IGNORE_CASE,
    )

    /** Parses a `ParametricEQ.txt` (Preamp + `Filter N: ON PK Fc 100 Hz Gain -2.0 dB Q 0.70` lines). */
    fun parseParametric(text: String): ParametricProfile {
        var preamp = 0.0
        val filters = mutableListOf<ParametricFilter>()
        for (line in text.lineSequence()) {
            preampRegex.find(line)?.let {
                preamp = it.groupValues[1].toDouble()
                return@let
            }
            val m = filterRegex.find(line) ?: continue
            if (m.groupValues[1].equals("OFF", ignoreCase = true)) continue
            val type = when (m.groupValues[2].uppercase()) {
                "PK", "PEQ" -> FilterType.PEAKING
                "LS", "LSC", "LSQ" -> FilterType.LOW_SHELF
                "HS", "HSC", "HSQ" -> FilterType.HIGH_SHELF
                else -> throw ParseException("Unsupported filter type '${m.groupValues[2]}' in: ${line.trim()}")
            }
            val frequency = m.groupValues[3].toDouble()
            val gain = m.groupValues[4].ifEmpty { "0" }.toDouble()
            val q = m.groupValues[5].ifEmpty { DEFAULT_SHELF_Q.toString() }.toDouble()
            if (frequency <= 0.0 || frequency >= 24_000.0 || q <= 0.0) {
                throw ParseException("Out-of-range filter: ${line.trim()}")
            }
            filters += ParametricFilter(type, frequency, gain, q)
        }
        if (filters.isEmpty()) throw ParseException("No enabled filters found")
        return ParametricProfile(preamp, filters)
    }

    /** Parses `GraphicEQ: 20 -4.4; 21 -4.3; ...` into (frequency, gain dB) points sorted by frequency. */
    fun parseGraphic(text: String): List<Pair<Double, Double>> {
        val body = text.substringAfter("GraphicEQ:", missingDelimiterValue = "").trim()
        if (body.isEmpty()) throw ParseException("Missing 'GraphicEQ:' prefix")
        val points = body.split(';').mapNotNull { entry ->
            val parts = entry.trim().split(Regex("\\s+"))
            if (parts.size != 2) return@mapNotNull null
            val f = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val g = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            f to g
        }.sortedBy { it.first }
        if (points.size < 2) throw ParseException("GraphicEQ needs at least two points")
        return points
    }

    /** Linear interpolation in log-frequency, clamped at the ends. */
    fun interpolate(points: List<Pair<Double, Double>>, frequencies: DoubleArray): DoubleArray =
        DoubleArray(frequencies.size) { i ->
            val f = frequencies[i]
            when {
                f <= points.first().first -> points.first().second
                f >= points.last().first -> points.last().second
                else -> {
                    val hi = points.indexOfFirst { it.first >= f }
                    val (f0, g0) = points[hi - 1]
                    val (f1, g1) = points[hi]
                    val t = (log10(f) - log10(f0)) / (log10(f1) - log10(f0))
                    g0 + (g1 - g0) * t
                }
            }
        }

    const val DEFAULT_SHELF_Q = 0.7071
}
