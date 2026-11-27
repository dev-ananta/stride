package com.stride.ui.activity

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stride.data.Repo
import com.stride.domain.Analyzer
import com.stride.domain.Fmt
import com.stride.domain.Geo
import com.stride.domain.TrackPoint
import com.stride.ui.Nav
import com.stride.ui.Screen
import com.stride.ui.Toasts
import com.stride.ui.components.AreaChart
import com.stride.ui.components.CardBox
import com.stride.ui.components.ConfirmDialog
import com.stride.ui.components.EmptyState
import com.stride.ui.components.MapDot
import com.stride.ui.components.MapLine
import com.stride.ui.components.OsmMap
import com.stride.ui.components.SectionTitle
import com.stride.ui.components.SportPickerDialog
import com.stride.ui.components.Stat
import com.stride.ui.components.icon
import com.stride.ui.home.dateText
import com.stride.ui.theme.Palette
import kotlinx.coroutines.launch

/** Pick a stretch of an activity with a range slider and save it as a personal segment. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateSegmentScreen(nav: Nav, activityId: String) {
    val lib by Repo.state.collectAsState()
    val a = lib.activity(activityId) ?: return
    val u = lib.settings.units
    val scope = rememberCoroutineScope()
    val points by produceState<List<TrackPoint>?>(null, activityId) { value = Repo.track(activityId) }
    val cum = remember(points) { points?.let { Geo.cumulative(it) } }
    val series = remember(points) { points?.let { Analyzer.series(it) } }
    var range by remember { mutableStateOf(0.3f..0.6f) }
    var name by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    val total = cum?.lastOrNull() ?: 0.0
    val (i0, i1) = remember(range, cum) {
        val c = cum ?: return@remember 0 to 0
        val s = range.start * total; val e = range.endInclusive * total
        c.indexOfFirst { it >= s }.coerceAtLeast(0) to c.indexOfLast { it <= e }.coerceAtLeast(0)
    }
    val part = points?.let { if (i1 > i0) it.subList(i0, i1 + 1) else emptyList() }.orEmpty()
    val partLen = if (cum != null && i1 > i0) cum[i1] - cum[i0] else 0.0

    Scaffold(topBar = {
        TopAppBar(title = { Text("New segment") }, navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
            val p = points
            Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(18.dp))) {
                if (p != null && p.size >= 2) OsmMap(Modifier.fillMaxSize(),
                    lines = listOf(MapLine(p, MaterialTheme.colorScheme.outline, 4f), MapLine(part, Palette.Gold, 7f)),
                    dots = part.firstOrNull()?.let { s -> listOf(MapDot(s.lat, s.lon, Palette.Start), MapDot(part.last().lat, part.last().lon, Palette.Finish)) }.orEmpty(),
                    fitTo = p)
            }
            Spacer(Modifier.height(14.dp))
            Text("Drag the handles to choose the stretch.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            series?.let { s ->
                val y = if (s.elevation.any { !it.isNaN() }) s.elevation else s.speed
                AreaChart(s.distance, y, Palette.Climb, Modifier.fillMaxWidth().height(110.dp).padding(top = 8.dp), highlight = range)
            }
            RangeSlider(range, { range = it }, valueRange = 0f..1f)
            Text("${Fmt.distance(partLen, u)} · from ${Fmt.distance(range.start * total, u)}", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(name, { name = it }, label = { Text("Segment name (e.g. “Park hill climb”)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            Text("Stride will find every time you've done this stretch — in past activities and future ones — and rank your efforts.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            Button(enabled = partLen >= 100 && !saving, onClick = {
                saving = true
                scope.launch {
                    runCatching { Repo.addSegment(name.ifBlank { "${a.name} segment" }, a.sport, part) }
                        .onSuccess { seg -> Toasts.show("Segment saved · ${Repo.state.value.leaderboard(seg.id).size} effort(s) found"); nav.replace(Screen.SegmentDetail(seg.id)) }
                        .onFailure { Toasts.show(it.message ?: "Couldn't save segment"); saving = false }
                }
            }) { Text(if (partLen < 100) "Choose at least 100 m" else "Save segment") }
        }
    }
}

/** A segment and your efforts on it, fastest first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentScreen(nav: Nav, id: String) {
    val lib by Repo.state.collectAsState()
    val seg = lib.segment(id) ?: return
    val u = lib.settings.units
    val scope = rememberCoroutineScope()
    val board = remember(lib.efforts, id) { lib.leaderboard(id) }
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(title = { Text(seg.name) },
            navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = { rename = true }) { Icon(Icons.Filled.Edit, "Rename") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
            })
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(18.dp))) {
                    OsmMap(Modifier.fillMaxSize(), lines = listOf(MapLine(seg.points, Palette.Gold, 6f)),
                        dots = listOf(MapDot(seg.points.first().lat, seg.points.first().lon, Palette.Start), MapDot(seg.points.last().lat, seg.points.last().lon, Palette.Finish)),
                        fitTo = seg.points)
                }
                Row(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                    Stat("Distance", Fmt.distance(seg.distanceM, u), Modifier.weight(1f))
                    Stat("Efforts", board.size.toString(), Modifier.weight(1f))
                    Stat("Best", board.firstOrNull()?.let { Fmt.duration(it.elapsedMs) } ?: "–", Modifier.weight(1f))
                }
                SectionTitle("Your efforts")
                if (board.isEmpty()) EmptyState("No efforts yet", "Next time you cover this stretch it'll show up here automatically.")
            }
            itemsIndexed(board, key = { _, e -> e.activityId + e.startTime }) { i, e ->
                val act = lib.activity(e.activityId)
                CardBox(onClick = { nav.push(Screen.ActivityDetail(e.activityId)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", Modifier.width(32.dp), style = MaterialTheme.typography.titleLarge,
                            color = if (i == 0) Palette.Gold else MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f)) {
                            Text(act?.name ?: "Activity", fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(dateText(e.startTime), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (i == 0) Icon(Icons.Filled.EmojiEvents, "Best", tint = Palette.Gold, modifier = Modifier.size(18.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(Fmt.duration(e.elapsedMs), fontWeight = FontWeight.Bold)
                            val sp = seg.distanceM / (e.elapsedMs / 1000.0).coerceAtLeast(1.0)
                            Text(Fmt.rate(sp, seg.sport, u), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    if (rename) {
        var text by remember { mutableStateOf(seg.name) }
        AlertDialog(onDismissRequest = { rename = false }, title = { Text("Rename segment") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { scope.launch { Repo.renameSegment(id, text.ifBlank { seg.name }) }; rename = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("Cancel") } })
    }
    if (confirmDelete) ConfirmDialog("Delete segment?", "“${seg.name}” and its ${board.size} recorded effort(s) will be removed. Your activities are not affected.", "Delete",
        onConfirm = { scope.launch { nav.pop(); Repo.deleteSegment(id) } }, onDismiss = { confirmDelete = false })
}

/** Rename, change sport, notes, privacy. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditActivityScreen(nav: Nav, id: String) {
    val lib by Repo.state.collectAsState()
    val a = lib.activity(id) ?: return
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(a.name) }
    var notes by remember { mutableStateOf(a.notes) }
    var sport by remember { mutableStateOf(a.sport) }
    var hideEnds by remember { mutableStateOf(a.hideEnds) }
    var pick by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Edit activity") }, navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                TextButton(onClick = {
                    scope.launch { Repo.updateActivity(a.copy(name = name.ifBlank { a.name }, notes = notes, sport = sport, hideEnds = hideEnds)); nav.pop() }
                }) { Text("Save") }
            })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            AssistChip(onClick = { pick = true }, label = { Text(sport.label) }, leadingIcon = { Icon(sport.icon(), null, Modifier.size(18.dp)) })
            if (sport != a.sport) Text("Changing the sport recalculates moving time, personal records and segment matches.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Hide start & finish when sharing", Modifier.weight(1f))
                Switch(hideEnds, { hideEnds = it })
            }
        }
    }
    if (pick) SportPickerDialog(sport, { sport = it; pick = false }, { pick = false })
}
