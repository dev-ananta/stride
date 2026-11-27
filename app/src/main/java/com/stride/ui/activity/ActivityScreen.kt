package com.stride.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stride.data.Repo
import com.stride.domain.Analyzer
import com.stride.domain.Fmt
import com.stride.domain.Geo
import com.stride.domain.Gpx
import com.stride.domain.TrackPoint
import com.stride.share.Sharing
import com.stride.ui.Nav
import com.stride.ui.Screen
import com.stride.ui.Toasts
import com.stride.ui.components.AreaChart
import com.stride.ui.components.CardBox
import com.stride.ui.components.ConfirmDialog
import com.stride.ui.components.MapDot
import com.stride.ui.components.MapLine
import com.stride.ui.components.OsmMap
import com.stride.ui.components.SectionTitle
import com.stride.ui.components.SportBadge
import com.stride.ui.components.Stat
import com.stride.ui.home.Thumbs
import com.stride.ui.home.dateText
import com.stride.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(nav: Nav, id: String) {
    val lib by Repo.state.collectAsState()
    // Missing = just deleted (this screen may still be animating out), so draw nothing.
    val a = lib.activity(id) ?: return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val u = lib.settings.units
    val points by produceState<List<TrackPoint>?>(null, id, a.sport) { value = Repo.track(id) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val analysis = remember(points, a.sport, u) {
        points?.let { p ->
            Triple(Analyzer.splits(p, Fmt.splitMeters(u)), Analyzer.bestEfforts(p, a.sport), Analyzer.series(p))
        }
    }
    val prLabels = remember(lib.records, id) { lib.prLabels(id) }
    val myEfforts = remember(lib.efforts, id) { lib.efforts.filter { it.activityId == id } }

    fun export(kind: String) = scope.launch {
        val p = points ?: return@launch
        val shared = if (a.hideEnds) Geo.trimEnds(p, lib.settings.hideEndsMeters.toDouble()) else p
        val f = withContext(Dispatchers.IO) {
            when (kind) {
                "gpx" -> Sharing.writeText(ctx, Sharing.fileName(a.name, "gpx"), Gpx.activity(a.name, a.sport, shared, a.notes))
                else -> Sharing.writeText(ctx, Sharing.fileName(a.name, "tcx"),
                    Gpx.tcx(a.sport, shared, Analyzer.summarize(shared, a.sport), Analyzer.calories(a.summary, a.sport, lib.settings.weightKg)))
            }
        }
        Sharing.share(ctx, f, if (kind == "gpx") "application/gpx+xml" else "application/vnd.garmin.tcx+xml", "Export ${kind.uppercase()}")
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(a.sport.label) },
            navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = { nav.push(Screen.ShareActivity(id)) }) { Icon(Icons.Filled.Share, "Share") }
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Edit") }, { menu = false; nav.push(Screen.EditActivity(id)) })
                    DropdownMenuItem({ Text("Export GPX") }, { menu = false; export("gpx") })
                    DropdownMenuItem({ Text("Export TCX") }, { menu = false; export("tcx") })
                    DropdownMenuItem({ Text("Save as route") }, {
                        menu = false
                        scope.launch { points?.let { val r = Repo.addRoute(a.name, a.sport, it); Toasts.show("Saved route “${r.name}”") } }
                    })
                    DropdownMenuItem({ Text("Create segment") }, { menu = false; nav.push(Screen.CreateSegment(id)) })
                    DropdownMenuItem({ Text("Delete", color = MaterialTheme.colorScheme.error) }, { menu = false; confirmDelete = true })
                }
            },
        )
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SportBadge(a.sport, size = 44)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(a.name, style = MaterialTheme.typography.headlineSmall)
                        Text(dateText(a.startTime), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (a.notes.isNotBlank()) Text(a.notes, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(14.dp))
            }
            item {
                val p = points
                Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(18.dp))) {
                    if (p != null && p.size >= 2) {
                        OsmMap(Modifier.fillMaxSize(),
                            lines = listOf(MapLine(p, MaterialTheme.colorScheme.primary, 5f)),
                            dots = listOf(MapDot(p.first().lat, p.first().lon, Palette.Start), MapDot(p.last().lat, p.last().lon, Palette.Finish)),
                            fitTo = p)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            item {
                val s = a.summary
                CardBox {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row {
                            Stat("Distance", Fmt.distanceValue(s.distanceM, u), Modifier.weight(1f), Fmt.distanceUnit(u))
                            if (a.sport.usesPace) Stat("Avg pace", Fmt.paceValue(s.avgSpeed, u), Modifier.weight(1f), "/${Fmt.distanceUnit(u)}")
                            else Stat("Avg speed", Fmt.speedValue(s.avgSpeed, u), Modifier.weight(1f), Fmt.speedUnit(u))
                            Stat("Moving time", Fmt.duration(s.movingMs), Modifier.weight(1f))
                        }
                        Row {
                            Stat("Elevation", Fmt.elevation(s.elevGainM, u), Modifier.weight(1f))
                            Stat("Elapsed", Fmt.duration(s.elapsedMs), Modifier.weight(1f))
                            if (a.sport.usesPace) Stat("Best pace", Fmt.paceValue(s.maxSpeed, u), Modifier.weight(1f), "/${Fmt.distanceUnit(u)}")
                            else Stat("Max speed", Fmt.speedValue(s.maxSpeed, u), Modifier.weight(1f), Fmt.speedUnit(u))
                        }
                        Text("≈ ${Analyzer.calories(s, a.sport, lib.settings.weightKg)} kcal (estimate from sport, time and your weight)",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val an = analysis
            if (an != null) {
                val (splits, efforts, series) = an
                if (efforts.isNotEmpty()) item {
                    SectionTitle("Best efforts")
                    CardBox {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            efforts.forEach { (label, ms) ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(label, Modifier.weight(1f))
                                    if (label in prLabels) {
                                        Icon(Icons.Filled.EmojiEvents, "Personal record", tint = Palette.Gold, modifier = Modifier.size(18.dp))
                                        Text(" PR  ", color = Palette.Gold, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                                    }
                                    Text(Fmt.duration(ms), fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
                if (myEfforts.isNotEmpty()) item {
                    SectionTitle("Segments")
                    CardBox {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            myEfforts.forEach { e ->
                                val seg = lib.segment(e.segmentId) ?: return@forEach
                                val board = lib.leaderboard(e.segmentId)
                                val rank = board.indexOfFirst { it.activityId == e.activityId && it.startTime == e.startTime } + 1
                                Row(Modifier.fillMaxWidth().clickable { nav.push(Screen.SegmentDetail(seg.id)) }, verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(seg.name, fontWeight = FontWeight.SemiBold)
                                        Text("${Fmt.distance(seg.distanceM, u)} · your #$rank of ${board.size}",
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (rank == 1) Icon(Icons.Filled.EmojiEvents, "Segment PR", tint = Palette.Gold, modifier = Modifier.size(18.dp))
                                    Text(" " + Fmt.duration(e.elapsedMs), fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
                if (splits.isNotEmpty()) item {
                    SectionTitle("Splits")
                    CardBox { SplitsTable(splits, a.sport, u) }
                }
                if (series.elevation.any { !it.isNaN() }) item {
                    SectionTitle("Elevation")
                    CardBox { AreaChart(series.distance, series.elevation, Palette.Climb) }
                }
                if (series.speed.isNotEmpty()) item {
                    SectionTitle(if (a.sport.usesPace) "Pace" else "Speed")
                    CardBox { AreaChart(series.distance, series.speed, MaterialTheme.colorScheme.primary) }
                }
            }
            item {
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { nav.push(Screen.ShareActivity(id)) }) { Text("Share image / video") }
                    OutlinedButton(onClick = { export("gpx") }) { Text("Export GPX") }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (confirmDelete) ConfirmDialog("Delete activity?", "“${a.name}” and its GPS track will be permanently deleted from this phone. This can't be undone.", "Delete",
        onConfirm = { scope.launch { nav.pop(); Thumbs.forget(id); Repo.deleteActivity(id); Toasts.show("Deleted") } },
        onDismiss = { confirmDelete = false })
}

@Composable
private fun SplitsTable(splits: List<Analyzer.Split>, sport: com.stride.domain.Sport, u: com.stride.domain.Units) {
    val fastest = splits.filter { it.distanceM > Fmt.splitMeters(u) * 0.5 }.maxOfOrNull { it.speed } ?: 1.0
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Text(Fmt.distanceUnit(u).uppercase(), Modifier.width(36.dp), style = MaterialTheme.typography.labelSmall)
            Text(if (sport.usesPace) "PACE" else "SPEED", Modifier.width(84.dp), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Text("ELEV", style = MaterialTheme.typography.labelSmall)
        }
        splits.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val partial = s.distanceM < Fmt.splitMeters(u) * 0.95
                Text(if (partial) String.format(java.util.Locale.US, "%.1f", s.distanceM / Fmt.splitMeters(u)) else s.index.toString(), Modifier.width(36.dp))
                Text(if (sport.usesPace) Fmt.paceValue(s.speed, u) else Fmt.speedValue(s.speed, u), Modifier.width(84.dp), fontWeight = FontWeight.SemiBold)
                Box(Modifier.weight(1f).height(10.dp)) {
                    Box(Modifier.fillMaxWidth((s.speed / fastest).toFloat().coerceIn(0.05f, 1f)).height(10.dp).clip(RoundedCornerShape(5.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = if (s.speed >= fastest * 0.999) 1f else 0.55f)))
                }
                Text("  ${if (s.elevDiffM >= 0) "+" else ""}${Fmt.elevation(s.elevDiffM, u)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
