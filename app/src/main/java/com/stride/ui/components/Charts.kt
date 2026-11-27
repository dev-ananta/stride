package com.stride.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stride.domain.TrackPoint
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/** Tile-free route drawing for lists and cards (fast, works offline). */
@Composable
fun RouteThumb(points: List<TrackPoint>, color: Color, modifier: Modifier = Modifier, stroke: Dp = 3.dp) {
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val xy = points.map { p ->
            val x = (p.lon + 180) / 360
            val y = (1 - ln(tan(PI / 4 + Math.toRadians(p.lat) / 2)) / PI) / 2
            x to y
        }
        val minX = xy.minOf { it.first }; val maxX = xy.maxOf { it.first }
        val minY = xy.minOf { it.second }; val maxY = xy.maxOf { it.second }
        val pad = stroke.toPx() * 2
        val w = size.width - 2 * pad; val h = size.height - 2 * pad
        val sx = max(maxX - minX, 1e-9); val sy = max(maxY - minY, 1e-9)
        val scale = min(w / sx, h / sy)
        val ox = pad + (w - sx * scale) / 2; val oy = pad + (h - sy * scale) / 2
        val path = Path()
        xy.forEachIndexed { i, (x, y) ->
            val px = (ox + (x - minX) * scale).toFloat(); val py = (oy + (y - minY) * scale).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, color, style = Stroke(stroke.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * Area chart over distance. Optional [highlight] range (0..1 of the x axis) is shaded — used when
 * choosing a stretch for a new segment.
 */
@Composable
fun AreaChart(
    x: DoubleArray,
    y: DoubleArray,
    color: Color,
    modifier: Modifier = Modifier.fillMaxWidth().height(120.dp),
    highlight: ClosedFloatingPointRange<Float>? = null,
    invert: Boolean = false,
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val pts = x.indices.filter { !y[it].isNaN() }
        if (pts.size < 2) return@Canvas
        val x0 = x.first(); val x1 = max(x.last(), x0 + 1)
        var lo = pts.minOf { y[it] }; var hi = pts.maxOf { y[it] }
        if (hi - lo < 1e-6) { hi += 1; lo -= 1 }
        val padY = (hi - lo) * 0.12
        lo -= padY; hi += padY
        fun px(v: Double) = ((v - x0) / (x1 - x0) * size.width).toFloat()
        fun py(v: Double): Float {
            val f = ((v - lo) / (hi - lo)).toFloat()
            return if (invert) f * size.height else size.height - f * size.height
        }
        for (k in 1..3) drawLine(grid, Offset(0f, size.height * k / 4), Offset(size.width, size.height * k / 4), 1f)

        val line = Path(); val area = Path()
        pts.forEachIndexed { i, idx ->
            val X = px(x[idx]); val Y = py(y[idx])
            if (i == 0) { line.moveTo(X, Y); area.moveTo(X, size.height); area.lineTo(X, Y) } else { line.lineTo(X, Y); area.lineTo(X, Y) }
        }
        area.lineTo(px(x[pts.last()]), size.height); area.close()
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0.02f))))
        drawPath(line, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        if (highlight != null) {
            val a = highlight.start * size.width; val b = highlight.endInclusive * size.width
            drawRect(Color.Black.copy(alpha = 0.35f), Offset(0f, 0f), Size(a, size.height))
            drawRect(Color.Black.copy(alpha = 0.35f), Offset(b, 0f), Size(size.width - b, size.height))
            drawLine(color, Offset(a, 0f), Offset(a, size.height), 2.dp.toPx())
            drawLine(color, Offset(b, 0f), Offset(b, size.height), 2.dp.toPx())
        }
    }
}

/** Simple vertical bars (weekly totals). [highlightLast] colours the current week. */
@Composable
fun Bars(values: List<Double>, color: Color, modifier: Modifier = Modifier.fillMaxWidth().height(110.dp), highlightLast: Boolean = true) {
    val muted = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val maxV = max(values.max(), 1e-9)
        val gap = 6.dp.toPx()
        val bw = (size.width - gap * (values.size - 1)) / values.size
        values.forEachIndexed { i, v ->
            val h = (v / maxV * (size.height - 4)).toFloat().coerceAtLeast(if (v > 0) 4f else 2f)
            val c = if (highlightLast && i == values.lastIndex) color else if (v > 0) color.copy(alpha = 0.45f) else muted
            drawRoundRect(c, Offset(i * (bw + gap), size.height - h), Size(bw, h), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
        }
    }
}
