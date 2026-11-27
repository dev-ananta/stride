package com.stride.data

import android.content.Context
import com.stride.domain.Activity
import com.stride.domain.Goal
import com.stride.domain.Gpx
import com.stride.domain.Route
import com.stride.domain.Segment
import com.stride.domain.Settings
import com.stride.domain.Sport
import com.stride.domain.TrackPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * App-wide access to your data. All disk work runs on a background thread, one change at a time,
 * and the UI observes [state].
 */
object Repo {
    lateinit var store: Store
        private set
    private lateinit var lib: Library
    private val lock = Mutex()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(LibState())
    val state: StateFlow<LibState> = _state.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** An unfinished recording found on disk at launch (the app or phone died mid-activity). */
    private val _recovered = MutableStateFlow<Store.InProgress?>(null)
    val recovered: StateFlow<Store.InProgress?> = _recovered.asStateFlow()

    fun init(context: Context) {
        if (::store.isInitialized) return
        store = Store(context.filesDir.resolve("stride"))
        lib = Library(store)
        scope.launch { io { lib.load() } ; _loaded.value = true }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        lock.withLock { block().also { _state.value = lib.state } }
    }

    fun settings(): Settings = _state.value.settings

    fun checkForRecovered(serviceRunning: Boolean) {
        scope.launch {
            val ip = withContext(Dispatchers.IO) { store.loadInProgress() }
            _recovered.value = if (!serviceRunning && ip != null && ip.points.size >= 2) ip else null
            if (!serviceRunning && ip != null && ip.points.size < 2) withContext(Dispatchers.IO) { store.clearInProgress() }
        }
    }

    fun dismissRecovered() { _recovered.value = null }

    suspend fun saveRecovered(): SaveResult? {
        val ip = _recovered.value ?: return null
        val r = addActivity(ip.points, ip.sport, null)
        withContext(Dispatchers.IO) { store.clearInProgress() }
        _recovered.value = null
        return r
    }

    suspend fun discardRecovered() {
        withContext(Dispatchers.IO) { store.clearInProgress() }
        _recovered.value = null
    }

    suspend fun addActivity(points: List<TrackPoint>, sport: Sport, name: String?, notes: String = "", hideEnds: Boolean = true) =
        io { lib.addActivity(points, sport, name, notes, hideEnds) }

    suspend fun updateActivity(a: Activity) = io { lib.updateActivity(a) }
    suspend fun deleteActivity(id: String) = io { lib.deleteActivity(id) }
    suspend fun track(id: String): List<TrackPoint> = withContext(Dispatchers.IO) { store.loadTrack(id) }

    suspend fun addSegment(name: String, sport: Sport, points: List<TrackPoint>): Segment = io { lib.addSegment(name, sport, points) }
    suspend fun renameSegment(id: String, name: String) = io { lib.renameSegment(id, name) }
    suspend fun deleteSegment(id: String) = io { lib.deleteSegment(id) }

    suspend fun addRoute(name: String, sport: Sport, points: List<TrackPoint>): Route = io { lib.addRoute(name, sport, points) }
    suspend fun deleteRoute(id: String) = io { lib.deleteRoute(id) }

    suspend fun addGoal(g: Goal) = io { lib.addGoal(g) }
    suspend fun deleteGoal(id: String) = io { lib.deleteGoal(id) }
    suspend fun saveSettings(s: Settings) = io { lib.saveSettings(s) }

    /**
     * Imports a GPX/TCX file. Files with timestamps become activities (with stats, PRs and segment
     * matches); files without timestamps become routes.
     */
    suspend fun import(xml: String, fallbackName: String, sport: Sport): ImportResult {
        val parsed = withContext(Dispatchers.Default) { Gpx.parse(xml) }
        if (parsed.points.size < 2) return ImportResult.Failed("No track points found in that file.")
        val name = parsed.name ?: fallbackName
        return if (parsed.hasTimes) {
            ImportResult.AsActivity(addActivity(parsed.points.sortedBy { it.time }, sport, name).activity)
        } else {
            ImportResult.AsRoute(addRoute(name, sport, parsed.points))
        }
    }

    sealed interface ImportResult {
        data class AsActivity(val activity: Activity) : ImportResult
        data class AsRoute(val route: Route) : ImportResult
        data class Failed(val why: String) : ImportResult
    }

    /** Called after a backup restore replaced the files. */
    suspend fun reload() = io { lib.load() }
}
