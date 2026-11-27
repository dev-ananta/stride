package com.stride.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Goal progress, weekly totals, streaks and personal-record bookkeeping. */
object Progress {

    fun periodStart(period: GoalPeriod, today: LocalDate): LocalDate = when (period) {
        GoalPeriod.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        GoalPeriod.MONTH -> today.withDayOfMonth(1)
        GoalPeriod.YEAR -> today.withDayOfYear(1)
    }

    fun periodEnd(period: GoalPeriod, today: LocalDate): LocalDate = when (period) {
        GoalPeriod.WEEK -> periodStart(period, today).plusDays(7)
        GoalPeriod.MONTH -> periodStart(period, today).plusMonths(1)
        GoalPeriod.YEAR -> periodStart(period, today).plusYears(1)
    }

    fun value(metric: GoalMetric, a: Activity): Double = when (metric) {
        GoalMetric.DISTANCE -> a.summary.distanceM
        GoalMetric.TIME -> a.summary.movingMs.toDouble()
        GoalMetric.ELEVATION -> a.summary.elevGainM
        GoalMetric.COUNT -> 1.0
    }

    data class GoalStatus(
        val goal: Goal,
        val current: Double,
        val fraction: Double,
        /** Where you'd be if you were exactly on pace for the period (0..1). */
        val expectedFraction: Double,
        val daysLeft: Int,
    ) {
        val done: Boolean get() = current >= goal.target
        val onTrack: Boolean get() = fraction >= expectedFraction
    }

    fun status(goal: Goal, activities: List<Activity>, today: LocalDate, zone: ZoneId): GoalStatus {
        val start = periodStart(goal.period, today)
        val end = periodEnd(goal.period, today)
        val current = activities
            .filter { goal.sport == null || it.sport == goal.sport }
            .filter { val d = dateOf(it.startTime, zone); !d.isBefore(start) && d.isBefore(end) }
            .sumOf { value(goal.metric, it) }
        val totalDays = (end.toEpochDay() - start.toEpochDay()).toDouble()
        val elapsedDays = (today.toEpochDay() - start.toEpochDay() + 1).toDouble()
        val fraction = if (goal.target > 0) current / goal.target else 0.0
        return GoalStatus(goal, current, fraction, (elapsedDays / totalDays).coerceIn(0.0, 1.0),
            (end.toEpochDay() - today.toEpochDay() - 1).toInt().coerceAtLeast(0))
    }

    fun dateOf(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    /** Consecutive weeks (Mon–Sun) with at least one activity, ending this week or last week. */
    fun weekStreak(activities: List<Activity>, today: LocalDate, zone: ZoneId): Int {
        val weeks = activities.map { periodStart(GoalPeriod.WEEK, dateOf(it.startTime, zone)) }.toSet()
        var w = periodStart(GoalPeriod.WEEK, today)
        if (w !in weeks) w = w.minusWeeks(1) // this week not started yet still keeps the streak
        var n = 0
        while (w in weeks) { n++; w = w.minusWeeks(1) }
        return n
    }

    /** Distance per week for the last [weeks] weeks, oldest first. */
    fun weeklyTotals(activities: List<Activity>, today: LocalDate, zone: ZoneId, weeks: Int, metric: GoalMetric = GoalMetric.DISTANCE, sport: Sport? = null): List<Pair<LocalDate, Double>> {
        val thisWeek = periodStart(GoalPeriod.WEEK, today)
        val bins = (weeks - 1 downTo 0).map { thisWeek.minusWeeks(it.toLong()) }
        val sums = DoubleArray(bins.size)
        for (a in activities) {
            if (sport != null && a.sport != sport) continue
            val w = periodStart(GoalPeriod.WEEK, dateOf(a.startTime, zone))
            val idx = bins.indexOf(w)
            if (idx >= 0) sums[idx] += value(metric, a)
        }
        return bins.mapIndexed { i, d -> d to sums[i] }
    }

    /** Activity days in the last [days] days (for the training calendar heat strip). */
    fun activeDays(activities: List<Activity>, zone: ZoneId): Set<LocalDate> = activities.map { dateOf(it.startTime, zone) }.toSet()
}

/** A personal record entry: the fastest time for a distance, and which activity set it. */
data class BestEffortRecord(val label: String, val distanceM: Double, val timeMs: Long, val activityId: String, val date: Long)

object Records {
    /**
     * Merges an activity's efforts into the record table.
     * Returns the new table plus which labels were improved by this activity (for the 🏆 badges).
     */
    fun merge(
        table: Map<String, BestEffortRecord>,
        activity: Activity,
        efforts: List<Pair<String, Long>>,
    ): Pair<Map<String, BestEffortRecord>, Set<String>> {
        val out = table.toMutableMap()
        val improved = HashSet<String>()
        val dist = Analyzer.effortDistancesFor(activity.sport).associate { it.second to it.first }
        for ((label, time) in efforts) {
            val key = recordKey(activity.sport, label)
            val old = out[key]
            if (old == null || time < old.timeMs) {
                out[key] = BestEffortRecord(label, dist[label] ?: 0.0, time, activity.id, activity.startTime)
                improved += label
            }
        }
        return out to improved
    }

    /** Runs and walks share records only within their own sport; rides likewise. */
    fun recordKey(sport: Sport, label: String) = "${sport.name}|$label"
}
