package com.stride.data

import com.stride.domain.Activity
import com.stride.domain.Analyzer
import com.stride.domain.BestEffortRecord
import com.stride.domain.Fmt
import com.stride.domain.Geo
import com.stride.domain.Goal
import com.stride.domain.Records
import com.stride.domain.Route
import com.stride.domain.Segment
import com.stride.domain.SegmentEffort
import com.stride.domain.SegmentMatcher
import com.stride.domain.Settings
import com.stride.domain.Sport
import com.stride.domain.TrackPoint
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Snapshot of everything the UI shows. Immutable; every change produces a new one. */
data class LibState(
    val activities: List<Activity> = emptyList(),
    val routes: List<Route> = emptyList(),
    val segments: List<Segment> = emptyList(),
    val efforts: List<SegmentEffort> = emptyList(),
    val goals: List<Goal> = emptyList(),
    val records: Map<String, BestEffortRecord> = emptyMap(),
    val settings: Settings = Settings(),
) {
    fun activity(id: String) = activities.firstOrNull { it.id == id }
    fun route(id: String?) = routes.firstOrNull { it.id == id }
    fun segment(id: String) = segments.firstOrNull { it.id == id }

    /** Your efforts on a segment, fastest first. */
    fun leaderboard(segmentId: String) = efforts.filter { it.segmentId == segmentId }.sortedBy { it.elapsedMs }

    /** Records for one sport, in distance order. */
    fun recordsFor(sport: Sport) = records.filterKeys { it.startsWith(sport.name + "|") }.values.sortedBy { it.distanceM }

    /** Labels where [activityId] currently holds the record. */
    fun prLabels(activityId: String) = records.values.filter { it.activityId == activityId }.map { it.label }.toSet()
}

/** Result of saving a new activity, used for the "New PR!" celebration. */
data class SaveResult(val activity: Activity, val newPrs: Set<String>, val segmentPrs: List<String>)

/**
 * All the bookkeeping that happens when activities, segments, etc. change. Synchronous and
 * Android-free (the repository calls it on a background thread).
 */
