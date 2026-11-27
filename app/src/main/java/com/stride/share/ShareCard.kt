package com.stride.share

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.stride.domain.Fmt
import com.stride.domain.Sport
import com.stride.domain.TrackPoint
import com.stride.domain.Units
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

enum class CardSize(val w: Int, val h: Int, val label: String) {
    STORY(1080, 1920, "Story / Reel 9:16"),
    PORTRAIT(1080, 1350, "Post 4:5"),
    SQUARE(1080, 1080, "Square 1:1"),
}

enum class CardBg(val label: String) {
    TRANSPARENT("Transparent"), DARK("Dark"), GRADIENT("Gradient"), PHOTO("Your photo"),
}

enum class StatKey { DISTANCE, TIME, PACE, ELEVATION }

data class CardSpec(
    val size: CardSize = CardSize.STORY,
    val bg: CardBg = CardBg.TRANSPARENT,
    val stats: Set<StatKey> = setOf(StatKey.DISTANCE, StatKey.TIME, StatKey.PACE),
    val routeColor: Int = 0xFF00BFA6.toInt(),
    val showTitle: Boolean = true,
    val showRoute: Boolean = true,
    val photo: Bitmap? = null,
)

data class CardData(
    val title: String,
    val sport: Sport,
    val dateText: String,
    /** Already trimmed for privacy if "hide start/end" is on. */
    val points: List<TrackPoint>,
    val distanceM: Double,
    val movingMs: Long,
    val avgSpeed: Double,
    val elevGainM: Double,
    val units: Units,
)

/**
 * Draws the shareable route + stats image. The same drawing code renders every frame of the
 * animated video ([progress] < 1 draws the route partway and counts the numbers up).
 */
