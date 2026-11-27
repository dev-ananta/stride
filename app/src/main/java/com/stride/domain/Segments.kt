package com.stride.domain

import kotlin.math.max

/**
 * Finds every time an activity covered a segment.
 *
 * An effort counts when the track:
 *  1. passes within [gateM] of the segment's start,
 *  2. later passes within [gateM] of its end,
 *  3. covers between 80% and 130% of the segment's length in between, and
 *  4. stays within [corridorM] of the segment's line at sample points along it
 *     (so riding the start and end on a different road doesn't count).
 */
object SegmentMatcher {

    fun findEfforts(
        activityId: String,
        track: List<TrackPoint>,
        segment: Segment,
        gateM: Double = 25.0,
        corridorM: Double = 40.0,
    ): List<SegmentEffort> {
        val seg = segment.points
        if (track.size < 2 || seg.size < 2) return emptyList()
        val segLen = segment.distanceM
        if (segLen < 50) return emptyList()
        val cum = Geo.cumulative(track)
        val t = Analyzer.activeTimes(track)
        val start = seg.first(); val end = seg.last()
        val samples = sampleAlong(seg, 8)
        val out = ArrayList<SegmentEffort>()

        var i = 0
        while (i < track.size) {
            if (Geo.distance(track[i], start) > gateM) { i++; continue }
            // Use the closest fix to the start gate within this pass.
            var s = i
            var sd = Geo.distance(track[i], start)
            var k = i + 1
            while (k < track.size && Geo.distance(track[k], start) <= gateM) {
                val d = Geo.distance(track[k], start)
                if (d < sd) { sd = d; s = k }
                k++
            }
            // Look for the end gate within a plausible distance.
            var e = -1
            var j = s + 1
            while (j < track.size && cum[j] - cum[s] <= segLen * 1.3) {
                if (cum[j] - cum[s] >= segLen * 0.8 && Geo.distance(track[j], end) <= gateM) {
                    // closest fix to the end gate in this pass
                    var best = j; var bd = Geo.distance(track[j], end)
                    var q = j + 1
                    while (q < track.size && Geo.distance(track[q], end) <= gateM) {
                        val d = Geo.distance(track[q], end)
                        if (d < bd) { bd = d; best = q }
                        q++
                    }
                    e = best
                    break
                }
                j++
            }
            if (e > s && followsLine(track, s, e, samples, corridorM)) {
                out += SegmentEffort(segment.id, activityId, track[s].time, t[e] - t[s], s, e)
                i = e + 1
            } else {
                i = max(k, i + 1)
            }
        }
        return out
    }

    private fun followsLine(track: List<TrackPoint>, s: Int, e: Int, samples: List<TrackPoint>, corridorM: Double): Boolean =
        samples.all { p -> Geo.distanceToPath(p.lat, p.lon, track, s, e) <= corridorM }

    /** [n] evenly spaced points along a path (by distance), excluding the ends. */
    fun sampleAlong(path: List<TrackPoint>, n: Int): List<TrackPoint> {
        val cum = Geo.cumulative(path)
        val total = cum.last()
        if (total <= 0) return emptyList()
        val out = ArrayList<TrackPoint>()
        var idx = 1
        for (k in 1..n) {
            val target = total * k / (n + 1)
            while (idx < path.lastIndex && cum[idx] < target) idx++
            val a = path[idx - 1]; val b = path[idx]
            val span = cum[idx] - cum[idx - 1]
            val f = if (span > 0) (target - cum[idx - 1]) / span else 0.0
            out += TrackPoint(0, a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
        }
        return out
    }
}

/** Route-following during a recording: how far off the planned line you are and how much is left. */
object RouteFollower {
    data class Progress(val offRouteM: Double, val doneM: Double, val remainingM: Double)

    fun progress(route: List<TrackPoint>, lat: Double, lon: Double): Progress? {
        if (route.size < 2) return null
        val cum = Geo.cumulative(route)
        var bestD = Double.MAX_VALUE
        var bestAlong = 0.0
        for (i in 0 until route.lastIndex) {
            val a = route[i]; val b = route[i + 1]
            val d = Geo.pointToSegment(lat, lon, a.lat, a.lon, b.lat, b.lon)
            if (d < bestD) {
                bestD = d
                val segLen = cum[i + 1] - cum[i]
                val da = Geo.distance(lat, lon, a.lat, a.lon)
                bestAlong = cum[i] + da.coerceAtMost(segLen)
            }
        }
        return Progress(bestD, bestAlong, (cum.last() - bestAlong).coerceAtLeast(0.0))
    }
}
