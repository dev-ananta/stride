package com.stride.domain

/**
 * Core data types. Pure Kotlin (no Android) so everything here is unit-tested on the JVM.
 */

/** Sports Stride can record. [usesPace] = show min/km (run-like) instead of km/h. */
enum class Sport(
    val label: String,
    val usesPace: Boolean,
    /** Below this speed (m/s) you're counted as stopped, for moving time. */
    val stoppedBelow: Double,
    /** Faster than this (m/s) between two GPS fixes is treated as a GPS glitch. */
    val maxPlausible: Double,
    /** Rough MET value for calorie estimates. */
    val met: Double,
) {
    RUN("Run", true, 0.5, 12.0, 9.8),
    TRAIL_RUN("Trail Run", true, 0.4, 12.0, 10.5),
    WALK("Walk", true, 0.3, 4.0, 3.5),
    HIKE("Hike", true, 0.25, 4.0, 6.0),
    RIDE("Ride", false, 1.0, 30.0, 7.5),
    MOUNTAIN_BIKE("Mountain Bike", false, 0.8, 25.0, 8.5),
    E_BIKE("E-Bike Ride", false, 1.0, 30.0, 4.5),
    INLINE_SKATE("Inline Skate", false, 0.8, 20.0, 7.0),
    SKI("Ski", false, 0.8, 40.0, 6.0),
    KAYAK("Kayak", false, 0.3, 8.0, 5.0),
    ROW("Row", false, 0.3, 8.0, 7.0),
    SWIM("Open Water Swim", true, 0.15, 3.0, 8.0),
    WHEELCHAIR("Wheelchair", false, 0.5, 12.0, 6.0),
    OTHER("Workout", false, 0.3, 40.0, 5.0);

    val isFootSport: Boolean get() = this == RUN || this == TRAIL_RUN || this == WALK || this == HIKE
}

/** One GPS fix. [resume] marks the first point after a pause (no distance is counted across the gap). */
data class TrackPoint(
    val time: Long,
    val lat: Double,
    val lon: Double,
    /** Metres above sea level, or NaN if unknown. */
    val ele: Double = Double.NaN,
    val accuracy: Float = 5f,
    val resume: Boolean = false,
)

/** Totals for one activity (cached in the index so lists load instantly). */
data class Summary(
    val distanceM: Double,
    val movingMs: Long,
    val elapsedMs: Long,
    val elevGainM: Double,
    val maxSpeed: Double,
) {
    val avgSpeed: Double get() = if (movingMs > 0) distanceM / (movingMs / 1000.0) else 0.0

    companion object { val EMPTY = Summary(0.0, 0, 0, 0.0, 0.0) }
}

data class Activity(
    val id: String,
    val name: String,
    val sport: Sport,
    val startTime: Long,
    val summary: Summary,
    val notes: String = "",
    /** Hide the first/last few hundred metres on shared images and exports (protects your home address). */
    val hideEnds: Boolean = true,
)

/** A planned route: drawn on the map or saved from an activity. */
data class Route(
    val id: String,
    val name: String,
    val sport: Sport,
    val points: List<TrackPoint>,
    val createdAt: Long,
) {
    val distanceM: Double get() = Geo.pathLength(points)
}

/** A stretch of road/trail you made into a segment. Your efforts on it are found automatically. */
data class Segment(
    val id: String,
    val name: String,
    val sport: Sport,
    val points: List<TrackPoint>,
    val createdAt: Long,
) {
    val distanceM: Double get() = Geo.pathLength(points)
}

/** One time you rode/ran a segment. */
data class SegmentEffort(
    val segmentId: String,
    val activityId: String,
    val startTime: Long,
    val elapsedMs: Long,
    val startIndex: Int,
    val endIndex: Int,
)

enum class GoalMetric(val label: String) { DISTANCE("Distance"), TIME("Moving time"), ELEVATION("Elevation"), COUNT("Activities") }
enum class GoalPeriod(val label: String) { WEEK("This week"), MONTH("This month"), YEAR("This year") }

data class Goal(
    val id: String,
    /** null = all sports */
    val sport: Sport?,
    val metric: GoalMetric,
    val period: GoalPeriod,
    /** metres, milliseconds, metres, or count — matching [metric]. */
    val target: Double,
)

enum class Units { METRIC, IMPERIAL }

data class Settings(
    val units: Units = Units.METRIC,
    val weightKg: Double = 70.0,
    val athleteName: String = "",
    val autoPause: Boolean = true,
    val safetyEnabled: Boolean = false,
    val safetyContacts: List<SafetyContact> = emptyList(),
    val safetyIntervalMin: Int = 15,
    val hideEndsMeters: Int = 200,
    /** Spoken split announcements ("Kilometre 3, pace 5:12"). */
    val voiceCues: Boolean = true,
    /** Text contacts automatically if you haven't moved for this many minutes (0 = off). */
    val stillAlertMin: Int = 10,
)

data class SafetyContact(val name: String, val phone: String)
