package io.github.andru1989.xmaudiostudio.ui.equalizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.andru1989.xmaudiostudio.ui.theme.Studio
import kotlin.math.log10
import kotlin.math.roundToInt

/** One curve on the response chart. */
data class ChartCurve(val frequencies: DoubleArray, val gainsDb: DoubleArray, val color: Color, val dashed: Boolean, val width: Float = 2.5f)

private const val MIN_HZ = 20.0
private const val MAX_HZ = 20_000.0

private fun xFor(f: Double, width: Float) = ((log10(f / MIN_HZ) / log10(MAX_HZ / MIN_HZ)) * width).toFloat()

/**
 * Log-frequency response chart. Curves from the device model are drawn dashed (predictions);
 * the caller decides what each curve means and labels it in text next to the chart.
 */
@Composable
fun ResponseChart(
    curves: List<ChartCurve>,
    bandFrequencies: List<Double>,
    bandGainsDb: List<Double>,
    rangeDb: Double,
    modifier: Modifier = Modifier,
) {
    val c = Studio.colors
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = c.muted, fontSize = 9.sp)
    Canvas(modifier.fillMaxWidth().height(190.dp).semantics { contentDescription = "Frequency response chart" }) {
        val w = size.width
        val h = size.height
        fun y(db: Double) = (h / 2 - (db / rangeDb) * (h / 2)).toFloat().coerceIn(0f, h)

        // Grid: band frequencies and every 6 dB.
        bandFrequencies.forEach { f -> drawLine(c.gridLine, Offset(xFor(f, w), 0f), Offset(xFor(f, w), h), 1f) }
        var db = -rangeDb
        while (db <= rangeDb + 1e-9) {
            drawLine(if (db == 0.0) c.muted.copy(alpha = 0.6f) else c.gridLine, Offset(0f, y(db)), Offset(w, y(db)), if (db == 0.0) 1.5f else 1f)
            db += 6.0
        }
        listOf(rangeDb / 2, 0.0, -rangeDb / 2).forEach { d ->
            val text = (if (d > 0) "+" else "") + d.toInt() + " dB"
            drawText(measurer, text, Offset(4.dp.toPx(), y(d) - 12.dp.toPx()), labelStyle)
        }
        curves.forEach { curve -> drawCurve(curve, w, ::y) }
        bandFrequencies.zip(bandGainsDb).forEach { (f, g) ->
            drawCircle(c.deviceEq, radius = 5.dp.toPx(), center = Offset(xFor(f, w), y(g)))
            drawCircle(c.surface, radius = 2.dp.toPx(), center = Offset(xFor(f, w), y(g)))
        }
    }
}

private fun DrawScope.drawCurve(curve: ChartCurve, width: Float, y: (Double) -> Float) {
    if (curve.frequencies.isEmpty()) return
    val path = Path()
    curve.frequencies.forEachIndexed { i, f ->
        val p = Offset(xFor(f, width), y(curve.gainsDb[i]))
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    drawPath(
        path, curve.color,
        style = Stroke(width = curve.width.dp.toPx(), pathEffect = if (curve.dashed) PathEffect.dashPathEffect(floatArrayOf(14f, 8f)) else null),
    )
}

fun frequencyLabel(hz: Double): String = if (hz >= 1000) "${(hz / 1000).let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it) }}k" else hz.roundToInt().toString()

/**
 * A large vertical fader with integer steps. Drag or tap to set; [onFinish] fires once per
 * gesture so the caller can record one undo entry per drag. Exposes progress semantics for
 * TalkBack.
 */
@Composable
fun VerticalFader(
    value: Int,
    range: IntRange,
    label: String,
    enabled: Boolean,
    onChange: (Int) -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Studio.colors
    var dragValue by remember { mutableIntStateOf(value) }
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnFinish by rememberUpdatedState(onFinish)
    val span = (range.last - range.first).toFloat()
    fun valueAt(yPx: Float, heightPx: Float): Int {
        val t = 1f - (yPx / heightPx).coerceIn(0f, 1f)
        return (range.first + t * span).roundToInt().coerceIn(range)
    }
    Column(modifier.width(34.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            (if (value > 0) "+" else "") + value,
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = if (value == 0) c.muted else c.onSurface,
        )
        Box(
            Modifier
                .height(170.dp)
                .width(34.dp)
                .semantics {
                    contentDescription = label
                    stateDescription = "$value dB"
                    if (enabled) {
                        setProgress { target ->
                            val v = target.roundToInt().coerceIn(range)
                            currentOnChange(v)
                            currentOnFinish()
                            true
                        }
                    }
                }
                .pointerInput(enabled, range) {
                    if (!enabled) return@pointerInput
                    detectVerticalDragGestures(
                        onDragStart = { dragValue = valueAt(it.y, size.height.toFloat()); currentOnChange(dragValue) },
                        onDragEnd = { currentOnFinish() },
                        onDragCancel = { currentOnFinish() },
                    ) { change, _ ->
                        val v = valueAt(change.position.y, size.height.toFloat())
                        if (v != dragValue) {
                            dragValue = v
                            currentOnChange(v)
                        }
                    }
                }
                .pointerInput(enabled, range) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(onDoubleTap = { currentOnChange(0); currentOnFinish() }) {
                        currentOnChange(valueAt(it.y, size.height.toFloat()))
                        currentOnFinish()
                    }
                },
        ) {
            Canvas(Modifier.matchParentSize()) {
                val trackW = 6.dp.toPx()
                val x = size.width / 2
                val zeroY = size.height * (range.last / span)
                drawRoundRect(c.gridLine, Offset(x - trackW / 2, 0f), Size(trackW, size.height), CornerRadius(trackW))
                val thumbY = size.height * (1f - (value - range.first) / span)
                val color = if (enabled) c.deviceEq else c.muted
                drawRect(color.copy(alpha = 0.55f), Offset(x - trackW / 2, minOf(zeroY, thumbY)), Size(trackW, kotlin.math.abs(zeroY - thumbY)))
                drawLine(c.muted, Offset(x - 10.dp.toPx(), zeroY), Offset(x + 10.dp.toPx(), zeroY), 1.dp.toPx())
                drawRoundRect(color, Offset(x - 13.dp.toPx(), thumbY - 6.dp.toPx()), Size(26.dp.toPx(), 12.dp.toPx()), CornerRadius(6.dp.toPx()))
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = c.muted, textAlign = TextAlign.Center)
    }
}
