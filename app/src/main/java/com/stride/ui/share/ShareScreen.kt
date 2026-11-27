package com.stride.ui.share

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.stride.data.Repo
import com.stride.domain.Geo
import com.stride.domain.TrackPoint
import com.stride.share.CardBg
import com.stride.share.CardData
import com.stride.share.CardSize
import com.stride.share.CardSpec
import com.stride.share.RouteVideo
import com.stride.share.ShareCard
import com.stride.share.Sharing
import com.stride.share.StatKey
import com.stride.ui.Nav
import com.stride.ui.Toasts
import com.stride.ui.components.SectionTitle
import com.stride.ui.home.dateText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val ROUTE_COLORS = listOf(0xFF00BFA6, 0xFFFFFFFF, 0xFFFFC83D, 0xFFFF5C8A, 0xFF7C8CFF, 0xFF34C759).map { it.toInt() }

/** Builds the Story/Reel image or video of an activity and sends it to Instagram or anywhere else. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(nav: Nav, id: String) {
    val lib by Repo.state.collectAsState()
    val a = lib.activity(id) ?: return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val u = lib.settings.units

    val points by produceState<List<TrackPoint>?>(null, id) {
        val p = Repo.track(id)
        value = if (a.hideEnds) Geo.trimEnds(p, lib.settings.hideEndsMeters.toDouble()) else p
    }
    var spec by remember { mutableStateOf(CardSpec()) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var videoProgress by remember { mutableFloatStateOf(0f) }
    var lastVideo by remember { mutableStateOf<File?>(null) }

    val data = remember(points, a, u) {
        points?.let { CardData(a.name, a.sport, dateText(a.startTime), it, a.summary.distanceM, a.summary.movingMs, a.summary.avgSpeed, a.summary.elevGainM, u) }
    }

    LaunchedEffect(spec, data) {
        val d = data ?: return@LaunchedEffect
        preview = withContext(Dispatchers.Default) { ShareCard.render(spec, d) }
        lastVideo = null // settings changed; the old video no longer matches
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val bmp = withContext(Dispatchers.IO) { runCatching { loadPhoto(ctx, uri) }.getOrNull() }
            if (bmp != null) spec = spec.copy(bg = CardBg.PHOTO, photo = bmp) else Toasts.show("Couldn't open that photo")
        }
    }

    suspend fun pngFile(): File? {
        val d = data ?: return null
        val bmp = withContext(Dispatchers.Default) { ShareCard.render(spec, d) }
        return withContext(Dispatchers.IO) { Sharing.writePng(ctx, Sharing.fileName(a.name, "png"), bmp) }
    }

    suspend fun videoFile(): File? {
        lastVideo?.let { if (it.exists()) return it }
        val d = data ?: return null
        busy = "Making video…"; videoProgress = 0f
        val f = File(Sharing.dir(ctx), Sharing.fileName(a.name, "mp4"))
        val ok = withContext(Dispatchers.Default) {
            runCatching { RouteVideo.render(spec.copy(size = if (spec.size == CardSize.SQUARE) CardSize.SQUARE else CardSize.STORY), d, f) { videoProgress = it } }
        }
        busy = null
        return if (ok.isSuccess) f.also { lastVideo = it } else { Toasts.show("This phone couldn't encode the video: ${ok.exceptionOrNull()?.message}"); null }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Share") }, navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
            // ---- preview ----
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val ratio = spec.size.w.toFloat() / spec.size.h
                Box(Modifier.fillMaxWidth(if (spec.size == CardSize.STORY) 0.62f else 0.85f).aspectRatio(ratio).clip(RoundedCornerShape(14.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))) {
                    if (spec.bg == CardBg.TRANSPARENT) Checkerboard(Modifier.fillMaxSize())
                    preview?.let { Image(it.asImageBitmap(), "Preview", Modifier.fillMaxSize()) }
                }
            }
            if (a.hideEnds) Text("Start and finish are hidden (${lib.settings.hideEndsMeters} m). Change this in Edit activity.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))

            // ---- options ----
            SectionTitle("Size")
            ChipRow(CardSize.entries, { it.label }, { it == spec.size }) { spec = spec.copy(size = it) }
            SectionTitle("Background")
            ChipRow(CardBg.entries, { it.label }, { it == spec.bg }) {
                if (it == CardBg.PHOTO) pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                else spec = spec.copy(bg = it)
            }
            if (spec.bg == CardBg.TRANSPARENT) Text("Transparent PNG: in Instagram, open a Story with your own photo or video, then add this image on top from your gallery (sticker ▸ photo).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionTitle("Stats")
            val statLabels = mapOf(StatKey.DISTANCE to "Distance", StatKey.TIME to "Time", StatKey.PACE to if (a.sport.usesPace) "Pace" else "Speed", StatKey.ELEVATION to "Elevation")
            ChipRow(StatKey.entries, { statLabels.getValue(it) }, { it in spec.stats }) {
                spec = spec.copy(stats = if (it in spec.stats) spec.stats - it else spec.stats + it)
            }
            ChipRow(listOf("Title", "Route"), { it }, { if (it == "Title") spec.showTitle else spec.showRoute }) {
                spec = if (it == "Title") spec.copy(showTitle = !spec.showTitle) else spec.copy(showRoute = !spec.showRoute)
            }
            SectionTitle("Route colour")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ROUTE_COLORS.forEach { c ->
                    Box(Modifier.size(34.dp).clip(CircleShape).background(Color(c))
                        .border(if (spec.routeColor == c) 3.dp else 1.dp, if (spec.routeColor == c) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable { spec = spec.copy(routeColor = c) })
                }
            }

            // ---- actions ----
            SectionTitle("Image (PNG)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = busy == null && data != null, onClick = {
                    scope.launch { pngFile()?.let { f -> if (!Sharing.toInstagram(ctx, f, "image/png")) Sharing.share(ctx, f, "image/png", "Share image") } }
                }) { Text("Instagram") }
                OutlinedButton(enabled = busy == null && data != null, onClick = { scope.launch { pngFile()?.let { Sharing.share(ctx, it, "image/png", "Share image") } } }) { Text("Share…") }
                OutlinedButton(enabled = busy == null && data != null, onClick = {
                    scope.launch {
                        val f = pngFile() ?: return@launch
                        val ok = withContext(Dispatchers.IO) { runCatching { Sharing.saveToGallery(ctx, f, "image/png") }.getOrNull() }
                        Toasts.show(if (ok != null) "Saved to Pictures/Stride" else "Couldn't save to gallery")
                    }
                }) { Text("Save") }
            }
            SectionTitle("Animated video for Reels (MP4)")
            Text("The route draws itself while the numbers count up (about 6 seconds, no sound — add music in Instagram).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (busy != null) {
                Text(busy!!, Modifier.padding(top = 8.dp))
                LinearProgressIndicator(progress = { videoProgress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = busy == null && data != null, onClick = {
                    scope.launch { videoFile()?.let { f -> if (!Sharing.toInstagram(ctx, f, "video/mp4")) Sharing.share(ctx, f, "video/mp4", "Share video") } }
                }) { Text("Instagram") }
                OutlinedButton(enabled = busy == null && data != null, onClick = { scope.launch { videoFile()?.let { Sharing.share(ctx, it, "video/mp4", "Share video") } } }) { Text("Share…") }
                OutlinedButton(enabled = busy == null && data != null, onClick = {
                    scope.launch {
                        val f = videoFile() ?: return@launch
                        val ok = withContext(Dispatchers.IO) { runCatching { Sharing.saveToGallery(ctx, f, "video/mp4") }.getOrNull() }
                        Toasts.show(if (ok != null) "Saved to Movies/Stride" else "Couldn't save to gallery")
                    }
                }) { Text("Save") }
            }
            if (spec.bg == CardBg.TRANSPARENT) Text("Videos can't be transparent, so the video uses the dark background.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun <T> ChipRow(items: List<T>, label: (T) -> String, selected: (T) -> Boolean, onClick: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { FilterChip(selected = selected(it), onClick = { onClick(it) }, label = { Text(label(it)) }) }
    }
}

@Composable
private fun Checkerboard(modifier: Modifier) {
    Canvas(modifier) {
        val s = 12.dp.toPx()
        val a = Color(0xFF2A2C30); val b = Color(0xFF3A3D42)
        var y = 0f; var row = 0
        while (y < size.height) {
            var x = 0f; var col = row % 2
            while (x < size.width) { drawRect(if (col % 2 == 0) a else b, Offset(x, y), Size(s, s)); x += s; col++ }
            y += s; row++
        }
    }
}

/** Decodes a gallery photo at a sensible size (and the right way up). */
private fun loadPhoto(ctx: android.content.Context, uri: Uri): Bitmap {
    val max = 2160
    return if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { dec, info, _ ->
            val scale = minOf(1f, max.toFloat() / maxOf(info.size.width, info.size.height))
            dec.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // needed to draw it onto our canvas
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= max) sample *= 2
        ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })!! }
    }
}
