package com.stride.data

import com.stride.domain.Activity
import com.stride.domain.BestEffortRecord
import com.stride.domain.Goal
import com.stride.domain.Json
import com.stride.domain.Mappers
import com.stride.domain.Route
import com.stride.domain.Segment
import com.stride.domain.SegmentEffort
import com.stride.domain.Settings
import com.stride.domain.Sport
import com.stride.domain.TrackCodec
import com.stride.domain.TrackPoint
import java.io.File
import java.io.FileOutputStream

/**
 * Everything lives in plain files in the app's private storage — no accounts, no cloud, no database.
 *
 *   activities.json        index of activities (summaries only, loads instantly)
 *   tracks/<id>.pbt        full GPS track per activity (binary, see TrackCodec)
 *   routes.json, segments.json, efforts.json, goals.json, records.json, settings.json
 *   current.pbt/.json      the recording in progress (appended live; survives crashes/reboots)
 *
 * Writes go to a temp file then rename, so a crash mid-save never leaves a half-written file.
 * Pure java.io so it is unit-tested on the JVM.
 */
class Store(val root: File) {
    private val tracks = File(root, "tracks")

    init { tracks.mkdirs() }

    // ---- generic helpers -------------------------------------------------------------------

    private fun readList(name: String): List<Map<*, *>> {
        val f = File(root, name)
        if (!f.exists()) return emptyList()
        return try {
            (Json.parse(f.readText()) as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
        } catch (_: Exception) {
            // Corrupt file: keep a copy for manual recovery rather than silently overwriting it later.
            f.copyTo(File(root, "$name.corrupt-${System.currentTimeMillis()}"), overwrite = true)
            emptyList()
        }
    }

    private fun writeAtomic(name: String, text: String) = writeAtomic(File(root, name), text.toByteArray())

    private fun writeAtomic(f: File, bytes: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        if (!tmp.renameTo(f)) { f.delete(); check(tmp.renameTo(f)) { "Could not save ${f.name}" } }
    }

    // ---- activities ------------------------------------------------------------------------

    fun loadActivities(): List<Activity> = readList("activities.json").map(Mappers::activity).sortedByDescending { it.startTime }
    fun saveActivities(list: List<Activity>) = writeAtomic("activities.json", Json.write(list.map(Mappers::activity)))

    fun trackFile(id: String) = File(tracks, "$id.pbt")
    fun saveTrack(id: String, points: List<TrackPoint>) = writeAtomic(trackFile(id), TrackCodec.encode(points))
    fun loadTrack(id: String): List<TrackPoint> = trackFile(id).takeIf { it.exists() }?.inputStream()?.use { TrackCodec.read(it) } ?: emptyList()
    fun deleteTrack(id: String) { trackFile(id).delete() }

    // ---- routes / segments / efforts / goals / records / settings ---------------------------

    fun loadRoutes() = readList("routes.json").map(Mappers::route).sortedByDescending { it.createdAt }
    fun saveRoutes(l: List<Route>) = writeAtomic("routes.json", Json.write(l.map(Mappers::route)))

    fun loadSegments() = readList("segments.json").map(Mappers::segment).sortedByDescending { it.createdAt }
    fun saveSegments(l: List<Segment>) = writeAtomic("segments.json", Json.write(l.map(Mappers::segment)))

    fun loadEfforts() = readList("efforts.json").map(Mappers::effort)
    fun saveEfforts(l: List<SegmentEffort>) = writeAtomic("efforts.json", Json.write(l.map(Mappers::effort)))

    fun loadGoals() = readList("goals.json").map(Mappers::goal)
    fun saveGoals(l: List<Goal>) = writeAtomic("goals.json", Json.write(l.map(Mappers::goal)))

    fun loadRecords(): Map<String, BestEffortRecord> {
        val f = File(root, "records.json")
        if (!f.exists()) return emptyMap()
        return try {
            (Json.parse(f.readText()) as? Map<*, *>).orEmpty().entries
                .mapNotNull { (k, v) -> (v as? Map<*, *>)?.let { k.toString() to Mappers.record(it) } }.toMap()
        } catch (_: Exception) { emptyMap() }
    }
    fun saveRecords(m: Map<String, BestEffortRecord>) = writeAtomic("records.json", Json.write(m.mapValues { Mappers.record(it.value) }))

    fun loadSettings(): Settings {
        val f = File(root, "settings.json")
        if (!f.exists()) return Settings()
        return try { Mappers.settings(Json.parse(f.readText()) as Map<*, *>) } catch (_: Exception) { Settings() }
    }
    fun saveSettings(s: Settings) = writeAtomic("settings.json", Json.write(Mappers.settings(s)))

    // ---- recording in progress --------------------------------------------------------------

    data class InProgress(val sport: Sport, val startedAt: Long, val routeId: String?, val points: List<TrackPoint>)

    private val currentTrack get() = File(root, "current.pbt")
    private val currentMeta get() = File(root, "current.json")

    /** Opens a fresh in-progress file. Returns an appender the recorder writes every fix to. */
    fun beginRecording(sport: Sport, startedAt: Long, routeId: String?): TrackAppender {
        writeAtomic("current.json", Json.write(mapOf("sport" to sport.name, "start" to startedAt, "route" to routeId)))
        val out = FileOutputStream(currentTrack, false)
        TrackCodec.writeHeader(out)
        return TrackAppender(out)
    }

    /** Re-opens the in-progress file for appending (after the service was restarted). */
    fun continueRecording(): TrackAppender = TrackAppender(FileOutputStream(currentTrack, true))

    fun loadInProgress(): InProgress? {
        if (!currentMeta.exists() || !currentTrack.exists()) return null
        return try {
            val m = Json.parse(currentMeta.readText()) as Map<*, *>
            val sport = Sport.entries.firstOrNull { it.name == m["sport"] } ?: Sport.RUN
            InProgress(sport, (m["start"] as Number).toLong(), m["route"] as? String, currentTrack.inputStream().use { TrackCodec.read(it) })
        } catch (_: Exception) { null }
    }

    fun clearInProgress() { currentTrack.delete(); currentMeta.delete() }

    class TrackAppender(private val out: FileOutputStream) : AutoCloseable {
        fun append(p: TrackPoint) { TrackCodec.writePoint(out, p) }
        override fun close() { try { out.fd.sync() } catch (_: Exception) {}; out.close() }
    }
}
