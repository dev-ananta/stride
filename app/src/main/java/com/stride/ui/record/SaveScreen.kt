package com.stride.ui.record

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import com.stride.data.Repo
import com.stride.domain.Analyzer
import com.stride.domain.Fmt
import com.stride.record.Recording
import com.stride.ui.Nav
import com.stride.ui.Screen
import com.stride.ui.Tab
import com.stride.ui.Toasts
import com.stride.ui.components.ConfirmDialog
import com.stride.ui.components.MapLine
import com.stride.ui.components.OsmMap
import com.stride.ui.components.SportPickerDialog
import com.stride.ui.components.Stat
import com.stride.ui.components.icon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

@Composable
fun SaveScreen(nav: Nav) {
    val lib by Repo.state.collectAsState()
    val rec by Recording.state.collectAsState()
    val points = remember { Recording.finished }
    val scope = rememberCoroutineScope()
    val u = lib.settings.units

    var sport by remember { mutableStateOf(rec.sport) }
    val hour = points.firstOrNull()?.let { Instant.ofEpochMilli(it.time).atZone(ZoneId.systemDefault()).hour } ?: 12
    var name by remember { mutableStateOf(Fmt.defaultName(sport, hour)) }
    var nameEdited by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf("") }
    var hideEnds by remember { mutableStateOf(true) }
    var pickSport by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val summary = remember(points, sport) { Analyzer.summarize(points, sport) }

    BackHandler { confirmDiscard = true }

    fun done() { Recording.clearFinished(); nav.home(Tab.HOME) }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Save activity", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(14.dp))
        if (points.size >= 2) {
            OsmMap(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(16.dp)),
                lines = listOf(MapLine(points, MaterialTheme.colorScheme.primary, 5f)), fitTo = points, interactive = false)
            Spacer(Modifier.height(14.dp))
        }
        Row(Modifier.fillMaxWidth()) {
            Stat("Distance", Fmt.distanceValue(summary.distanceM, u), Modifier.weight(1f), Fmt.distanceUnit(u))
            Stat("Moving time", Fmt.duration(summary.movingMs), Modifier.weight(1f))
            Stat("Elevation", Fmt.elevation(summary.elevGainM, u), Modifier.weight(1f))
        }
        Spacer(Modifier.height(18.dp))
        OutlinedTextField(name, { name = it; nameEdited = true }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(notes, { notes = it }, label = { Text("How did it go? (optional)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        AssistChip(onClick = { pickSport = true }, label = { Text(sport.label) }, leadingIcon = { Icon(sport.icon(), null, Modifier.size(18.dp)) })
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Hide start & finish when sharing", style = MaterialTheme.typography.bodyLarge)
                Text("Trims the first and last ${lib.settings.hideEndsMeters} m from images and exported files so your home isn't revealed.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(hideEnds, { hideEnds = it })
        }
        Spacer(Modifier.height(20.dp))
        if (points.size < 2) {
            Text("Not enough GPS points were recorded to save this activity.", color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(10.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = points.size >= 2 && !saving, onClick = {
                saving = true
                scope.launch {
                    val r = runCatching { Repo.addActivity(points, sport, name, notes, hideEnds) }
                    r.onSuccess { res ->
                        withContext(Dispatchers.IO) { Repo.store.clearInProgress() }
                        Recording.clearFinished()
                        val prs = res.newPrs.size + res.segmentPrs.size
                        Toasts.show(if (prs > 0) "Saved — ${prs} new personal record${if (prs == 1) "" else "s"}! 🏆" else "Saved")
                        nav.home(Tab.HOME); nav.push(Screen.ActivityDetail(res.activity.id))
                    }.onFailure { Toasts.show("Couldn't save: ${it.message}"); saving = false }
                }
            }) { Text("Save") }
            TextButton(onClick = { confirmDiscard = true }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
        }
    }

    if (pickSport) SportPickerDialog(sport, {
        sport = it; pickSport = false
        if (!nameEdited) name = Fmt.defaultName(it, hour)
    }, { pickSport = false })
    if (confirmDiscard) ConfirmDialog("Discard activity?", "This recording will be deleted. This can't be undone.", "Discard",
        onConfirm = { scope.launch { withContext(Dispatchers.IO) { Repo.store.clearInProgress() }; done() } },
        onDismiss = { confirmDiscard = false })
}
