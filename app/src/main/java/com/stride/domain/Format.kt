package com.stride.domain

import java.util.Locale
import kotlin.math.roundToLong

/** All number formatting in one place so metric/imperial stays consistent everywhere. */
object Fmt {
    private const val M_PER_MILE = 1609.344
    private const val FT_PER_M = 3.28084

    fun distance(m: Double, u: Units): String = when (u) {
        Units.METRIC -> if (m < 1000) "${m.roundToLong()} m" else String.format(Locale.US, "%.2f km", m / 1000)
        Units.IMPERIAL -> String.format(Locale.US, "%.2f mi", m / M_PER_MILE)
    }

    /** Number only (for big stat tiles), unit separately via [distanceUnit]. */
    fun distanceValue(m: Double, u: Units): String = when (u) {
        Units.METRIC -> String.format(Locale.US, "%.2f", m / 1000)
        Units.IMPERIAL -> String.format(Locale.US, "%.2f", m / M_PER_MILE)
    }

    fun distanceUnit(u: Units) = if (u == Units.METRIC) "km" else "mi"
    fun splitMeters(u: Units) = if (u == Units.METRIC) 1000.0 else M_PER_MILE

    fun elevation(m: Double, u: Units): String = when (u) {
        Units.METRIC -> "${m.roundToLong()} m"
        Units.IMPERIAL -> "${(m * FT_PER_M).roundToLong()} ft"
    }

    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
    }

    /** "1h 23m" style for summaries. */
    fun durationShort(ms: Long): String {
        val totalMin = ms / 60_000
        val h = totalMin / 60; val m = totalMin % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    /** Pace per km/mi, e.g. "5:12 /km". Returns "–" when not moving. */
    fun pace(speedMs: Double, u: Units): String {
        if (speedMs < 0.3) return "–"
        val secPer = splitMeters(u) / speedMs
        if (secPer > 3600) return "–"
        val total = secPer.roundToLong()
        return String.format(Locale.US, "%d:%02d /%s", total / 60, total % 60, distanceUnit(u))
    }

    fun paceValue(speedMs: Double, u: Units): String = pace(speedMs, u).substringBefore(" /")

    fun speed(speedMs: Double, u: Units): String = when (u) {
        Units.METRIC -> String.format(Locale.US, "%.1f km/h", speedMs * 3.6)
        Units.IMPERIAL -> String.format(Locale.US, "%.1f mph", speedMs * 2.236936)
    }

    fun speedValue(speedMs: Double, u: Units): String = speed(speedMs, u).substringBefore(" ")
    fun speedUnit(u: Units) = if (u == Units.METRIC) "km/h" else "mph"

    /** Pace or speed depending on sport. */
    fun rate(speedMs: Double, sport: Sport, u: Units) = if (sport.usesPace) pace(speedMs, u) else speed(speedMs, u)

    fun goalValue(v: Double, metric: GoalMetric, u: Units): String = when (metric) {
        GoalMetric.DISTANCE -> distance(v, u)
        GoalMetric.TIME -> durationShort(v.toLong())
        GoalMetric.ELEVATION -> elevation(v, u)
        GoalMetric.COUNT -> v.toLong().toString()
    }

    /** Parse what the user typed for a goal target into base units. */
    fun parseGoalTarget(text: String, metric: GoalMetric, u: Units): Double? {
        val n = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
        if (n <= 0) return null
        return when (metric) {
            GoalMetric.DISTANCE -> if (u == Units.METRIC) n * 1000 else n * M_PER_MILE
            GoalMetric.TIME -> n * 3_600_000 // hours
            GoalMetric.ELEVATION -> if (u == Units.METRIC) n else n / FT_PER_M
            GoalMetric.COUNT -> n
        }
    }

    fun goalInputUnit(metric: GoalMetric, u: Units) = when (metric) {
        GoalMetric.DISTANCE -> distanceUnit(u)
        GoalMetric.TIME -> "hours"
        GoalMetric.ELEVATION -> if (u == Units.METRIC) "m" else "ft"
        GoalMetric.COUNT -> "activities"
    }

    /** Default title like "Morning Run". */
    fun defaultName(sport: Sport, hourOfDay: Int): String {
        val part = when (hourOfDay) {
            in 5..10 -> "Morning"
            in 11..13 -> "Lunch"
            in 14..17 -> "Afternoon"
            in 18..21 -> "Evening"
            else -> "Night"
        }
        return "$part ${sport.label}"
    }
}
