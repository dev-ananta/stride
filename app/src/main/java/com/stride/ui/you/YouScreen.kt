package com.stride.ui.you

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.stride.MainActivity
import com.stride.data.Backup
import com.stride.data.Repo
import com.stride.domain.Gpx
import com.stride.domain.SafetyContact
import com.stride.domain.SafetyText
import com.stride.domain.Settings
import com.stride.domain.Units
import com.stride.record.Safety
import com.stride.share.Sharing
import com.stride.ui.Nav
import com.stride.ui.Toasts
import com.stride.ui.components.CardBox
import com.stride.ui.components.SectionTitle
import com.stride.ui.home.Thumbs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Composable
fun YouScreen(nav: Nav) {
    val lib by Repo.state.collectAsState()
    val s = lib.settings
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    fun save(n: Settings) { scope.launch { Repo.saveSettings(n) } }

    var name by remember(s.athleteName) { mutableStateOf(s.athleteName) }
    var weight by remember(s.weightKg) { mutableStateOf(s.weightKg.toInt().toString()) }
    var addContact by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var confirmRestore by remember { mutableStateOf<android.net.Uri?>(null) }

    val smsPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        save(Repo.settings().copy(safetyEnabled = true))
        if (!granted) Toasts.show("Without SMS permission, check-ins open your messaging app for you to send.")
    }
    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data
        if (res.resultCode != Activity.RESULT_OK || uri == null) return@rememberLauncherForActivityResult
        // Picking from the Phone table grants one-time access to that row; no contacts permission needed.
        ctx.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val contact = SafetyContact(c.getString(0) ?: "Contact", c.getString(1) ?: "")
                if (contact.phone.isNotBlank()) save(Repo.settings().let { it.copy(safetyContacts = (it.safetyContacts + contact).distinctBy { x -> SafetyText.cleanPhone(x.phone) }.take(5)) })
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { (ctx as? MainActivity)?.importUri(uri) }
    }
    val backupTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            busy = "Backing up…"
            val ok = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { Backup.write(Repo.store.root, it) } } }
            busy = null
            Toasts.show(if (ok.isSuccess) "Backup saved" else "Backup failed: ${ok.exceptionOrNull()?.message}")
        }
    }
    val restoreFrom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> confirmRestore = uri }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("You", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 8.dp))

        SectionTitle("Profile")
        CardBox {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Your name (used in safety texts)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(weight, { weight = it.filter(Char::isDigit).take(3) }, label = { Text("Weight (kg, for calorie estimates)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                LaunchedEffect(name, weight) {
                    kotlinx.coroutines.delay(600) // save after typing pauses
                    val w = weight.toDoubleOrNull()?.takeIf { it in 25.0..300.0 } ?: s.weightKg
                    if (name != s.athleteName || w != s.weightKg) Repo.saveSettings(Repo.settings().copy(athleteName = name.trim(), weightKg = w))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(s.units == Units.METRIC, { save(s.copy(units = Units.METRIC)) }, label = { Text("Kilometres") })
                    FilterChip(s.units == Units.IMPERIAL, { save(s.copy(units = Units.IMPERIAL)) }, label = { Text("Miles") })
                }
            }
        }

        SectionTitle("Recording")
        CardBox {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SwitchRow("Auto-pause", "Pauses the timer when you stop (traffic lights, photos) and resumes when you move.", s.autoPause) { save(s.copy(autoPause = it)) }
                SwitchRow("Voice splits", "Announces each ${if (s.units == Units.METRIC) "kilometre" else "mile"} with your split and average pace.", s.voiceCues) { save(s.copy(voiceCues = it)) }
            }
        }

        SectionTitle("Privacy")
        CardBox {
            Column {
                Text("Hide start & finish: ${if (s.hideEndsMeters == 0) "off" else "${s.hideEndsMeters} m"}", fontWeight = FontWeight.SemiBold)
                Text("Trims this much from the start and end of shared images and exported files (per-activity switch in Edit).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                var v by remember(s.hideEndsMeters) { mutableStateOf(s.hideEndsMeters.toFloat()) }
                Slider(v, { v = it }, valueRange = 0f..1000f, steps = 9, onValueChangeFinished = { save(s.copy(hideEndsMeters = v.toInt())) })
                Text("All data stays on this phone. No account, no uploads, no tracking. Maps are loaded from OpenStreetMap.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        SectionTitle("Safety check-ins")
        CardBox {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Texts up to 5 contacts a map link to where you are: when you start, every few minutes, if you stop moving, if your battery is low, and when you finish. " +
                    "It's a plain SMS, so they don't need any app.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SwitchRow("Send safety texts", if (Safety.canAutoSend(ctx)) "Sent automatically." else "Needs SMS permission to send automatically; otherwise you tap Send.", s.safetyEnabled) { on ->
                    if (on && !Safety.canAutoSend(ctx)) smsPerm.launch(Manifest.permission.SEND_SMS) else save(s.copy(safetyEnabled = on))
                }
                s.safetyContacts.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(c.name, fontWeight = FontWeight.SemiBold); Text(c.phone, style = MaterialTheme.typography.bodySmall) }
                        IconButton(onClick = { save(s.copy(safetyContacts = s.safetyContacts - c)) }) { Icon(Icons.Filled.Close, "Remove ${c.name}") }
                    }
                }
                if (s.safetyContacts.size < 5) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        runCatching { pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) }
                            .onFailure { addContact = true }
                    }) { Text("Pick contact") }
                    TextButton(onClick = { addContact = true }) { Text("Type a number") }
                }
                Text("Location update every", style = MaterialTheme.typography.labelLarge)
                ChoiceRow(listOf(5, 10, 15, 30, 0), s.safetyIntervalMin, { if (it == 0) "Never" else "$it min" }) { save(s.copy(safetyIntervalMin = it)) }
                Text("Alert if I haven't moved for", style = MaterialTheme.typography.labelLarge)
                ChoiceRow(listOf(5, 10, 20, 0), s.stillAlertMin, { if (it == 0) "Off" else "$it min" }) { save(s.copy(stillAlertMin = it)) }
                if (s.safetyContacts.isNotEmpty()) TextButton(onClick = {
                    val text = "Test from Stride: ${s.athleteName.ifBlank { "I" }} added you as a safety contact for workouts."
                    val n = Safety.send(ctx, s.safetyContacts, text)
                    if (n > 0) Toasts.show("Test sent to $n contact(s)") else runCatching { ctx.startActivity(Safety.composeIntent(s.safetyContacts, text)) }
                }) { Text("Send a test text") }
            }
        }

        SectionTitle("Your data")
        CardBox {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Import GPX / TCX file") }
                OutlinedButton(enabled = lib.activities.isNotEmpty() && busy == null, modifier = Modifier.fillMaxWidth(), onClick = {
                    scope.launch {
                        busy = "Exporting…"
                        val f = withContext(Dispatchers.IO) { exportAllGpx(ctx) }
                        busy = null
                        Sharing.share(ctx, f, "application/zip", "Export all activities")
                    }
                }) { Text("Export all activities (GPX .zip)") }
                Text("Works with bulk upload on Strava, Garmin Connect, Komoot and most other apps.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(enabled = busy == null, onClick = { backupTo.launch("Stride-backup-${LocalDate.now()}.zip") }, modifier = Modifier.fillMaxWidth()) { Text("Back up everything (.zip)") }
                OutlinedButton(enabled = busy == null, onClick = { restoreFrom.launch(arrayOf("application/zip", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) { Text("Restore from backup") }
                Text("There's no cloud account, so keep a backup somewhere safe (Drive, a computer…) to move to a new phone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                busy?.let { Text(it) }
            }
        }

        SectionTitle("About")
        Text("Stride 1.0 · Map data © OpenStreetMap contributors (openstreetmap.org/copyright). Road-following routes by FOSSGIS OSRM.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
    }

    if (addContact) {
        var cName by remember { mutableStateOf("") }
        var cPhone by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { addContact = false }, title = { Text("Safety contact") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(cName, { cName = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(cPhone, { cPhone = it }, label = { Text("Mobile number") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                }
            },
            confirmButton = {
                TextButton(enabled = SafetyText.cleanPhone(cPhone).length >= 5, onClick = {
                    save(s.copy(safetyContacts = (s.safetyContacts + SafetyContact(cName.ifBlank { "Contact" }, cPhone.trim())).take(5))); addContact = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { addContact = false }) { Text("Cancel") } })
    }
    confirmRestore?.let { uri ->
        AlertDialog(onDismissRequest = { confirmRestore = null }, title = { Text("Restore backup?") },
            text = { Text("This replaces everything currently in Stride (activities, routes, segments, goals, settings) with the backup. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = null
                    scope.launch {
                        busy = "Restoring…"
                        val r = withContext(Dispatchers.IO) { Backup.restore(ctx, uri) }
                        if (r.isSuccess) { Repo.reload(); Thumbs.clear(); Toasts.show("Restored ${r.getOrNull()} files") }
                        else Toasts.show(r.exceptionOrNull()?.message ?: "Restore failed")
                        busy = null
                    }
                }) { Text("Replace my data", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("Cancel") } })
    }
}

/** One GPX per activity, zipped, with privacy trimming applied where it's switched on. */
private suspend fun exportAllGpx(ctx: android.content.Context): File {
    val st = Repo.state.value
    val out = File(Sharing.dir(ctx), "Stride-activities-${LocalDate.now()}.zip")
    ZipOutputStream(out.outputStream().buffered()).use { zip ->
        val used = HashSet<String>()
        for (a in st.activities) {
            val pts = Repo.track(a.id).let { if (a.hideEnds) com.stride.domain.Geo.trimEnds(it, st.settings.hideEndsMeters.toDouble()) else it }
            if (pts.size < 2) continue
            var n = "${java.time.Instant.ofEpochMilli(a.startTime).atZone(java.time.ZoneId.systemDefault()).toLocalDate()}_${Sharing.fileName(a.name, "gpx")}"
            var k = 2
            while (!used.add(n)) n = n.removeSuffix(".gpx") + "_${k++}.gpx"
            zip.putNextEntry(ZipEntry(n))
            zip.write(Gpx.activity(a.name, a.sport, pts, a.notes).toByteArray())
            zip.closeEntry()
        }
    }
    return out
}

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun ChoiceRow(options: List<Int>, current: Int, label: (Int) -> String, onPick: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> FilterChip(o == current, { onPick(o) }, label = { Text(label(o)) }) }
    }
}