class Library(private val store: Store, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {
    var state: LibState = LibState()
        private set

    fun load(): LibState {
        state = LibState(
            store.loadActivities(), store.loadRoutes(), store.loadSegments(), store.loadEfforts(),
            store.loadGoals(), store.loadRecords(), store.loadSettings(),
        )
        return state
    }

    private fun newId() = UUID.randomUUID().toString().replace("-", "").take(16)

    private fun sameFamily(a: Sport, b: Sport) = a.usesPace == b.usesPace

    // ---- activities ------------------------------------------------------------------------

    fun addActivity(points: List<TrackPoint>, sport: Sport, name: String?, notes: String = "", hideEnds: Boolean = true): SaveResult {
        require(points.isNotEmpty()) { "Nothing was recorded" }
        val start = points.first().time
        val hour = Instant.ofEpochMilli(start).atZone(zone()).hour
        val a = Activity(newId(), name?.takeIf { it.isNotBlank() } ?: Fmt.defaultName(sport, hour), sport, start,
            Analyzer.summarize(points, sport), notes, hideEnds)
        store.saveTrack(a.id, points)

        val (records, improved) = Records.merge(state.records, a, Analyzer.bestEfforts(points, sport))
        val newEfforts = state.segments.filter { sameFamily(it.sport, sport) }.flatMap { SegmentMatcher.findEfforts(a.id, points, it) }
        val segmentPrs = newEfforts.filter { e ->
            state.efforts.none { it.segmentId == e.segmentId && it.elapsedMs <= e.elapsedMs }
        }.mapNotNull { state.segment(it.segmentId)?.name }.distinct()

        val activities = (listOf(a) + state.activities).sortedByDescending { it.startTime }
        val efforts = state.efforts + newEfforts
        store.saveActivities(activities); store.saveRecords(records); store.saveEfforts(efforts)
        state = state.copy(activities = activities, records = records, efforts = efforts)
        return SaveResult(a, improved, segmentPrs)
    }

    fun updateActivity(a: Activity) {
        val old = state.activity(a.id) ?: return
        val activities = state.activities.map { if (it.id == a.id) a else it }
        store.saveActivities(activities)
        state = state.copy(activities = activities)
        if (old.sport != a.sport) {
            // Summary thresholds (moving time) and record tables depend on sport.
            val pts = store.loadTrack(a.id)
            val fixed = a.copy(summary = Analyzer.summarize(pts, a.sport))
            val list = state.activities.map { if (it.id == a.id) fixed else it }
            store.saveActivities(list)
            state = state.copy(activities = list)
            rebuildRecords(); rematchActivity(a.id)
        }
    }

    fun deleteActivity(id: String) {
        val activities = state.activities.filterNot { it.id == id }
        val efforts = state.efforts.filterNot { it.activityId == id }
        store.saveActivities(activities); store.saveEfforts(efforts); store.deleteTrack(id)
        state = state.copy(activities = activities, efforts = efforts)
        if (state.records.values.any { it.activityId == id }) rebuildRecords()
    }

    fun track(id: String) = store.loadTrack(id)

    /** Recomputes the PR table from every track (after deletes or sport changes). */
    fun rebuildRecords() {
        var table = emptyMap<String, BestEffortRecord>()
        for (a in state.activities) table = Records.merge(table, a, Analyzer.bestEfforts(store.loadTrack(a.id), a.sport)).first
        store.saveRecords(table)
        state = state.copy(records = table)
    }

    private fun rematchActivity(id: String) {
        val a = state.activity(id) ?: return
        val pts = store.loadTrack(id)
        val mine = state.segments.filter { sameFamily(it.sport, a.sport) }.flatMap { SegmentMatcher.findEfforts(id, pts, it) }
        val efforts = state.efforts.filterNot { it.activityId == id } + mine
        store.saveEfforts(efforts)
        state = state.copy(efforts = efforts)
    }

    // ---- segments --------------------------------------------------------------------------

    /** Makes a segment from part of an activity and finds your efforts on it in every past activity. */
    fun addSegment(name: String, sport: Sport, points: List<TrackPoint>): Segment {
        val clean = Geo.simplify(points.map { it.copy(resume = false) }, 3.0).map { TrackPoint(0, it.lat, it.lon, it.ele) }
        require(Geo.pathLength(clean) >= 100) { "Segments must be at least 100 m long" }
        val seg = Segment(newId(), name.ifBlank { "Segment" }, sport, clean, System.currentTimeMillis())
        val found = state.activities.filter { sameFamily(it.sport, sport) }
            .flatMap { SegmentMatcher.findEfforts(it.id, store.loadTrack(it.id), seg) }
        val segments = listOf(seg) + state.segments
        val efforts = state.efforts + found
        store.saveSegments(segments); store.saveEfforts(efforts)
        state = state.copy(segments = segments, efforts = efforts)
        return seg
    }

    fun renameSegment(id: String, name: String) {
        val segments = state.segments.map { if (it.id == id) it.copy(name = name) else it }
        store.saveSegments(segments); state = state.copy(segments = segments)
    }

    fun deleteSegment(id: String) {
        val segments = state.segments.filterNot { it.id == id }
        val efforts = state.efforts.filterNot { it.segmentId == id }
        store.saveSegments(segments); store.saveEfforts(efforts)
        state = state.copy(segments = segments, efforts = efforts)
    }

    // ---- routes ----------------------------------------------------------------------------

    fun addRoute(name: String, sport: Sport, points: List<TrackPoint>): Route {
        val pts = Geo.simplify(points.map { it.copy(resume = false) }, 2.0).map { TrackPoint(0, it.lat, it.lon, it.ele) }
        val r = Route(newId(), name.ifBlank { "Route" }, sport, pts, System.currentTimeMillis())
        val routes = listOf(r) + state.routes
        store.saveRoutes(routes); state = state.copy(routes = routes)
        return r
    }

    fun deleteRoute(id: String) {
        val routes = state.routes.filterNot { it.id == id }
        store.saveRoutes(routes); state = state.copy(routes = routes)
    }

    // ---- goals & settings ------------------------------------------------------------------

    fun addGoal(g: Goal) {
        val goals = state.goals + g.copy(id = g.id.ifBlank { newId() })
        store.saveGoals(goals); state = state.copy(goals = goals)
    }

    fun deleteGoal(id: String) {
        val goals = state.goals.filterNot { it.id == id }
        store.saveGoals(goals); state = state.copy(goals = goals)
    }

    fun saveSettings(s: Settings) { store.saveSettings(s); state = state.copy(settings = s) }
}
