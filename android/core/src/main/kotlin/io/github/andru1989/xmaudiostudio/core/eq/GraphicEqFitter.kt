package io.github.andru1989.xmaudiostudio.core.eq

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * How we *model* a headphone's built-in graphic EQ in order to predict its curve.
 *
 * Sony does not publish the filter shapes; each band is modelled as an RBJ peaking
 * filter at the band centre. Curves computed from this model are predictions and are
 * labelled as such in both UIs ([shapeVerified] stays false until a measurement says
 * otherwise).
 */
data class DeviceEqModel(
    val bandFrequencies: List<Double>,
    val q: Double,
    /** dB per device step (UNVERIFIED for XM6, assumed 1.0). */
    val stepDb: Double,
    val minStep: Int,
    val maxStep: Int,
    val shapeVerified: Boolean = false,
) {
    val bandCount: Int get() = bandFrequencies.size

    fun filters(steps: List<Int>): List<ParametricFilter> =
        bandFrequencies.mapIndexed { k, f -> ParametricFilter(FilterType.PEAKING, f, steps[k] * stepDb, q) }

    /** Predicted device curve for [steps] (device units). */
    fun responseDb(steps: List<Int>, frequencies: DoubleArray, sampleRate: Double = ParametricProfile.DEFAULT_SAMPLE_RATE): DoubleArray =
        ParametricProfile(0.0, filters(steps)).responseDb(frequencies, sampleRate)
}

data class FitResult(
    /** Integer device steps, low band first, within the model's range. */
    val steps: List<Int>,
    /** Unquantised least-squares solution in dB (before rounding to device steps). */
    val continuousGainsDb: List<Double>,
    /** Constant level shift absorbed by the fit (not sent to the headphones). */
    val levelOffsetDb: Double,
    val frequencies: DoubleArray,
    val targetDb: DoubleArray,
    val predictedDb: DoubleArray,
    /** Weighted RMS of (predicted + offset - target). */
    val weightedRmsErrorDb: Double,
    /** Largest |error| between 20 Hz and 10 kHz. */
    val maxAbsErrorDb: Double,
)

/**
 * Fits a target correction curve onto a fixed-band graphic EQ.
 *
 * 1. Continuous: box-constrained ridge least squares by cyclic coordinate descent on a
 *    linearised basis (each band's dB response at +1 dB), with a free level offset.
 * 2. Discrete: round to device steps, then greedy ±1 step coordinate search on the exact
 *    (non-linear) predicted response until no move lowers the objective.
 *
 * Deterministic and dependency-free so the Swift port produces the same steps; see
 * docs/dsp/AUTOEQ.md for the derivation and shared/autoeq-test-vectors for pinned results.
 */
object GraphicEqFitter {
    const val DEFAULT_REGULARIZATION = 0.02
    private const val SWEEPS = 500
    private const val TOLERANCE = 1e-10

    const val GRID_POINTS = 240

    /**
     * 0.5 below 40 Hz (seal-dependent), 1.0 up to 6 kHz, then falling linearly in log f to
     * 0.3 at 10 kHz and above: treble measurements are the least reliable part of any curve.
     */
    fun defaultWeight(frequency: Double): Double = when {
        frequency < 40.0 -> 0.5
        frequency <= 6_000.0 -> 1.0
        frequency >= 10_000.0 -> 0.3
        else -> 1.0 - 0.7 * (log10(frequency / 6_000.0) / log10(10_000.0 / 6_000.0))
    }

    /** The default fitting grid: [GRID_POINTS] log-spaced points, 20 Hz to 20 kHz. */
    fun defaultGrid(): DoubleArray = FrequencyGrid.logSpaced(20.0, 20_000.0, GRID_POINTS)

