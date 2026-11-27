package com.stride.ui.progress

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.stride.data.Repo
import com.stride.domain.Fmt
import com.stride.domain.Goal
import com.stride.domain.GoalMetric
import com.stride.domain.GoalPeriod
import com.stride.domain.Progress
import com.stride.domain.Sport
import com.stride.ui.Nav
import com.stride.ui.Screen
import com.stride.ui.Toasts
import com.stride.ui.components.Bars
import com.stride.ui.components.CardBox
import com.stride.ui.components.EmptyState
import com.stride.ui.components.SectionTitle
import com.stride.ui.components.Stat
import com.stride.ui.home.dateText
import com.stride.ui.theme.Palette
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun ProgressScreen(nav: Nav) {
    val lib by Repo.state.collectAsState()
    val u = lib.settings.units
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val scope = rememberCoroutineScope()
    var addGoal by remember { mutableStateOf(false) }
    var chartMetric by remember { mutableStateOf(GoalMetric.DISTANCE) }
    val sportsWithRecords = remember(lib.records) { Sport.entries.filter { s -> lib.records.keys.any { it.startsWith(s.name + "|") } } }
    var prSport by remember(sportsWithRecords) { mutableStateOf(sportsWithRecords.firstOrNull()) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item { Text("Progress", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 8.dp)) }

        // ---- goals ----
        item {
            SectionTitle("Goals") { IconButton(onClick = { addGoal = true }) { Icon(Icons.Filled.Add, "Add goal") } }
            if (lib.goals.isEmpty()) CardBox(onClick = { addGoal = true }) {
                Text("Set a weekly, monthly or yearly goal — distance, time, elevation or number of activities.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(lib.goals, key = { it.id }) { g ->
            val s = Progress.status(g, lib.activities, today, zone)
            CardBox(Modifier.padding(bottom = 10.dp)) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${g.period.label} · ${g.sport?.label ?: "All sports"}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${Fmt.goalValue(s.current, g.metric, u)} / ${Fmt.goalValue(g.target, g.metric, u)}", style = MaterialTheme.typography.titleLarge)
                        }
                        IconButton(onClick = { scope.launch { Repo.deleteGoal(g.id) } }) { Icon(Icons.Filled.Close, "Remove goal") }
                    }
                    LinearProgressIndicator(progress = { s.fraction.toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = if (s.done) Palette.Gold else MaterialTheme.colorScheme.primary)
                    Text(
                        when {
                            s.done -> "Goal reached! 🎉"
                            s.onTrack -> "On track · ${s.daysLeft} day${if (s.daysLeft == 1) "" else "s"} left"
                            else -> "${Fmt.goalValue(g.target - s.current, g.metric, u)} to go · ${s.daysLeft} day${if (s.daysLeft == 1) "" else "s"} left"
                        },
                        style = MaterialTheme.typography.bodySmall, color = if (s.done) Palette.Gold else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        // ---- training ----
        item {
            SectionTitle("Training")
            CardBox {
                Column {
                    val streak = Progress.weekStreak(lib.activities, today, zone)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.LocalFireDepartment, null, tint = Palette.Gold, modifier = Modifier.size(20.dp))
                        Text(" $streak-week streak", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(GoalMetric.DISTANCE, GoalMetric.TIME, GoalMetric.ELEVATION, GoalMetric.COUNT).forEach { m ->
                            FilterChip(chartMetric == m, { chartMetric = m }, label = { Text(m.label) })
                        }
                    }
                    val weekly = remember(lib.activities, chartMetric) { Progress.weeklyTotals(lib.activities, today, zone, 12, chartMetric) }
                    Bars(weekly.map { it.second }, MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth().height(120.dp).padding(top = 10.dp))
                    Text("This week: ${Fmt.goalValue(weekly.last().second, chartMetric, u)} · 12-week avg: ${Fmt.goalValue(weekly.map { it.second }.average(), chartMetric, u)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    Spacer(Modifier.height(14.dp))
                    ActivityCalendar(Progress.activeDays(lib.activities, zone), today)
                    Spacer(Modifier.height(14.dp))
                    val yearStart = Progress.periodStart(GoalPeriod.YEAR, today)
                    val year = lib.activities.filter { !Progress.dateOf(it.startTime, zone).isBefore(yearStart) }
                    Row {
                        Stat("${today.year} distance", Fmt.distance(year.sumOf { it.summary.distanceM }, u), Modifier.weight(1f))
                        Stat("All-time", Fmt.distance(lib.activities.sumOf { it.summary.distanceM }, u), Modifier.weight(1f))
                        Stat("Activities", lib.activities.size.toString(), Modifier.weight(0.7f))
                    }
                }
            }
        }

        // ---- personal records ----
        item {
            SectionTitle("Personal records")
            if (sportsWithRecords.isEmpty()) EmptyState("No records yet", "Your fastest 1 km, 5 km, mile, 10 km, half marathon (and 20 km, 40 km, 100 km rides…) are tracked automatically.")
            else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sportsWithRecords.forEach { s -> FilterChip(prSport == s, { prSport = s }, label = { Text(s.label) }) }
            }
        }
        prSport?.let { sport ->
            item {
                CardBox(Modifier.padding(top = 8.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        lib.recordsFor(sport).forEach { r ->
                            Row(Modifier.fillMaxWidth().clickable { if (lib.activity(r.activityId) != null) nav.push(Screen.ActivityDetail(r.activityId)) },
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.EmojiEvents, null, tint = Palette.Gold, modifier = Modifier.size(18.dp))
                                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                    Text(r.label, fontWeight = FontWeight.SemiBold)
                                    Text(dateText(r.date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(Fmt.duration(r.timeMs), fontWeight = FontWeight.Bold)
                                    if (r.distanceM > 0) Text(Fmt.rate(r.distanceM / (r.timeMs / 1000.0), sport, u), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---- segments ----
        item {
            SectionTitle("Your segments")
            if (lib.segments.isEmpty()) EmptyState("No segments yet", "Open any activity, tap ⋮ › Create segment, and pick a climb, sprint or favourite stretch.")
        }
        items(lib.segments, key = { it.id }) { seg ->
            val board = lib.leaderboard(seg.id)
            CardBox(Modifier.padding(bottom = 8.dp), onClick = { nav.push(Screen.SegmentDetail(seg.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(seg.name, fontWeight = FontWeight.SemiBold)
                        Text("${seg.sport.label} · ${Fmt.distance(seg.distanceM, u)} · ${board.size} effort${if (board.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(board.firstOrNull()?.let { Fmt.duration(it.elapsedMs) } ?: "–", fontWeight = FontWeight.Bold)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (addGoal) AddGoalDialog(u, onDismiss = { addGoal = false }) { g -> scope.launch { Repo.addGoal(g); Toasts.show("Goal added") }; addGoal = false }
}

/** Last 18 weeks as a grid of days (filled = you were active). */
@Composable
private fun ActivityCalendar(days: Set<LocalDate>, today: LocalDate) {
    val weeks = 18
    val active = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val firstMonday = Progress.periodStart(GoalPeriod.WEEK, today).minusWeeks((weeks - 1).toLong())
    Canvas(Modifier.fillMaxWidth().height(96.dp)) {
        val gap = 3.dp.toPx()
        val cell = minOf((size.width - gap * (weeks - 1)) / weeks, (size.height - gap * 6) / 7)
        for (w in 0 until weeks) for (d in 0 until 7) {
            val date = firstMonday.plusDays((w * 7 + d).toLong())
            if (date.isAfter(today)) continue
            drawRoundRect(if (date in days) active else empty, Offset(w * (cell + gap), d * (cell + gap)), Size(cell, cell), CornerRadius(cell * 0.25f))
        }
    }
    Text("Last 18 weeks", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AddGoalDialog(u: com.stride.domain.Units, onDismiss: () -> Unit, onAdd: (Goal) -> Unit) {
    var metric by remember { mutableStateOf(GoalMetric.DISTANCE) }
    var period by remember { mutableStateOf(GoalPeriod.WEEK) }
    var sport by remember { mutableStateOf<Sport?>(null) }
    var text by remember { mutableStateOf("") }
    val target = Fmt.parseGoalTarget(text, metric, u)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New goal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GoalPeriod.entries.forEach { p ->
                        val label = when (p) { GoalPeriod.WEEK -> "Weekly"; GoalPeriod.MONTH -> "Monthly"; GoalPeriod.YEAR -> "Yearly" }
                        FilterChip(period == p, { period = p }, label = { Text(label) })
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GoalMetric.entries.forEach { m -> FilterChip(metric == m, { metric = m }, label = { Text(m.label) }) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(sport == null, { sport = null }, label = { Text("All sports") })
                    listOf(Sport.RUN, Sport.RIDE, Sport.WALK, Sport.HIKE, Sport.SWIM, Sport.TRAIL_RUN, Sport.MOUNTAIN_BIKE).forEach { s ->
                        FilterChip(sport == s, { sport = s }, label = { Text(s.label) })
                    }
                }
                OutlinedTextField(text, { text = it }, label = { Text("Target (${Fmt.goalInputUnit(metric, u)})") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        },
        confirmButton = { TextButton(enabled = target != null, onClick = { onAdd(Goal("", sport, metric, period, target!!)) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
