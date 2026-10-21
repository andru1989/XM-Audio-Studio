package io.github.andru1989.xmaudiostudio.core.eq

import io.github.andru1989.xmaudiostudio.core.SharedFixtures
import io.github.andru1989.xmaudiostudio.core.double
import io.github.andru1989.xmaudiostudio.core.int
import io.github.andru1989.xmaudiostudio.core.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** shared/autoeq-test-vectors/vectors.json, produced by an independent Python reference. */
class EqVectorsTest {
    private val v = SharedFixtures.json("autoeq-test-vectors/vectors.json")
    private val tol = v["tolerances"]!!.jsonObject

    private fun type(name: String) = when (name) {
        "peaking" -> FilterType.PEAKING
        "lowShelf" -> FilterType.LOW_SHELF
        else -> FilterType.HIGH_SHELF
    }

    private val model: DeviceEqModel = v["deviceModel"]!!.jsonObject.let { m ->
        DeviceEqModel(
            bandFrequencies = m["bands"]!!.jsonArray.map { it.double },
            q = m["q"]!!.double,
            stepDb = m["stepDb"]!!.double,
            minStep = m["minStep"]!!.int,
            maxStep = m["maxStep"]!!.int,
        )
    }

    @Test
    fun biquadCoefficientsAndMagnitudesMatchReference() {
        for (b in v["biquads"]!!.jsonArray.map { it.jsonObject }) {
            val filter = ParametricFilter(type(b["type"]!!.str), b["frequency"]!!.double, b["gainDb"]!!.double, b["q"]!!.double)
            val fs = b["sampleRate"]!!.double
            val c = BiquadCoefficients.of(filter, fs)
            val expected = b["coefficients"]!!.jsonArray.map { it.double }
            listOf(c.b0, c.b1, c.b2, c.a1, c.a2).zip(expected).forEach { (actual, e) ->
                assertEquals("coefficient of $filter", e, actual, tol["coefficient"]!!.double)
            }
            val probes = b["probeHz"]!!.jsonArray.map { it.double }
            val mags = b["magnitudeDb"]!!.jsonArray.map { it.double }
            probes.zip(mags).forEach { (f, e) ->
                assertEquals("|H| at $f Hz for $filter", e, c.magnitudeDb(f, fs), tol["magnitudeDb"]!!.double)
            }
        }
    }

    @Test
    fun parsesAndFitsEveryProfile() {
        val grid = GraphicEqFitter.defaultGrid()
        for (case in v["fits"]!!.jsonArray.map { it.jsonObject }) {
            val name = case["name"]!!.str
            val target = if (case.containsKey("parametricText")) {
                val parsed = AutoEqParser.parseParametric(case["parametricText"]!!.str)
                checkParsed(name, parsed, case["parsed"]!!.jsonObject)
                parsed.responseDb(grid)
            } else {
                model.responseDb(case["deviceSteps"]!!.jsonArray.map { it.int }, grid)
            }
            val result = GraphicEqFitter.fit(model, target, grid)
            val expected = case["expected"]!!.jsonObject
            assertEquals(name, expected["steps"]!!.jsonArray.map { it.int }, result.steps)
            expected["continuousGainsDb"]!!.jsonArray.map { it.double }.zip(result.continuousGainsDb).forEach { (e, a) ->
                assertEquals("$name continuous", e, a, tol["fitContinuousDb"]!!.double)
            }
            assertEquals("$name rms", expected["weightedRmsErrorDb"]!!.double, result.weightedRmsErrorDb, tol["fitRmsDb"]!!.double)
            assertEquals("$name offset", expected["levelOffsetDb"]!!.double, result.levelOffsetDb, tol["fitRmsDb"]!!.double)
        }
    }

    private fun checkParsed(name: String, parsed: ParametricProfile, expected: JsonObject) {
        assertEquals(name, expected["preampDb"]!!.double, parsed.preampDb, 0.0)
        val filters = expected["filters"]!!.jsonArray.map { it.jsonObject }
        assertEquals(name, filters.size, parsed.filters.size)
        filters.zip(parsed.filters).forEach { (e, a) ->
            assertEquals(type(e["type"]!!.str), a.type)
            assertEquals(e["frequency"]!!.double, a.frequency, 0.0)
            assertEquals(e["gainDb"]!!.double, a.gainDb, 0.0)
            assertEquals(e["q"]!!.double, a.q, 0.0)
        }
    }

    @Test
    fun graphicEqParsingAndInterpolation() {
        val points = AutoEqParser.parseGraphic("GraphicEQ: 20 -4.4; 100 -2.0; 1000 0.0; 20000 3.0")
        assertEquals(4, points.size)
        val values = AutoEqParser.interpolate(points, doubleArrayOf(10.0, 20.0, 316.2277660168379, 1000.0, 30000.0))
        assertEquals(-4.4, values[0], 1e-12)
        assertEquals(-4.4, values[1], 1e-12)
        assertEquals(-1.0, values[2], 1e-9) // half-way between 100 and 1000 Hz in log f
        assertEquals(0.0, values[3], 1e-12)
        assertEquals(3.0, values[4], 1e-12)
    }

    @Test
    fun rejectsMalformedProfiles() {
        val bad = listOf("", "Preamp: -3 dB", "Filter 1: ON XYZ Fc 100 Hz Gain 1 dB Q 1", "Filter 1: ON PK Fc 0 Hz Gain 1 dB Q 1")
        for (text in bad) {
            val failed = runCatching { AutoEqParser.parseParametric(text) }.isFailure
            assertTrue("should reject: '$text'", failed)
        }
    }
}