object ShareCard {
    private val numberFace: Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
    private val labelFace: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun render(spec: CardSpec, data: CardData): Bitmap {
        val bmp = Bitmap.createBitmap(spec.size.w, spec.size.h, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), spec, data, 1f)
        return bmp
    }

    fun draw(c: Canvas, spec: CardSpec, data: CardData, progress: Float, opaque: Boolean = false) {
        val w = spec.size.w.toFloat(); val h = spec.size.h.toFloat()
        drawBackground(c, spec, w, h, opaque)
        val text = Color.WHITE // every background is dark, or a photo with a dark scrim
        val shadow = spec.bg == CardBg.TRANSPARENT || spec.bg == CardBg.PHOTO

        // Layout: title on top, route in the middle, stats at the bottom.
        val pad = w * 0.08f
        var top = h * (if (spec.size == CardSize.STORY) 0.12f else 0.07f)
        if (spec.showTitle) {
            val tp = paint(text, w * 0.058f, numberFace, shadow)
            c.drawText(ellipsize(data.title, tp, w - 2 * pad), pad, top + tp.textSize, tp)
            val dp = paint(withAlpha(text, 200), w * 0.034f, labelFace, shadow)
            c.drawText("${data.sport.label} · ${data.dateText}", pad, top + tp.textSize + dp.textSize * 1.6f, dp)
            top += tp.textSize + dp.textSize * 2.6f
        }

        val statsH = if (spec.stats.isEmpty()) 0f else w * 0.23f
        val bottom = h - h * (if (spec.size == CardSize.STORY) 0.12f else 0.07f) - statsH
        if (spec.showRoute && data.points.size >= 2) {
            drawRoute(c, data.points, RectF(pad, top + w * 0.04f, w - pad, bottom - w * 0.05f), spec.routeColor, progress)
        }
        if (spec.stats.isNotEmpty()) drawStats(c, spec, data, RectF(pad, bottom, w - pad, bottom + statsH), text, shadow, ease(progress))

        // Small wordmark, bottom-centre.
        val wm = paint(withAlpha(text, 170), w * 0.028f, numberFace, shadow).apply { textAlign = Paint.Align.CENTER; letterSpacing = 0.35f }
        c.drawText("STRIDE", w / 2, h - h * 0.035f, wm)
    }

    private fun drawBackground(c: Canvas, spec: CardSpec, w: Float, h: Float, opaque: Boolean) {
        when (if (opaque && spec.bg == CardBg.TRANSPARENT) CardBg.DARK else spec.bg) {
            CardBg.TRANSPARENT -> c.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            CardBg.DARK -> c.drawColor(0xFF101114.toInt())
            CardBg.GRADIENT -> {
                val p = Paint().apply {
                    shader = LinearGradient(0f, 0f, w, h, intArrayOf(0xFF0B3D3A.toInt(), 0xFF0F1B2D.toInt(), 0xFF1E1035.toInt()), null, Shader.TileMode.CLAMP)
                }
                c.drawRect(0f, 0f, w, h, p)
            }
            CardBg.PHOTO -> {
                val photo = spec.photo
                if (photo == null) c.drawColor(0xFF101114.toInt()) else {
                    // centre-crop the photo to the card
                    val scale = max(w / photo.width, h / photo.height)
                    val sw = (w / scale).toInt(); val sh = (h / scale).toInt()
                    val sx = (photo.width - sw) / 2; val sy = (photo.height - sh) / 2
                    c.drawBitmap(photo, Rect(sx, sy, sx + sw, sy + sh), RectF(0f, 0f, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
                    // gentle top/bottom scrims so white text stays readable on bright photos
                    val scrim = Paint().apply {
                        shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(0x88000000.toInt(), 0x00000000, 0x00000000, 0xAA000000.toInt()),
                            floatArrayOf(0f, 0.3f, 0.6f, 1f), Shader.TileMode.CLAMP)
                    }
                    c.drawRect(0f, 0f, w, h, scrim)
                }
            }
        }
    }

    /** Web-Mercator projection so the route has the same shape as on a map. */
    private fun project(p: TrackPoint): Pair<Double, Double> {
        val x = (p.lon + 180) / 360
        val s = tan(PI / 4 + Math.toRadians(p.lat) / 2)
        val y = (1 - ln(s) / PI) / 2
        return x to y
    }

    fun routePath(points: List<TrackPoint>, box: RectF): Path {
        val xy = points.map(::project)
        val minX = xy.minOf { it.first }; val maxX = xy.maxOf { it.first }
        val minY = xy.minOf { it.second }; val maxY = xy.maxOf { it.second }
        val spanX = max(maxX - minX, 1e-9); val spanY = max(maxY - minY, 1e-9)
        val scale = min(box.width() / spanX, box.height() / spanY)
        val ox = box.left + (box.width() - spanX * scale) / 2
        val oy = box.top + (box.height() - spanY * scale) / 2
        val path = Path()
        xy.forEachIndexed { i, (x, y) ->
            val px = (ox + (x - minX) * scale).toFloat(); val py = (oy + (y - minY) * scale).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        return path
    }

    private fun drawRoute(c: Canvas, points: List<TrackPoint>, box: RectF, color: Int, progress: Float) {
        val full = routePath(points, box)
        val pm = PathMeasure(full, false)
        val len = pm.length
        val path = if (progress >= 1f) full else Path().also { pm.getSegment(0f, len * progress.coerceIn(0f, 1f), it, true) }
        val stroke = box.width() * 0.018f
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke * 2.6f; this.color = withAlpha(color, 90)
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            maskFilter = BlurMaskFilter(stroke * 1.6f, BlurMaskFilter.Blur.NORMAL)
        }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; this.color = color
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        c.drawPath(path, glow)
        c.drawPath(path, line)

        val pos = FloatArray(2)
        pm.getPosTan(0f, pos, null)
        dot(c, pos[0], pos[1], stroke * 1.25f, Color.WHITE, color)
        pm.getPosTan(len * progress.coerceIn(0f, 1f), pos, null)
        if (progress >= 1f) dot(c, pos[0], pos[1], stroke * 1.25f, color, Color.WHITE) else dot(c, pos[0], pos[1], stroke * 1.5f, Color.WHITE, color)
    }

    private fun dot(c: Canvas, x: Float, y: Float, r: Float, fill: Int, ring: Int) {
        c.drawCircle(x, y, r * 1.45f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ring })
        c.drawCircle(x, y, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
    }

    private fun drawStats(c: Canvas, spec: CardSpec, d: CardData, box: RectF, text: Int, shadow: Boolean, k: Float) {
        val items = buildList {
            if (StatKey.DISTANCE in spec.stats) add("DISTANCE" to "${Fmt.distanceValue(d.distanceM * k, d.units)} ${Fmt.distanceUnit(d.units)}")
            if (StatKey.TIME in spec.stats) add("TIME" to Fmt.duration((d.movingMs * k).toLong()))
            if (StatKey.PACE in spec.stats) add(
                if (d.sport.usesPace) "PACE" to Fmt.pace(d.avgSpeed, d.units) else "SPEED" to Fmt.speed(d.avgSpeed * k, d.units),
            )
            if (StatKey.ELEVATION in spec.stats) add("ELEV GAIN" to Fmt.elevation(d.elevGainM * k, d.units))
        }
        if (items.isEmpty()) return
        val colW = box.width() / items.size
        val valueSize = box.width() * (if (items.size >= 4) 0.058f else 0.072f)
        val lp = paint(withAlpha(text, 190), box.width() * 0.03f, labelFace, shadow).apply { letterSpacing = 0.12f; textAlign = Paint.Align.CENTER }
        val vp = paint(text, valueSize, numberFace, shadow).apply { textAlign = Paint.Align.CENTER }
        items.forEachIndexed { i, (label, value) ->
            val cx = box.left + colW * i + colW / 2
            c.drawText(label, cx, box.top + lp.textSize, lp)
            val vpFit = Paint(vp).apply { while (measureText(value) > colW * 0.94f && textSize > 20) textSize *= 0.94f }
            c.drawText(value, cx, box.top + lp.textSize * 1.6f + vpFit.textSize, vpFit)
        }
    }

    private fun paint(color: Int, size: Float, face: Typeface, shadow: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; textSize = size; typeface = face
        if (shadow) setShadowLayer(size * 0.12f, 0f, size * 0.04f, 0x99000000.toInt())
    }

    private fun ellipsize(s: String, p: Paint, maxW: Float): String {
        if (p.measureText(s) <= maxW) return s
        var t = s
        while (t.length > 1 && p.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }

    private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

    private fun ease(t: Float): Float = 1f - (1f - t.coerceIn(0f, 1f)).toDouble().pow(3.0).toFloat()
}
