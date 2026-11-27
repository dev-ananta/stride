package com.stride.domain

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Distances on the Earth's surface and small helpers for polylines. */
object Geo {
    private const val R = 6_371_000.0 // mean Earth radius, metres

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * R * asin(min(1.0, sqrt(a)))
    }

    fun distance(a: TrackPoint, b: TrackPoint) = distance(a.lat, a.lon, b.lat, b.lon)

    /** Length of a path, not counting the jumps across pauses. */
    fun pathLength(points: List<TrackPoint>): Double {
        var d = 0.0
        for (i in 1 until points.size) if (!points[i].resume) d += distance(points[i - 1], points[i])
        return d
    }

    /** Running distance at each point. */
    fun cumulative(points: List<TrackPoint>): DoubleArray {
        val out = DoubleArray(points.size)
        for (i in 1 until points.size) {
            out[i] = out[i - 1] + if (points[i].resume) 0.0 else distance(points[i - 1], points[i])
        }
        return out
    }

    /**
     * Shortest distance (metres) from point P to segment AB. Uses a local flat projection, which is
     * accurate to well under a metre over the short distances involved.
     */
    fun pointToSegment(pLat: Double, pLon: Double, aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val k = cos(Math.toRadians(pLat))
        val m = 111_320.0
        val ax = (aLon - pLon) * k * m; val ay = (aLat - pLat) * m
        val bx = (bLon - pLon) * k * m; val by = (bLat - pLat) * m
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else max(0.0, min(1.0, -(ax * dx + ay * dy) / len2))
        val cx = ax + t * dx; val cy = ay + t * dy
        return sqrt(cx * cx + cy * cy)
    }

    /** Distance from a point to the nearest part of a polyline, searching [from]..[to] indices. */
    fun distanceToPath(lat: Double, lon: Double, path: List<TrackPoint>, from: Int = 0, to: Int = path.lastIndex): Double {
        if (path.isEmpty()) return Double.MAX_VALUE
        if (path.size == 1 || from >= to) return distance(lat, lon, path[from].lat, path[from].lon)
        var best = Double.MAX_VALUE
        for (i in from until to) {
            val d = pointToSegment(lat, lon, path[i].lat, path[i].lon, path[i + 1].lat, path[i + 1].lon)
            if (d < best) best = d
        }
        return best
    }

    /** Douglas–Peucker simplification (keeps shape, drops redundant points) for drawing and storing segments. */
    fun simplify(points: List<TrackPoint>, toleranceM: Double): List<TrackPoint> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true; keep[points.lastIndex] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.lastIndex)
        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeLast()
            var maxD = 0.0; var idx = -1
            for (i in s + 1 until e) {
                val d = pointToSegment(points[i].lat, points[i].lon, points[s].lat, points[s].lon, points[e].lat, points[e].lon)
                if (d > maxD) { maxD = d; idx = i }
            }
            if (idx >= 0 && maxD > toleranceM) {
                keep[idx] = true
                stack.addLast(s to idx); stack.addLast(idx to e)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    data class Bounds(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double)

    fun bounds(points: List<TrackPoint>): Bounds? {
        if (points.isEmpty()) return null
        var a = Double.MAX_VALUE; var b = Double.MAX_VALUE; var c = -Double.MAX_VALUE; var d = -Double.MAX_VALUE
        for (p in points) { a = min(a, p.lat); b = min(b, p.lon); c = max(c, p.lat); d = max(d, p.lon) }
        return Bounds(a, b, c, d)
    }

    /**
     * Drops the first and last [meters] of a track — used before sharing or exporting so the
     * image/file doesn't reveal where you live.
     */
    fun trimEnds(points: List<TrackPoint>, meters: Double): List<TrackPoint> {
        if (meters <= 0 || points.size < 3) return points
        val cum = cumulative(points)
        val total = cum.last()
        if (total <= meters * 2.5) return points // too short to trim meaningfully
        val start = cum.indexOfFirst { it >= meters }.coerceAtLeast(0)
        val end = cum.indexOfLast { it <= total - meters }.coerceAtLeast(start)
        return points.subList(start, end + 1)
    }

    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val y = sin(Math.toRadians(lon2 - lon1)) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
            sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(Math.toRadians(lon2 - lon1))
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }
}
