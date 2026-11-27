package com.stride.domain

import kotlin.math.max
import kotlin.math.min

/** Everything computed from a recorded track: totals, splits, best efforts, chart series. */
object Analyzer {

    /** Time with pauses removed, at each point (ms since start). */
    fun activeTimes(points: List<TrackPoint>): LongArray {
        val out = LongArray(points.size)
        for (i in 1 until points.size) {
            out[i] = out[i - 1] + if (points[i].resume) 0L else (points[i].time - points[i - 1].time).coerceAtLeast(0)
        }
        return out
    }

    fun summarize(points: List<TrackPoint>, sport: Sport): Summary {
        if (points.size < 2) return Summary(0.0, 0, if (points.isEmpty()) 0 else 0, 0.0, 0.0)
        var dist = 0.0
        var moving = 0L
        for (i in 1 until points.size) {
            if (points[i].resume) continue
            val d = Geo.distance(points[i - 1], points[i])
            val dt = points[i].time - points[i - 1].time
            dist += d
            if (dt > 0 && d / (dt / 1000.0) >= sport.stoppedBelow) moving += dt
        }
        val elapsed = points.last().time - points.first().time
        return Summary(dist, moving, elapsed, elevationGain(points), maxSpeed(points))
    }

    /** Climbing with a 3 m dead-band so GPS altitude noise doesn't add up to fake hills. */
    fun elevationGain(points: List<TrackPoint>, threshold: Double = 3.0): Double {
        var gain = 0.0
        var ref = Double.NaN
        for (p in points) {
            val e = p.ele
            if (e.isNaN()) continue
            if (ref.isNaN()) { ref = e; continue }
            if (e - ref >= threshold) { gain += e - ref; ref = e }
            else if (ref - e >= threshold) ref = e
        }
        return gain
    }

    /** Fastest speed held for at least [windowMs] — ignores single-fix GPS spikes. */
    fun maxSpeed(points: List<TrackPoint>, windowMs: Long = 5000): Double {
        if (points.size < 2) return 0.0
        val cum = Geo.cumulative(points)
        val t = activeTimes(points)
        var best = 0.0
        var j = 0
        for (i in points.indices) {
            if (j < i) j = i
            while (j < points.lastIndex && t[j] - t[i] < windowMs) j++
            val dt = t[j] - t[i]
            if (dt >= windowMs) best = max(best, (cum[j] - cum[i]) / (dt / 1000.0))
        }
        return best
    }

    fun calories(summary: Summary, sport: Sport, weightKg: Double): Int =
        (sport.met * weightKg * (summary.movingMs / 3_600_000.0)).toInt()

    // ---- Splits ----------------------------------------------------------------------------

    data class Split(val index: Int, val distanceM: Double, val timeMs: Long, val elevDiffM: Double) {
        val speed: Double get() = if (timeMs > 0) distanceM / (timeMs / 1000.0) else 0.0
    }

    /** Per-km (or per-mile) splits. The last split is the leftover partial distance. */
    fun splits(points: List<TrackPoint>, splitM: Double): List<Split> {
        if (points.size < 2) return emptyList()
        val cum = Geo.cumulative(points)
        val t = activeTimes(points)
        val out = ArrayList<Split>()
        var boundary = splitM
        var prevT = 0.0
        var prevD = 0.0
        var prevE = firstEle(points)
        for (i in 1 until points.size) {
            while (cum[i] >= boundary && cum[i] > cum[i - 1]) {
                val f = (boundary - cum[i - 1]) / (cum[i] - cum[i - 1])
                val bt = t[i - 1] + f * (t[i] - t[i - 1])
                val be = lerpEle(points[i - 1].ele, points[i].ele, f)
                out += Split(out.size + 1, boundary - prevD, (bt - prevT).toLong(), diff(be, prevE))
                prevT = bt; prevD = boundary; if (!be.isNaN()) prevE = be
                boundary += splitM
            }
        }
        val rest = cum.last() - prevD
        if (rest > splitM * 0.05) out += Split(out.size + 1, rest, (t.last() - prevT).toLong(), diff(lastEle(points), prevE))
        return out
    }

    private fun firstEle(p: List<TrackPoint>) = p.firstOrNull { !it.ele.isNaN() }?.ele ?: Double.NaN
    private fun lastEle(p: List<TrackPoint>) = p.lastOrNull { !it.ele.isNaN() }?.ele ?: Double.NaN
    private fun lerpEle(a: Double, b: Double, f: Double) = when {
        a.isNaN() -> b; b.isNaN() -> a; else -> a + (b - a) * f
    }
    private fun diff(a: Double, b: Double) = if (a.isNaN() || b.isNaN()) 0.0 else a - b

    // ---- Best efforts ----------------------------------------------------------------------

    /** Standard distances for personal records (runs/walks), metres → label. */
    val BEST_EFFORT_DISTANCES: List<Pair<Double, String>> = listOf(
        400.0 to "400 m", 804.672 to "½ mile", 1000.0 to "1 km", 1609.344 to "1 mile", 3218.688 to "2 mile",
        5000.0 to "5 km", 10_000.0 to "10 km", 15_000.0 to "15 km", 16_093.44 to "10 mile", 20_000.0 to "20 km",
        21_097.5 to "Half marathon", 30_000.0 to "30 km", 42_195.0 to "Marathon",
    )

