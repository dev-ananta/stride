package com.stride.ui.routes

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.core.content.ContextCompat
import com.stride.MainActivity
import com.stride.data.Repo
import com.stride.data.RouteSnap
import com.stride.domain.Fmt
import com.stride.domain.Geo
import com.stride.domain.Gpx
import com.stride.domain.Sport
import com.stride.domain.TrackPoint
import com.stride.share.Sharing
import com.stride.ui.Nav
import com.stride.ui.Screen
import com.stride.ui.Toasts
import com.stride.ui.components.CardBox
import com.stride.ui.components.ConfirmDialog
import com.stride.ui.components.EmptyState
import com.stride.ui.components.MapDot
import com.stride.ui.components.MapLine
import com.stride.ui.components.OsmMap
import com.stride.ui.components.RouteThumb
import com.stride.ui.components.SectionTitle
import com.stride.ui.components.SportBadge
import com.stride.ui.components.SportPickerDialog
import com.stride.ui.components.Stat
import com.stride.ui.components.icon
import com.stride.ui.home.Thumbs
import com.stride.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RoutesScreen(nav: Nav) {
    val lib by Repo.state.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val u = lib.settings.units
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { (ctx as? MainActivity)?.importUri(uri) }
    }
    // Personal map: every activity you've recorded, drawn together.
    val everywhere by produceState(emptyList<List<TrackPoint>>(), lib.activities.size) {
        value = lib.activities.take(200).map { Thumbs.get(it.id) }.filter { it.size >= 2 }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Maps", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { nav.push(Screen.RouteBuilder) }) { Icon(Icons.Filled.Add, null); Text(" New route") }
                OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }) { Icon(Icons.Filled.FileOpen, null); Text(" Import GPX/TCX") }
            }
        }
        if (everywhere.isNotEmpty()) item {
            SectionTitle("Everywhere you've been")
            Box(Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(18.dp))) {
                OsmMap(Modifier.fillMaxSize(),
                    lines = everywhere.map { MapLine(it, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f), 3f) },
                    fitTo = everywhere.flatten(), fitKey = everywhere.size)
            }
        }
        item { SectionTitle("Your routes") }
        if (lib.routes.isEmpty()) item {
            EmptyState("No routes yet", "Draw one by tapping points on the map, save one from any activity, or import a GPX file from another app.")
        }
        items(lib.routes, key = { it.id }) { r ->
            CardBox(onClick = { nav.push(Screen.RouteDetail(r.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp), modifier = Modifier.size(72.dp)) {
                        RouteThumb(r.points, Palette.Planned, Modifier.fillMaxSize().padding(6.dp), 2.dp)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text("${r.sport.label} · ${Fmt.distance(r.distanceM, u)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@SuppressLint("MissingPermission")
private fun lastKnown(ctx: Context): TrackPoint? {
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
    val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
    return lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }?.let { TrackPoint(0, it.latitude, it.longitude) }
}

/** Tap the map to add points. With "Follow roads" on, each leg snaps to streets and paths. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteBuilderScreen(nav: Nav) {
    val lib by Repo.state.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val u = lib.settings.units
    val waypoints = remember { mutableStateListOf<TrackPoint>() }
    val legs = remember { mutableStateListOf<List<TrackPoint>>() } // legs[i] goes from waypoint i to i+1
    var snap by remember { mutableStateOf(true) }
    var sport by remember { mutableStateOf(lib.activities.firstOrNull()?.sport ?: Sport.RUN) }
    var pickSport by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var snapFailed by remember { mutableStateOf(false) }

    // Start the map where you are (last known fix), else where your latest activity began.
    val startFallback by produceState(remember { lastKnown(ctx) }) {
        if (value == null) value = lib.activities.firstOrNull()?.let { Repo.track(it.id).firstOrNull() }
    }
    val line = legs.flatten()
    val dist = Geo.pathLength(line)

    fun addPoint(p: TrackPoint) {
        if (working) return
        val prev = waypoints.lastOrNull()
        waypoints.add(p)
        if (prev == null) return
        if (!snap) { legs.add(listOf(prev, p)); return }
        working = true
        scope.launch {
            val leg = RouteSnap.snap(listOf(prev, p), sport)
            if (leg == null) snapFailed = true
            legs.add(leg ?: listOf(prev, p))
            working = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        OsmMap(
            Modifier.fillMaxSize(),
            lines = listOf(MapLine(line, Palette.Planned, 6f)),
            dots = waypoints.mapIndexed { i, w -> MapDot(w.lat, w.lon, if (i == 0) Palette.Start else Palette.Planned, if (i == 0) 16f else 11f) },
            fitTo = startFallback?.let { listOf(it) }, fitKey = startFallback != null,
            initialZoom = if (startFallback != null) 15.0 else 4.0,
            onTap = { lat, lon -> addPoint(TrackPoint(0, lat, lon)) },
        )
        Row(Modifier.statusBarsPadding().padding(10.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(onClick = { nav.pop() }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.width(8.dp))
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainer) {
                Text(if (waypoints.isEmpty()) "Tap the map to start your route" else Fmt.distance(dist, u),
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp), fontWeight = FontWeight.SemiBold)
            }
        }
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)) {
            Column(Modifier.padding(16.dp).navigationBarsPadding()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(onClick = { pickSport = true }, label = { Text(sport.label) }, leadingIcon = { Icon(sport.icon(), null, Modifier.size(18.dp)) })
                    FilterChip(snap, { snap = !snap }, label = { Text("Follow roads & paths") })
                }
                if (snapFailed) Text("Couldn't reach the routing service, so some legs are straight lines (needs internet).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(enabled = waypoints.isNotEmpty() && !working, onClick = {
                        waypoints.removeAt(waypoints.lastIndex); if (legs.size > waypoints.size - 1 && legs.isNotEmpty()) legs.removeAt(legs.lastIndex)
                    }) { Icon(Icons.AutoMirrored.Filled.Undo, null); Text(" Undo") }
                    OutlinedButton(enabled = waypoints.size >= 2 && !working, onClick = { addPoint(waypoints.first()) }) { Icon(Icons.Filled.Loop, null); Text(" Loop") }
                    Spacer(Modifier.weight(1f))
                    Button(enabled = line.size >= 2 && !working, onClick = { saving = true }) { Text("Save") }
                }
                if (snap) Text("Road-following uses the free OpenStreetMap routing service; only your tapped points are sent.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }

    if (pickSport) SportPickerDialog(sport, { sport = it; pickSport = false }, { pickSport = false })
    if (saving) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { saving = false }, title = { Text("Name your route") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("e.g. River loop") }) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val r = Repo.addRoute(name.ifBlank { "Route ${Fmt.distance(dist, u)}" }, sport, line)
                        saving = false; nav.replace(Screen.RouteDetail(r.id))
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { saving = false }) { Text("Cancel") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteDetailScreen(nav: Nav, id: String) {
    val lib by Repo.state.collectAsState()
    val r = lib.route(id) ?: return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val u = lib.settings.units
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(title = { Text(r.name) }, navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
            Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp))) {
                OsmMap(Modifier.fillMaxSize(), lines = listOf(MapLine(r.points, Palette.Planned, 6f)),
                    dots = listOf(MapDot(r.points.first().lat, r.points.first().lon, Palette.Start), MapDot(r.points.last().lat, r.points.last().lon, Palette.Finish)),
                    fitTo = r.points)
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SportBadge(r.sport)
                Spacer(Modifier.width(12.dp))
                Stat("Distance", Fmt.distance(r.distanceM, u), Modifier.weight(1f))
                if (r.points.any { !it.ele.isNaN() }) Stat("Climb", Fmt.elevation(com.stride.domain.Analyzer.elevationGain(r.points), u), Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { nav.push(Screen.Record(r.id)) }) { Text("Record on this route") }
                OutlinedButton(onClick = {
                    scope.launch {
                        val f = withContext(Dispatchers.IO) { Sharing.writeText(ctx, Sharing.fileName(r.name, "gpx"), Gpx.route(r.name, r.points)) }
                        Sharing.share(ctx, f, "application/gpx+xml", "Export route")
                    }
                }) { Text("Export GPX") }
                TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (confirmDelete) ConfirmDialog("Delete route?", "“${r.name}” will be removed. Activities recorded on it are kept.", "Delete",
        onConfirm = { scope.launch { nav.pop(); Repo.deleteRoute(id); Toasts.show("Route deleted") } }, onDismiss = { confirmDelete = false })
}
