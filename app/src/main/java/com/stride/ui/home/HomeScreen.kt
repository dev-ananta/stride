package com.stride.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stride.data.LibState
import com.stride.data.Repo
import com.stride.domain.Activity
import com.stride.domain.Fmt
import com.stride.domain.GoalPeriod
import com.stride.domain.Progress
import com.stride.domain.TrackPoint
import com.stride.domain.Geo
import com.stride.ui.Nav
import com.stride.ui.Screen
import com.stride.ui.Toasts
import com.stride.ui.components.Bars
import com.stride.ui.components.CardBox
import com.stride.ui.components.EmptyState
import com.stride.ui.components.RouteThumb
import com.stride.ui.components.SportBadge
import com.stride.ui.components.Stat
import com.stride.ui.theme.Palette
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Small cache of simplified tracks for list thumbnails. */
object Thumbs {
    private val cache = HashMap<String, List<TrackPoint>>()
    suspend fun get(id: String): List<TrackPoint> = cache[id] ?: Repo.track(id).let { pts ->
        val len = Geo.pathLength(pts)
        Geo.simplify(pts, (len / 400).coerceIn(2.0, 50.0)).also { cache[id] = it }
    }
    fun forget(id: String) { cache.remove(id) }
    fun clear() { cache.clear() }
}

fun dateText(millis: Long): String = java.time.Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))

@Composable
fun HomeScreen(nav: Nav) {
    val st by Repo.state.collectAsState()
    val recovered by Repo.recovered.collectAsState()
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Stride", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                if (st.settings.athleteName.isNotBlank()) Text("Hi, ${st.settings.athleteName}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        recovered?.let { ip ->
            item {
                CardBox {
                    Column {
                        Text("Unfinished ${ip.sport.label.lowercase()} found", style = MaterialTheme.typography.titleMedium)
                        Text("Stride was closed while recording (${Fmt.distance(Geo.pathLength(ip.points), st.settings.units)} so far). Nothing was lost.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                Repo.dismissRecovered()
                                com.stride.record.Recording.restore(ctx)
                                nav.push(Screen.Record(ip.routeId))
                            }) { Text("Resume") }
                            OutlinedButton(onClick = { scope.launch { Repo.saveRecovered()?.let { nav.push(Screen.ActivityDetail(it.activity.id)) } } }) { Text("Save") }
                            TextButton(onClick = { scope.launch { Repo.discardRecovered(); Toasts.show("Discarded") } }) { Text("Discard") }
                        }
                    }
                }
            }
        }
        item { WeekCard(st) }
        if (st.activities.isEmpty()) {
            item {
                EmptyState("No activities yet", "Tap Record to track your first run, ride or walk. Everything stays on this phone.") {
                    Button(onClick = { nav.push(Screen.Record()) }) { Text("Record an activity") }
                }
            }
        }
        items(st.activities, key = { it.id }) { a -> ActivityCard(a, st, onClick = { nav.push(Screen.ActivityDetail(a.id)) }) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun WeekCard(st: LibState) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val u = st.settings.units
    val weekStart = Progress.periodStart(GoalPeriod.WEEK, today)
    val week = st.activities.filter { !Progress.dateOf(it.startTime, zone).isBefore(weekStart) }
    val weekly = remember(st.activities) { Progress.weeklyTotals(st.activities, today, zone, 12) }
    val streak = remember(st.activities) { Progress.weekStreak(st.activities, today, zone) }
    CardBox {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("This week", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (streak > 0) {
                    Icon(Icons.Filled.LocalFireDepartment, null, tint = Palette.Gold, modifier = Modifier.size(18.dp))
                    Text(" $streak-week streak", style = MaterialTheme.typography.labelLarge, color = Palette.Gold)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                Stat("Distance", Fmt.distanceValue(week.sumOf { it.summary.distanceM }, u), Modifier.weight(1f), Fmt.distanceUnit(u))
                Stat("Time", Fmt.durationShort(week.sumOf { it.summary.movingMs }), Modifier.weight(1f))
                Stat("Elev", Fmt.elevation(week.sumOf { it.summary.elevGainM }, u), Modifier.weight(1f))
                Stat("Activities", week.size.toString(), Modifier.weight(0.8f))
            }
            Spacer(Modifier.height(14.dp))
            Bars(weekly.map { it.second }, MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth().height(56.dp))
            Text("Last 12 weeks · distance", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))
            // Nearest goal, if any
            st.goals.firstOrNull()?.let { g ->
                val s = Progress.status(g, st.activities, today, zone)
                Spacer(Modifier.height(10.dp))
                Text("${g.period.label}: ${Fmt.goalValue(s.current, g.metric, u)} of ${Fmt.goalValue(g.target, g.metric, u)}" +
                    (g.sport?.let { " · ${it.label}" } ?: "") + if (s.done) " ✓" else "",
                    style = MaterialTheme.typography.bodyMedium, color = if (s.done) Palette.Gold else MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { s.fraction.toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                )
            }
        }
    }
}

@Composable
fun ActivityCard(a: Activity, st: LibState, onClick: () -> Unit) {
    val u = st.settings.units
    val thumb by produceState(emptyList<TrackPoint>(), a.id) { value = Thumbs.get(a.id) }
    val prs = remember(st.records, a.id) { st.prLabels(a.id) }
    CardBox(onClick = onClick) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SportBadge(a.sport)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(dateText(a.startTime), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (prs.isNotEmpty()) {
                    Surface(color = Palette.Gold.copy(alpha = 0.18f), shape = RoundedCornerShape(50)) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.EmojiEvents, null, tint = Palette.Gold, modifier = Modifier.size(14.dp))
                            Text(" ${prs.size} PR", style = MaterialTheme.typography.labelMedium, color = Palette.Gold, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                Stat("Distance", Fmt.distanceValue(a.summary.distanceM, u), Modifier.weight(1f), Fmt.distanceUnit(u))
                if (a.sport.usesPace) Stat("Pace", Fmt.paceValue(a.summary.avgSpeed, u), Modifier.weight(1f), "/${Fmt.distanceUnit(u)}")
                else Stat("Speed", Fmt.speedValue(a.summary.avgSpeed, u), Modifier.weight(1f), Fmt.speedUnit(u))
                Stat("Time", Fmt.duration(a.summary.movingMs), Modifier.weight(1f))
            }
            if (thumb.size >= 2) {
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(130.dp).clip(RoundedCornerShape(12.dp))) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxSize()) {}
                    RouteThumb(thumb, MaterialTheme.colorScheme.primary, Modifier.fillMaxSize().padding(10.dp))
                }
            }
        }
    }
}