    fun fit(
        model: DeviceEqModel,
        targetDb: DoubleArray,
        frequencies: DoubleArray,
        regularization: Double = DEFAULT_REGULARIZATION,
        sampleRate: Double = ParametricProfile.DEFAULT_SAMPLE_RATE,
    ): FitResult {
        require(targetDb.size == frequencies.size)
        val n = frequencies.size
        val bands = model.bandCount
        val weights = DoubleArray(n) { defaultWeight(frequencies[it]) }
        val weightSum = weights.sum()

        // Linearised basis: response of band k at +1 dB.
        val basis = Array(bands) { k ->
            ParametricProfile(0.0, listOf(ParametricFilter(FilterType.PEAKING, model.bandFrequencies[k], 1.0, model.q)))
                .responseDb(frequencies, sampleRate)
        }
        val minDb = model.minStep * model.stepDb
        val maxDb = model.maxStep * model.stepDb

        val x = DoubleArray(bands)
        var offset = 0.0
        val model0 = DoubleArray(n) // current sum_k x_k * basis_k
        for (sweep in 0 until SWEEPS) {
            var maxChange = 0.0
            // Offset: weighted mean of the residual.
            var acc = 0.0
            for (i in 0 until n) acc += weights[i] * (targetDb[i] - model0[i])
            val newOffset = acc / weightSum
            maxChange = max(maxChange, abs(newOffset - offset))
            offset = newOffset
            for (k in 0 until bands) {
                val b = basis[k]
                var num = 0.0
                var den = regularization
                for (i in 0 until n) {
                    val partial = model0[i] - x[k] * b[i]
                    num += weights[i] * b[i] * (targetDb[i] - offset - partial)
                    den += weights[i] * b[i] * b[i]
                }
                val updated = (num / den).coerceIn(minDb, maxDb)
                val delta = updated - x[k]
                if (delta != 0.0) {
                    for (i in 0 until n) model0[i] += delta * b[i]
                    x[k] = updated
                }
                maxChange = max(maxChange, abs(delta))
            }
            if (maxChange < TOLERANCE) break
        }

        // Discrete refinement on the exact response.
        val steps = IntArray(bands) { k -> (x[k] / model.stepDb).roundToInt().coerceIn(model.minStep, model.maxStep) }
        var best = objective(model, steps, targetDb, frequencies, weights, regularization, sampleRate)
        var improved = true
        var passes = 0
        while (improved && passes < 100) {
            improved = false
            passes++
            for (k in 0 until bands) {
                for (delta in intArrayOf(-1, 1)) {
                    val candidate = steps[k] + delta
                    if (candidate < model.minStep || candidate > model.maxStep) continue
                    val original = steps[k]
                    steps[k] = candidate
                    val value = objective(model, steps, targetDb, frequencies, weights, regularization, sampleRate)
                    if (value < best - 1e-12) {
                        best = value
                        improved = true
                    } else {
                        steps[k] = original
                    }
                }
            }
        }

        val predicted = model.responseDb(steps.toList(), frequencies, sampleRate)
        val finalOffset = levelOffset(model, steps, targetDb, frequencies, weights, sampleRate)
        var weightedSq = 0.0
        var maxAbs = 0.0
        for (i in 0 until n) {
            val e = predicted[i] + finalOffset - targetDb[i]
            weightedSq += weights[i] * e * e
            if (frequencies[i] in 20.0..10_000.0) maxAbs = max(maxAbs, abs(e))
        }
        return FitResult(
            steps = steps.toList(),
            continuousGainsDb = x.toList(),
            levelOffsetDb = finalOffset,
            frequencies = frequencies,
            targetDb = targetDb,
            predictedDb = predicted,
            weightedRmsErrorDb = sqrt(weightedSq / weightSum),
            maxAbsErrorDb = maxAbs,
        )
    }

    /** Weighted squared error at the optimal level offset, plus the ridge penalty. */
    private fun objective(
        model: DeviceEqModel, steps: IntArray, target: DoubleArray, frequencies: DoubleArray,
        weights: DoubleArray, regularization: Double, sampleRate: Double,
    ): Double {
        val predicted = model.responseDb(steps.toList(), frequencies, sampleRate)
        val offset = weightedMean(target, predicted, weights)
        var sum = 0.0
        for (i in predicted.indices) {
            val e = predicted[i] + offset - target[i]
            sum += weights[i] * e * e
        }
        for (s in steps) sum += regularization * (s * model.stepDb) * (s * model.stepDb)
        return sum
    }

    private fun levelOffset(
        model: DeviceEqModel, steps: IntArray, target: DoubleArray, frequencies: DoubleArray,
        weights: DoubleArray, sampleRate: Double,
    ): Double = weightedMean(target, model.responseDb(steps.toList(), frequencies, sampleRate), weights)

    private fun weightedMean(target: DoubleArray, predicted: DoubleArray, weights: DoubleArray): Double {
        var acc = 0.0
        var ws = 0.0
        for (i in target.indices) {
            acc += weights[i] * (target[i] - predicted[i])
            ws += weights[i]
        }
        return acc / ws
    }
}
