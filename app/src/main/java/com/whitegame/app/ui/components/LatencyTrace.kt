package com.whitegame.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.whitegame.app.ui.theme.Line
import com.whitegame.app.ui.theme.LocalAnimationsEnabled
import com.whitegame.app.ui.theme.Trace
import com.whitegame.app.ui.theme.Trace3

/** Rolling window of latency samples, so the trace survives recomposition. */
class TraceHistory(val capacity: Int = 48) {
    private val samples = mutableStateListOf<Long?>()

    val values: List<Long?> get() = samples

    fun push(v: Long?) {
        samples.add(v)
        while (samples.size > capacity) samples.removeAt(0)
    }

    fun clear() = samples.clear()

    val latest: Long? get() = samples.lastOrNull { it != null }
    val average: Long?
        get() {
            val ok = samples.filterNotNull()
            return if (ok.isEmpty()) null else ok.sum() / ok.size
        }
    val worst: Long? get() = samples.filterNotNull().maxOrNull()
    val best: Long? get() = samples.filterNotNull().minOrNull()
}

@Composable
fun rememberTraceHistory(capacity: Int = 48): TraceHistory =
    remember { TraceHistory(capacity) }

enum class TraceMode { Idle, Sweeping, Live }

/**
 * The app's signature: an oscilloscope line of real latency samples, newest on the right.
 *
 * Idle draws a dead baseline, Sweeping runs a scan bar while the tunnel handshakes, and Live
 * plots measurements. Nothing here is decorative — every point is a measurement that happened.
 */
@Composable
fun LatencyTrace(
    history: TraceHistory,
    mode: TraceMode,
    modifier: Modifier = Modifier,
    height: Dp = 108.dp
) {
    val animate = LocalAnimationsEnabled.current
    // Split rather than branching on a remembered animation: an infinite transition created
    // inside an `if` would leave a dangling slot when the mode changes.
    if (mode == TraceMode.Sweeping && animate) {
        SweepingTrace(modifier, height)
    } else {
        Canvas(modifier.fillMaxWidth().height(height)) {
            drawGuides()
            if (mode == TraceMode.Live) drawSignal(history.values) else drawDeadline()
        }
    }
}

@Composable
private fun SweepingTrace(modifier: Modifier, height: Dp) {
    val sweep by rememberInfiniteTransition(label = "sweep").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "sweepX"
    )
    Canvas(modifier.fillMaxWidth().height(height)) {
        drawGuides()
        drawDeadline()
        drawSweep(sweep)
    }
}

/** One dashed mid-axis and a solid baseline. Anything more competes with the data. */
private fun DrawScope.drawGuides() {
    val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 5.dp.toPx()))
    drawLine(
        Line, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f),
        1f, pathEffect = dash
    )
    drawLine(Line, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 1.5f)
}

private fun DrawScope.drawDeadline() {
    drawLine(
        Trace3.copy(alpha = 0.5f),
        Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f),
        2f
    )
}

private fun DrawScope.drawSweep(progress: Float) {
    val x = size.width * progress
    val w = size.width * 0.22f
    drawRect(
        brush = Brush.horizontalGradient(
            listOf(Color.Transparent, Trace.copy(alpha = 0.18f), Color.Transparent),
            startX = x - w, endX = x + w
        ),
        topLeft = Offset(x - w, 0f),
        size = Size(w * 2, size.height)
    )
    drawLine(Trace.copy(alpha = 0.7f), Offset(x, 0f), Offset(x, size.height), 1.5f)
}

/**
 * Scales to the window's own range so the shape of the variation is visible, which is what
 * a player actually reads — a flat line at 90ms is better news than a spiky one at 60ms.
 */
private fun DrawScope.drawSignal(values: List<Long?>) {
    if (values.isEmpty()) return drawDeadline()
    val present = values.filterNotNull()
    if (present.isEmpty()) return drawDeadline()

    val lo = (present.min().toFloat() * 0.75f).coerceAtLeast(0f)
    val hi = (present.max().toFloat() * 1.15f).coerceAtLeast(lo + 10f)
    val top = 10.dp.toPx()
    val bottom = size.height - 6.dp.toPx()
    val stepX = if (values.size > 1) size.width / (values.size - 1) else size.width

    // Lower latency should sit higher on the chart.
    fun y(v: Long): Float {
        val t = ((v.toFloat() - lo) / (hi - lo)).coerceIn(0f, 1f)
        return top + (bottom - top) * t
    }

    val line = Path()
    val fill = Path()
    var started = false
    var lastX = 0f
    var lastY = 0f

    values.forEachIndexed { i, v ->
        val x = i * stepX
        if (v == null) {
            started = false
            return@forEachIndexed
        }
        val yy = y(v)
        if (!started) {
            line.moveTo(x, yy)
            fill.moveTo(x, bottom)
            fill.lineTo(x, yy)
            started = true
        } else {
            line.lineTo(x, yy)
            fill.lineTo(x, yy)
        }
        lastX = x
        lastY = yy
    }
    if (started) fill.lineTo(lastX, bottom)

    drawPath(
        fill,
        Brush.verticalGradient(listOf(Trace.copy(alpha = 0.16f), Color.Transparent))
    )
    drawPath(
        line, Trace,
        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )

    // Instrument cursor on the newest sample.
    if (started) {
        drawLine(Trace.copy(alpha = 0.25f), Offset(lastX, 0f), Offset(lastX, size.height), 1f)
        val r = 3.dp.toPx()
        drawRect(Trace, topLeft = Offset(lastX - r, lastY - r), size = Size(r * 2, r * 2))
    }
}
