@file:Suppress("DEPRECATION") // non-mirrored Material icons are fine for sport glyphs

package com.stride.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibleForward
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DownhillSkiing
import androidx.compose.material.icons.filled.ElectricBike
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Kayaking
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.PedalBike
import androidx.compose.material.icons.filled.Pool
import androidx.compose.material.icons.filled.RollerSkating
import androidx.compose.material.icons.filled.Rowing
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stride.domain.Sport
import com.stride.ui.theme.StatNumber

fun Sport.icon(): ImageVector = when (this) {
    Sport.RUN -> Icons.Filled.DirectionsRun
    Sport.TRAIL_RUN -> Icons.Filled.Landscape
    Sport.WALK -> Icons.Filled.DirectionsWalk
    Sport.HIKE -> Icons.Filled.Hiking
    Sport.RIDE -> Icons.Filled.DirectionsBike
    Sport.MOUNTAIN_BIKE -> Icons.Filled.PedalBike
    Sport.E_BIKE -> Icons.Filled.ElectricBike
    Sport.INLINE_SKATE -> Icons.Filled.RollerSkating
    Sport.SKI -> Icons.Filled.DownhillSkiing
    Sport.KAYAK -> Icons.Filled.Kayaking
    Sport.ROW -> Icons.Filled.Rowing
    Sport.SWIM -> Icons.Filled.Pool
    Sport.WHEELCHAIR -> Icons.Filled.AccessibleForward
    Sport.OTHER -> Icons.Filled.FitnessCenter
}

/** Label above, big number below. */
@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier, unit: String? = null, big: Boolean = false) {
    Column(modifier) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = if (big) StatNumber.copy(fontSize = StatNumber.fontSize * 1.6f) else StatNumber, maxLines = 1)
            if (unit != null) {
                Spacer(Modifier.width(3.dp))
                Text(unit, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = if (big) 7.dp else 4.dp))
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun CardBox(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).let { if (onClick != null) it.clickable(onClick = onClick) else it },
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(18.dp),
    ) { Box(Modifier.padding(16.dp)) { content() } }
}

@Composable
fun SportBadge(sport: Sport, tint: Color = MaterialTheme.colorScheme.primary, size: Int = 36) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Icon(sport.icon(), null, tint = tint, modifier = Modifier.size((size * 0.58f).dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SportPickerDialog(current: Sport, onPick: (Sport) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Sport") },
        text = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Sport.entries.forEach { s ->
                    FilterChip(selected = s == current, onClick = { onPick(s) },
                        label = { Text(s.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(s.icon(), null, Modifier.size(18.dp)) })
                }
            }
        },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = true) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) { Spacer(Modifier.size(14.dp)); action() }
    }
}
