package com.stride.ui.record

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.stride.data.Repo
import com.stride.domain.Fmt
import com.stride.domain.Geo
import com.stride.domain.Sport
import com.stride.record.RecStatus
import com.stride.record.Recording
import com.stride.record.Safety
import com.stride.domain.SafetyText
import com.stride.ui.Nav
import com.stride.ui.Screen
import com.stride.ui.components.ConfirmDialog
import com.stride.ui.components.MapDot
import com.stride.ui.components.MapLine
import com.stride.ui.components.OsmMap
import com.stride.ui.components.SportPickerDialog
import com.stride.ui.components.icon
import com.stride.ui.theme.Palette
import com.stride.ui.theme.StatNumber
import kotlinx.coroutines.delay

@Composable
fun RecordScreen(nav: Nav, initialRouteId: String?) {
    val ctx = LocalContext.current
    val lib by Repo.state.collectAsState()
    val rec by Recording.state.collectAsState()
    val u = lib.settings.units

    var sport by rememberSaveable { mutableStateOf(rec.takeIf { it.status != RecStatus.IDLE }?.sport ?: lib.activities.firstOrNull()?.sport ?: Sport.RUN) }
    var routeId by rememberSaveable { mutableStateOf(rec.routeId ?: initialRouteId) }
    var pickSport by remember { mutableStateOf(false) }
    var pickRoute by remember { mutableStateOf(false) }
    var confirmFinish by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }

    fun hasLocation() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) { permissionDenied = false; Recording.prepare(ctx, sport, routeId) }
        else permissionDenied = true
    }

    // Get GPS warmed up as soon as the screen opens (or when sport/route changes before starting).
    LaunchedEffect(sport, routeId) {
        if (rec.isLive) return@LaunchedEffect
        if (hasLocation()) Recording.prepare(ctx, sport, routeId)
        else permLauncher.launch(buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray())
    }

    // Recording finished (from here or elsewhere) → Save screen.
    LaunchedEffect(rec.status) { if (rec.status == RecStatus.FINISHED) nav.replace(Screen.SaveRecording) }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(500) } }

    fun leave() {
        if (!rec.isLive) Recording.cancel(ctx) // stop GPS if we never started
        nav.pop()
    }
    BackHandler { leave() }

    val route = lib.route(routeId)
    // Redrawing thousands of points every second is wasteful; simplify the live line in steps.
    val liveLine = remember(rec.points.size / 15, rec.status) { Geo.simplify(rec.points.toList(), 2.0) }
    val here = if (rec.lastLat != null && rec.lastLon != null) rec.lastLat!! to rec.lastLon!! else null

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxWidth().weight(if (rec.isLive) 0.9f else 1.3f)) {
            OsmMap(
                Modifier.fillMaxSize(),
                lines = buildList {
                    route?.let { add(MapLine(it.points, Palette.Planned, 6f, dashed = true)) }
                    add(MapLine(liveLine, MaterialTheme.colorScheme.primary, 6f))
                },
                dots = buildList {
                    rec.points.firstOrNull()?.let { add(MapDot(it.lat, it.lon, Palette.Start, 12f)) }
                    here?.let { add(MapDot(it.first, it.second, Color(0xFF2F80FF), 18f)) }
                },
                fitTo = route?.points?.takeIf { here == null } ?: here?.let { listOf(com.stride.domain.TrackPoint(0, it.first, it.second)) },
                fitKey = route?.id ?: (here != null),
                follow = if (rec.isLive) here else null,
                initialZoom = 16.0,
            )
            Row(Modifier.statusBarsPadding().padding(10.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(onClick = { leave() }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f))) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.weight(1f))
                GpsPill(rec.accuracyM)
            }
        }

        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp).navigationBarsPadding()) {
                if (!rec.isLive) {
                    // ---- before starting ----
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { pickSport = true }, label = { Text(sport.label) }, leadingIcon = { Icon(sport.icon(), null, Modifier.size(18.dp)) })
                        AssistChip(onClick = { pickRoute = true }, label = { Text(route?.let { "${it.name} · ${Fmt.distance(it.distanceM, u)}" } ?: "No route", maxLines = 1) },
                            leadingIcon = { Icon(Icons.Filled.Route, null, Modifier.size(18.dp)) })
                    }
                    if (lib.settings.safetyEnabled && lib.settings.safetyContacts.isNotEmpty()) {
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Shield, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Text(" Safety texts on · ${lib.settings.safetyContacts.size} contact(s)" +
                                if (!Safety.canAutoSend(ctx)) " (manual — no SMS permission)" else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (permissionDenied) {
                        Text("Stride needs location permission to record. Allow “Precise” location in Settings › Apps › Stride › Permissions.",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(10.dp))
                    }
                    val goodFix = (rec.accuracyM ?: 999f) <= 25f
                    Text(
                        when {
                            rec.accuracyM == null -> "Waiting for GPS… head outside for the best signal."
                            goodFix -> "GPS ready (±${rec.accuracyM!!.toInt()} m)"
                            else -> "Weak GPS (±${rec.accuracyM!!.toInt()} m). You can start now; accuracy improves as you go."
                        },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { Recording.start(ctx) },
                        enabled = rec.status == RecStatus.READY && rec.accuracyM != null,
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                        shape = RoundedCornerShape(32.dp),
                    ) { Text("START", fontWeight = FontWeight.Black, fontSize = 20.sp, letterSpacing = 2.sp) }
                } else {
                    // ---- recording ----
                    val active = rec.activeNow(now)
                    StatusLine(rec.status)
                    Text(Fmt.duration(active), style = StatNumber.copy(fontSize = 56.sp), modifier = Modifier.align(Alignment.CenterHorizontally))
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        BigStat("Distance", Fmt.distanceValue(rec.distanceM, u), Fmt.distanceUnit(u), Modifier.weight(1f))
                        if (rec.sport.usesPace) {
                            BigStat("Pace", Fmt.paceValue(rec.currentSpeed, u), "/${Fmt.distanceUnit(u)}", Modifier.weight(1f))
                            BigStat("Avg pace", Fmt.paceValue(rec.avgSpeed, u), "/${Fmt.distanceUnit(u)}", Modifier.weight(1f))
                        } else {
                            BigStat("Speed", Fmt.speedValue(rec.currentSpeed, u), Fmt.speedUnit(u), Modifier.weight(1f))
                            BigStat("Avg", Fmt.speedValue(rec.avgSpeed, u), Fmt.speedUnit(u), Modifier.weight(1f))
                        }
                    }
                    if (route != null && rec.routeLeftM != null) {
                        val off = (rec.offRouteM ?: 0.0) > 50
                        Text(
                            if (off) "Off route by ${Fmt.distance(rec.offRouteM!!, u)}" else "${Fmt.distance(rec.routeLeftM!!, u)} to go on ${route.name}",
                            color = if (off) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    if (lib.settings.safetyContacts.isNotEmpty()) {
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Shield, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Text(" " + (rec.safetyNote ?: if (lib.settings.safetyEnabled) "Safety texts on" else "Safety texts off"),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 2)
                            TextButton(onClick = {
                                if (Safety.canAutoSend(ctx)) Recording.shareLocationNow(ctx)
                                else {
                                    val s = lib.settings
                                    val text = SafetyText.update(s.athleteName, rec.lastLat ?: return@TextButton, rec.lastLon ?: return@TextButton,
                                        rec.distanceM, active, u, Safety.batteryPercent(ctx))
                                    runCatching { ctx.startActivity(Safety.composeIntent(s.safetyContacts, text)) }
                                }
                            }) { Text("Share location") }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        if (rec.status == RecStatus.PAUSED) {
                            RoundButton("Resume", Icons.Filled.PlayArrow, MaterialTheme.colorScheme.primary) { Recording.resume(ctx) }
                            RoundButton("Finish", Icons.Filled.Stop, MaterialTheme.colorScheme.error) { confirmFinish = true }
                        } else {
                            RoundButton("Pause", Icons.Filled.Pause, MaterialTheme.colorScheme.primary, big = true) { Recording.pause(ctx) }
                        }
                    }
                }
            }
        }
    }

    if (pickSport) SportPickerDialog(sport, { sport = it; pickSport = false }, { pickSport = false })
    if (pickRoute) {
        AlertDialog(
            onDismissRequest = { pickRoute = false },
            title = { Text("Follow a route") },
            text = {
                LazyColumn {
                    item { TextButton(onClick = { routeId = null; pickRoute = false }) { Text("No route") } }
                    items(lib.routes, key = { it.id }) { r ->
                        TextButton(onClick = { routeId = r.id; pickRoute = false }) {
                            Text("${r.name} · ${Fmt.distance(r.distanceM, u)}", modifier = Modifier.fillMaxWidth())
                        }
                    }
                    if (lib.routes.isEmpty()) item { Text("No saved routes yet. Draw one on the Maps tab or import a GPX.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            },
            confirmButton = { TextButton(onClick = { pickRoute = false }) { Text("Close") } },
        )
    }
    if (confirmFinish) ConfirmDialog("Finish activity?", "You'll be able to name it, save it, or discard it next.", "Finish",
        onConfirm = { Recording.finish(ctx) }, onDismiss = { confirmFinish = false }, destructive = false)
}

@Composable
private fun StatusLine(s: RecStatus) {
    val (text, color) = when (s) {
        RecStatus.RECORDING -> "RECORDING" to MaterialTheme.colorScheme.primary
        RecStatus.PAUSED -> "PAUSED" to Palette.Gold
        RecStatus.AUTO_PAUSED -> "AUTO-PAUSED · start moving to resume" to Palette.Gold
        else -> "" to Color.Unspecified
    }
    Text(text, color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
        modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

@Composable
private fun BigStat(label: String, value: String, unit: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = StatNumber.copy(fontSize = 30.sp))
        Text("$label · $unit", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RoundButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, big: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, modifier = Modifier.size(if (big) 84.dp else 74.dp).clip(CircleShape).background(color)) {
            Icon(icon, label, tint = Color.Black, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun GpsPill(acc: Float?) {
    val good = acc != null && acc <= 15
    val ok = acc != null && acc <= 30
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (ok) Icons.Filled.GpsFixed else Icons.Filled.GpsNotFixed, null, Modifier.size(16.dp),
                tint = when { good -> Palette.Start; ok -> Palette.Gold; else -> MaterialTheme.colorScheme.error })
            Spacer(Modifier.width(6.dp))
            Text(if (acc == null) "GPS" else "±${acc.toInt()} m", style = MaterialTheme.typography.labelMedium)
        }
    }
}
