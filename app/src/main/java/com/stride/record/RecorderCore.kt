package com.stride.record

import com.stride.domain.AutoPause
import com.stride.domain.Geo
import com.stride.domain.GpsFilter
import com.stride.domain.RouteFollower
import com.stride.domain.Sport
import com.stride.domain.TrackPoint

enum class RecStatus { IDLE, READY, RECORDING, PAUSED, AUTO_PAUSED, FINISHED }

/** What the Record screen shows. Rebuilt (cheaply) on every GPS fix. */
data class RecState(
    val status: RecStatus = RecStatus.IDLE,
    val sport: Sport = Sport.RUN,
    val startedAt: Long = 0,
    /** All recorded points (the list instance is shared, not copied; treat as read-only). */
    val points: List<TrackPoint> = emptyList(),
    val distanceM: Double = 0.0,
    /** Timer: time spent recording, excluding manual and auto pauses. */
    val activeMs: Long = 0,
    /** Wall-clock time the active timer was last brought up to date, so the UI can tick between fixes. */
    val activeAsOf: Long = 0,
    val elevGainM: Double = 0.0,
    val currentSpeed: Double = 0.0,
    val accuracyM: Float? = null,
    val lastLat: Double? = null,
    val lastLon: Double? = null,
    val routeId: String? = null,
    val routeDoneM: Double? = null,
    val routeLeftM: Double? = null,
    val offRouteM: Double? = null,
    val safetyNote: String? = null,
) {
    val avgSpeed: Double get() = if (activeMs > 0) distanceM / (activeMs / 1000.0) else 0.0
    val isLive: Boolean get() = status == RecStatus.RECORDING || status == RecStatus.PAUSED || status == RecStatus.AUTO_PAUSED

    /** Timer value right now, ticking between GPS fixes while recording. */
    fun activeNow(now: Long): Long = if (status == RecStatus.RECORDING) activeMs + (now - activeAsOf).coerceIn(0, 5_000) else activeMs
}

/**
 * The recording state machine, free of Android so it's unit-testable. The service feeds it raw fixes
 * and clock ticks; it decides what gets recorded, keeps live totals, and reports split crossings.
 */
