package com.stride

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.stride.data.Repo
import com.stride.domain.Sport
import com.stride.record.RecStatus
import com.stride.record.Recording
import com.stride.ui.Nav
import com.stride.ui.Screen
import com.stride.ui.Tab
import com.stride.ui.Toasts
import com.stride.ui.activity.ActivityScreen
import com.stride.ui.activity.CreateSegmentScreen
import com.stride.ui.activity.EditActivityScreen
import com.stride.ui.activity.SegmentScreen
import com.stride.ui.home.HomeScreen
import com.stride.ui.progress.ProgressScreen
import com.stride.ui.record.RecordScreen
import com.stride.ui.record.SaveScreen
import com.stride.ui.routes.RouteBuilderScreen
import com.stride.ui.routes.RouteDetailScreen
import com.stride.ui.routes.RoutesScreen
import com.stride.ui.share.ShareScreen
import com.stride.ui.theme.StrideTheme
import com.stride.ui.you.YouScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val nav = Nav()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { StrideTheme { App(nav) } }

        lifecycleScope.launch {
            Repo.loaded.first { it }
            Repo.checkForRecovered(Recording.serviceRunning)
            routeToRecording()
            handleIncoming(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
        routeToRecording()
    }

    /** Opening the app mid-activity goes straight back to the Record (or Save) screen. */
    private fun routeToRecording() {
        val st = Recording.state.value
        when {
            st.isLive && nav.stack.none { it is Screen.Record } -> nav.push(Screen.Record(st.routeId))
            st.status == RecStatus.FINISHED && Recording.finished.isNotEmpty() && nav.top != Screen.SaveRecording -> nav.push(Screen.SaveRecording)
        }
    }

    /** "Open with Stride" / "Share to Stride" for GPX and TCX files. */
    private fun handleIncoming(intent: Intent?) {
        val i = intent ?: return
        val uri: Uri = when (i.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        } ?: return
        i.action = null // don't import twice on rotation/new intent
        lifecycleScope.launch { importUri(uri) }
    }

    suspend fun importUri(uri: Uri) {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }?.substringBeforeLast('.') ?: "Imported"
        val text = try {
            contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
        } catch (_: Exception) { null }
        if (text == null) { Toasts.show("Couldn't read that file"); return }
        val sport = guessSport(text)
        when (val r = Repo.import(text, name, sport)) {
            is Repo.ImportResult.AsActivity -> { Toasts.show("Imported activity “${r.activity.name}”"); nav.push(Screen.ActivityDetail(r.activity.id)) }
            is Repo.ImportResult.AsRoute -> { Toasts.show("Imported route “${r.route.name}”"); nav.push(Screen.RouteDetail(r.route.id)) }
            is Repo.ImportResult.Failed -> Toasts.show(r.why)
        }
    }

    private fun guessSport(xml: String): Sport {
        val t = Regex("<type>\\s*([^<]+)</type>").find(xml)?.groupValues?.get(1)?.lowercase()
            ?: Regex("Sport=\"([^\"]+)\"").find(xml)?.groupValues?.get(1)?.lowercase() ?: ""
        return when {
            "mountain" in t -> Sport.MOUNTAIN_BIKE
            "cycl" in t || "bik" in t || "ride" in t -> Sport.RIDE
            "hik" in t -> Sport.HIKE
            "walk" in t -> Sport.WALK
            "swim" in t -> Sport.SWIM
            "kayak" in t -> Sport.KAYAK
            "row" in t -> Sport.ROW
            "ski" in t -> Sport.SKI
            else -> Sport.RUN
        }
    }
}

@Composable
private fun App(nav: Nav) {
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { Toasts.queue.collect { snack.showSnackbar(it) } }
    val rec by Recording.state.collectAsState()

    BackHandler(enabled = nav.stack.size > 1 || nav.tab != Tab.HOME) {
        if (!nav.pop()) nav.tab = Tab.HOME
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(targetState = nav.top, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { screen ->
            when (screen) {
                Screen.Tabs -> Tabs(nav, rec.isLive, snack)
                is Screen.Record -> RecordScreen(nav, screen.routeId)
                Screen.SaveRecording -> SaveScreen(nav)
                is Screen.ActivityDetail -> ActivityScreen(nav, screen.id)
                is Screen.EditActivity -> EditActivityScreen(nav, screen.id)
                is Screen.ShareActivity -> ShareScreen(nav, screen.id)
                is Screen.CreateSegment -> CreateSegmentScreen(nav, screen.activityId)
                is Screen.SegmentDetail -> SegmentScreen(nav, screen.id)
                is Screen.RouteDetail -> RouteDetailScreen(nav, screen.id)
                Screen.RouteBuilder -> RouteBuilderScreen(nav)
            }
        }
        if (nav.top != Screen.Tabs) SnackbarHost(snack, Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = 24.dp))
    }
}

@Composable
private fun Tabs(nav: Nav, recordingLive: Boolean, snack: SnackbarHostState) {
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tab.entries.forEach { t ->
                    val isRecord = t == Tab.RECORD
                    NavigationBarItem(
                        selected = nav.tab == t && !isRecord,
                        onClick = { if (isRecord) nav.push(Screen.Record()) else nav.tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.HOME -> Icons.Filled.Home
                                    Tab.ROUTES -> Icons.Filled.Map
                                    Tab.RECORD -> Icons.Filled.FiberManualRecord
                                    Tab.PROGRESS -> Icons.Filled.EmojiEvents
                                    Tab.YOU -> Icons.Filled.Person
                                }, t.label,
                                tint = if (isRecord) MaterialTheme.colorScheme.primary else androidx.compose.material3.LocalContentColor.current,
                                modifier = if (isRecord) Modifier.size(30.dp) else Modifier,
                            )
                        },
                        label = { Text(if (isRecord && recordingLive) "Recording" else t.label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (nav.tab) {
                Tab.HOME, Tab.RECORD -> HomeScreen(nav)
                Tab.ROUTES -> RoutesScreen(nav)
                Tab.PROGRESS -> ProgressScreen(nav)
                Tab.YOU -> YouScreen(nav)
            }
        }
    }
}
