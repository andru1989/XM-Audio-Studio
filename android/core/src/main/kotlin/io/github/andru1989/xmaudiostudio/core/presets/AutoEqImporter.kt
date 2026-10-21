package io.github.andru1989.xmaudiostudio.core.presets

import io.github.andru1989.xmaudiostudio.core.device.DeviceCatalog
import io.github.andru1989.xmaudiostudio.core.device.EqualizerSpec
import io.github.andru1989.xmaudiostudio.core.device.HeadphoneModel
import io.github.andru1989.xmaudiostudio.core.eq.AutoEqParser
import io.github.andru1989.xmaudiostudio.core.eq.DeviceEqModel
import io.github.andru1989.xmaudiostudio.core.eq.FitResult
import io.github.andru1989.xmaudiostudio.core.eq.GraphicEqFitter

/**
 * "AutoEQ interno": turns an AutoEq ParametricEQ.txt / GraphicEQ.txt into the closest curve
 * the headphones' own fixed bands can play. The result is a fit to a *model* of Sony's EQ
 * (see [DeviceEqModel]); the UI shows target vs. approximation and the residual error.
 */
object AutoEqImporter {
    /** Octave-spaced graphic EQ bands are modelled with Q = sqrt(2). */
    const val BAND_Q = 1.41

    fun deviceModel(spec: EqualizerSpec) = DeviceEqModel(
        bandFrequencies = spec.bandFrequenciesHz,
        q = BAND_Q,
        stepDb = spec.stepDb,
        minStep = spec.minStep,
        maxStep = spec.maxStep,
    )

    class Result(val fit: FitResult, val sourceCurve: SourceCurve?)

    /** Accepts either format; ParametricEQ is detected by its "Filter" lines. */
    fun fit(text: String, model: HeadphoneModel): Result {
        val spec = DeviceCatalog.capabilities(model).equalizer
            ?: throw ProfileFormatException("${model.displayName} has no equalizer")
        val grid = GraphicEqFitter.defaultGrid()
        return try {
            if (text.contains("GraphicEQ:")) {
                val target = AutoEqParser.interpolate(AutoEqParser.parseGraphic(text), grid)
                Result(GraphicEqFitter.fit(deviceModel(spec), target, grid), null)
            } else {
                val parametric = AutoEqParser.parseParametric(text)
                Result(GraphicEqFitter.fit(deviceModel(spec), parametric.responseDb(grid), grid), SourceCurve.of(parametric))
            }
        } catch (e: AutoEqParser.ParseException) {
            throw ProfileFormatException(e.message ?: "Unreadable AutoEq file")
        }
    }
}
