package io.github.andru1989.xmaudiostudio.core.eq

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class FilterType { PEAKING, LOW_SHELF, HIGH_SHELF }

data class ParametricFilter(
    val type: FilterType,
    val frequency: Double,
    val gainDb: Double,
    val q: Double,
)

/**
 * Normalised biquad coefficients (a0 == 1) from the RBJ Audio EQ Cookbook.
 * Formulas are documented in docs/dsp/EQ-MATH.md and duplicated in Swift (AudioStudioKit);
 * shared/autoeq-test-vectors pins both to the same numbers.
 */
data class BiquadCoefficients(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {

    /** |H(e^jw)| in dB at [frequency] Hz. */
    fun magnitudeDb(frequency: Double, sampleRate: Double): Double {
        val w = 2.0 * PI * frequency / sampleRate
        val cw = cos(w)
        val c2w = cos(2.0 * w)
        val sw = sin(w)
        val s2w = sin(2.0 * w)
        val numRe = b0 + b1 * cw + b2 * c2w
        val numIm = -(b1 * sw + b2 * s2w)
        val denRe = 1.0 + a1 * cw + a2 * c2w
        val denIm = -(a1 * sw + a2 * s2w)
        val num = numRe * numRe + numIm * numIm
        val den = denRe * denRe + denIm * denIm
        return 10.0 * log10(max(num, 1e-30) / max(den, 1e-30))
    }

    companion object {
        fun of(filter: ParametricFilter, sampleRate: Double): BiquadCoefficients {
            val a = 10.0.pow(filter.gainDb / 40.0)
            val w0 = 2.0 * PI * filter.frequency / sampleRate
            val cw = cos(w0)
            val alpha = sin(w0) / (2.0 * filter.q)
            val b0: Double
            val b1: Double
            val b2: Double
            val a0: Double
            val a1: Double
            val a2: Double
            when (filter.type) {
                FilterType.PEAKING -> {
                    b0 = 1.0 + alpha * a
                    b1 = -2.0 * cw
                    b2 = 1.0 - alpha * a
                    a0 = 1.0 + alpha / a
                    a1 = -2.0 * cw
                    a2 = 1.0 - alpha / a
                }
                FilterType.LOW_SHELF -> {
                    val k = 2.0 * sqrt(a) * alpha
                    b0 = a * ((a + 1) - (a - 1) * cw + k)
                    b1 = 2.0 * a * ((a - 1) - (a + 1) * cw)
                    b2 = a * ((a + 1) - (a - 1) * cw - k)
                    a0 = (a + 1) + (a - 1) * cw + k
                    a1 = -2.0 * ((a - 1) + (a + 1) * cw)
                    a2 = (a + 1) + (a - 1) * cw - k
                }
                FilterType.HIGH_SHELF -> {
                    val k = 2.0 * sqrt(a) * alpha
                    b0 = a * ((a + 1) + (a - 1) * cw + k)
                    b1 = -2.0 * a * ((a - 1) + (a + 1) * cw)
                    b2 = a * ((a + 1) + (a - 1) * cw - k)
                    a0 = (a + 1) - (a - 1) * cw + k
                    a1 = 2.0 * ((a - 1) - (a + 1) * cw)
                    a2 = (a + 1) - (a - 1) * cw - k
                }
            }
            return BiquadCoefficients(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }
    }
}

/** A parametric EQ (e.g. an AutoEQ ParametricEQ.txt): preamp plus a chain of biquads. */
data class ParametricProfile(val preampDb: Double, val filters: List<ParametricFilter>) {

    /** Response of the filter chain in dB on [frequencies]; [includePreamp] adds the preamp. */
    fun responseDb(frequencies: DoubleArray, sampleRate: Double = DEFAULT_SAMPLE_RATE, includePreamp: Boolean = false): DoubleArray {
        val coefficients = filters.map { BiquadCoefficients.of(it, sampleRate) }
        return DoubleArray(frequencies.size) { i ->
            var sum = if (includePreamp) preampDb else 0.0
            for (c in coefficients) sum += c.magnitudeDb(frequencies[i], sampleRate)
            sum
        }
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE = 48_000.0
    }
}

object FrequencyGrid {
    /** [count] log-spaced points from [min] to [max] inclusive. */
    fun logSpaced(min: Double = 20.0, max: Double = 20_000.0, count: Int = 96): DoubleArray {
        require(count >= 2)
        val ratio = log10(max / min)
        return DoubleArray(count) { i -> min * 10.0.pow(ratio * i / (count - 1)) }
    }
}