    /** For rides and other wheel sports. */
    val RIDE_EFFORT_DISTANCES: List<Pair<Double, String>> = listOf(
        5_000.0 to "5 km", 10_000.0 to "10 km", 20_000.0 to "20 km", 40_000.0 to "40 km", 50_000.0 to "50 km",
        80_000.0 to "80 km", 100_000.0 to "100 km", 160_934.4 to "100 mile",
    )

    fun effortDistancesFor(sport: Sport) = if (sport.usesPace) BEST_EFFORT_DISTANCES else RIDE_EFFORT_DISTANCES

    /**
     * Fastest time over [distanceM] anywhere inside the track (sliding window over cumulative distance).
     * Returns null if the track is shorter than the distance.
     */
    fun bestEffort(points: List<TrackPoint>, distanceM: Double): Long? {
        if (points.size < 2) return null
        val cum = Geo.cumulative(points)
        if (cum.last() < distanceM) return null
        val t = activeTimes(points)
        var best = Long.MAX_VALUE
        var i = 0
        for (j in points.indices) {
            if (cum[j] < distanceM) continue
            // advance i while the window still covers the distance
            while (i + 1 < j && cum[j] - cum[i + 1] >= distanceM) i++
            val covered = cum[j] - cum[i]
            if (covered <= 0) continue
            val time = ((t[j] - t[i]) * (distanceM / covered)).toLong()
            if (time in 1 until best) best = time
        }
        return if (best == Long.MAX_VALUE) null else best
    }

    fun bestEfforts(points: List<TrackPoint>, sport: Sport): List<Pair<String, Long>> =
        effortDistancesFor(sport).mapNotNull { (d, label) -> bestEffort(points, d)?.let { label to it } }

    // ---- Chart series ----------------------------------------------------------------------

    /** x = distance (m), elevation (m or NaN), speed (m/s, smoothed). Downsampled to ~[buckets] points. */
    data class Series(val distance: DoubleArray, val elevation: DoubleArray, val speed: DoubleArray)

    fun series(points: List<TrackPoint>, buckets: Int = 200, smoothMs: Long = 20_000): Series {
        if (points.size < 2) return Series(DoubleArray(0), DoubleArray(0), DoubleArray(0))
        val cum = Geo.cumulative(points)
        val t = activeTimes(points)
        val n = points.size
        val speed = DoubleArray(n)
        var a = 0
        for (i in 0 until n) {
            while (a < i && t[i] - t[a] > smoothMs) a++
            val dt = (t[i] - t[a]) / 1000.0
            speed[i] = if (dt > 0) (cum[i] - cum[a]) / dt else 0.0
        }
        val m = min(buckets, n)
        val dOut = DoubleArray(m); val eOut = DoubleArray(m); val sOut = DoubleArray(m)
        for (b in 0 until m) {
            val idx = ((b.toLong() * (n - 1)) / max(1, m - 1)).toInt()
            dOut[b] = cum[idx]; eOut[b] = points[idx].ele; sOut[b] = speed[idx]
        }
        return Series(dOut, eOut, sOut)
    }
}

/**
 * Cleans the raw GPS stream while recording:
 * - rejects fixes with poor accuracy,
 * - rejects "teleports" faster than the sport could possibly go,
 * - thins out jitter while standing still (keeps one fix every few seconds).
 */
class GpsFilter(private val sport: Sport, private val maxAccuracyM: Float = 30f) {
    private var last: TrackPoint? = null

    fun reset() { last = null }

    fun accept(p: TrackPoint): Boolean {
        if (p.accuracy > maxAccuracyM) return false
        val prev = last
        if (prev == null || p.resume) { last = p; return true }
        val dt = (p.time - prev.time) / 1000.0
        if (dt <= 0) return false
        val d = Geo.distance(prev, p)
        if (d / dt > sport.maxPlausible) return false
        if (d < max(2.0, p.accuracy * 0.3) && dt < 5) return false
        last = p
        return true
    }
}

/**
 * Auto-pause: pauses when you've been (nearly) still for [stillMs], resumes as soon as you move.
 * Feed it every accepted fix; it says whether you're currently auto-paused.
 */
class AutoPause(private val sport: Sport, private val stillMs: Long = 8000) {
    private val window = ArrayDeque<TrackPoint>()
    var paused = false
        private set

    fun update(p: TrackPoint): Boolean {
        window.addLast(p)
        while (window.size > 2 && p.time - window.first().time > stillMs) window.removeFirst()
        val span = p.time - window.first().time
        if (span >= stillMs * 0.75) {
            val d = Geo.pathLength(window.toList().map { it.copy(resume = false) })
            val speed = d / (span / 1000.0)
            paused = if (paused) speed < sport.stoppedBelow * 1.5 else speed < sport.stoppedBelow
        }
        return paused
    }

    fun reset() { window.clear(); paused = false }
}