class RecorderCore(
    private val onPoint: (TrackPoint) -> Unit = {},
    private val onSplit: (index: Int, splitMs: Long, state: RecState) -> Unit = { _, _, _ -> },
) {
    var state = RecState()
        private set

    private val points = ArrayList<TrackPoint>()
    private var filter = GpsFilter(Sport.RUN)
    private var autoPause: AutoPause? = null
    private var route: List<TrackPoint> = emptyList()
    private var splitM = 1000.0
    private var nextSplit = 1000.0
    private var lastSplitActive = 0L
    private var needResume = false
    private var eleRef = Double.NaN
    private val speedWindow = ArrayDeque<TrackPoint>()
    /** Last time the device actually moved (for "hasn't moved" safety alerts). */
    var lastMovedAt = 0L
        private set

    fun prepare(sport: Sport, routeId: String?, routePoints: List<TrackPoint>, splitMeters: Double) {
        reset()
        filter = GpsFilter(sport)
        route = routePoints
        splitM = splitMeters; nextSplit = splitMeters
        state = RecState(RecStatus.READY, sport, routeId = routeId)
    }

    fun start(now: Long, autoPauseOn: Boolean) {
        check(state.status == RecStatus.READY) { "Not ready" }
        autoPause = if (autoPauseOn) AutoPause(state.sport) else null
        lastMovedAt = now
        state = state.copy(status = RecStatus.RECORDING, startedAt = now, activeAsOf = now, points = points)
    }

    /** Continue a recording restored from disk (after a crash or the system killing the service). */
    fun restore(sport: Sport, startedAt: Long, routeId: String?, routePoints: List<TrackPoint>, saved: List<TrackPoint>, splitMeters: Double, autoPauseOn: Boolean, now: Long) {
        reset()
        filter = GpsFilter(sport)
        route = routePoints
        points.addAll(saved)
        splitM = splitMeters
        autoPause = if (autoPauseOn) AutoPause(sport) else null
        var dist = 0.0; var active = 0L
        for (i in 1 until saved.size) {
            if (saved[i].resume) continue
            dist += Geo.distance(saved[i - 1], saved[i])
            active += (saved[i].time - saved[i - 1].time).coerceAtLeast(0)
        }
        nextSplit = (Math.floor(dist / splitM) + 1) * splitM
        lastSplitActive = active
        for (p in saved) trackElevation(p)
        needResume = saved.isNotEmpty()
        lastMovedAt = now
        state = RecState(RecStatus.RECORDING, sport, startedAt, points, dist, active, now, gain, routeId = routeId)
    }

    private var gain = 0.0

    private fun trackElevation(p: TrackPoint) {
        val e = p.ele
        if (e.isNaN()) return
        if (eleRef.isNaN()) { eleRef = e; return }
        if (e - eleRef >= 3.0) { gain += e - eleRef; eleRef = e } else if (eleRef - e >= 3.0) eleRef = e
    }

    fun onFix(raw: TrackPoint) {
        val s = state
        if (s.status == RecStatus.IDLE || s.status == RecStatus.FINISHED) return
        var st = s.copy(accuracyM = raw.accuracy, lastLat = raw.lat, lastLon = raw.lon)

        if (route.size >= 2) RouteFollower.progress(route, raw.lat, raw.lon)?.let {
            st = st.copy(routeDoneM = it.doneM, routeLeftM = it.remainingM, offRouteM = it.offRouteM)
        }

        if (s.status == RecStatus.READY || s.status == RecStatus.PAUSED) { state = st; return }

        // Auto-pause watches every reasonably accurate fix, even ones the jitter filter drops.
        val ap = autoPause
        if (ap != null && raw.accuracy <= 30f) {
            val wasPaused = ap.paused
            val nowPaused = ap.update(raw)
            if (nowPaused && !wasPaused) {
                st = bringTimerUpTo(st, raw.time).copy(status = RecStatus.AUTO_PAUSED)
                needResume = true
            } else if (!nowPaused && wasPaused) {
                st = st.copy(status = RecStatus.RECORDING, activeAsOf = raw.time)
                filter.reset()
            }
        }
        if (st.status == RecStatus.AUTO_PAUSED) { state = st.copy(currentSpeed = 0.0); return }

        val p = if (needResume) raw.copy(resume = true) else raw
        if (!filter.accept(p)) { state = bringTimerUpTo(st, raw.time); return }
        needResume = false

        var dist = st.distanceM
        var active = st.activeMs
        val prev = points.lastOrNull()
        if (prev != null && !p.resume) {
            dist += Geo.distance(prev, p)
            active += (p.time - prev.time).coerceAtLeast(0)
        }
        points.add(p)
        onPoint(p)
        trackElevation(p)

        speedWindow.addLast(p)
        while (speedWindow.size > 2 && p.time - speedWindow.first().time > 10_000) speedWindow.removeFirst()
        val spanS = (p.time - speedWindow.first().time) / 1000.0
        val speed = if (spanS > 0) Geo.pathLength(speedWindow.toList()) / spanS else 0.0
        if (speed >= st.sport.stoppedBelow) lastMovedAt = p.time

        st = st.copy(points = points, distanceM = dist, activeMs = active, activeAsOf = p.time, elevGainM = gain, currentSpeed = speed)
        state = st

        while (dist >= nextSplit) {
            val idx = (nextSplit / splitM).toInt()
            onSplit(idx, active - lastSplitActive, st)
            lastSplitActive = active
            nextSplit += splitM
        }
    }

    private fun bringTimerUpTo(st: RecState, t: Long): RecState =
        if (st.status == RecStatus.RECORDING && t > st.activeAsOf) st.copy(activeMs = st.activeMs + (t - st.activeAsOf).coerceAtMost(5_000), activeAsOf = t) else st

    fun pause(now: Long) {
        if (state.status != RecStatus.RECORDING && state.status != RecStatus.AUTO_PAUSED) return
        state = bringTimerUpTo(state, now).copy(status = RecStatus.PAUSED, currentSpeed = 0.0)
        needResume = true
    }

    fun resume(now: Long) {
        if (state.status != RecStatus.PAUSED) return
        autoPause?.reset(); filter.reset()
        lastMovedAt = now
        state = state.copy(status = RecStatus.RECORDING, activeAsOf = now)
    }

    /** Stops recording; the points stay available for the save screen. */
    fun finish(now: Long): List<TrackPoint> {
        state = bringTimerUpTo(state, now).copy(status = RecStatus.FINISHED, currentSpeed = 0.0)
        return points.toList()
    }

    fun setSafetyNote(note: String?) { state = state.copy(safetyNote = note) }

    fun reset() {
        points.clear(); speedWindow.clear(); autoPause = null; route = emptyList()
        needResume = false; eleRef = Double.NaN; gain = 0.0; lastSplitActive = 0; nextSplit = splitM
        state = RecState()
    }
}
