package com.stride.record

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.stride.MainActivity
import com.stride.R
import com.stride.data.Repo
import com.stride.data.Store
import com.stride.domain.Fmt
import com.stride.domain.SafetyText
import com.stride.domain.Sport
import com.stride.domain.TrackPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Shared between the service (writer) and the UI (reader). */
object Recording {
    private val _state = MutableStateFlow(RecState())
    val state: StateFlow<RecState> = _state.asStateFlow()

    /** Points from the last finished recording, waiting for the Save screen. */
    @Volatile var finished: List<TrackPoint> = emptyList()
        internal set

    @Volatile var serviceRunning = false
        internal set

    internal fun publish(s: RecState) { _state.value = s }
    fun clearFinished() { finished = emptyList(); _state.value = RecState() }

    // ---- commands from the UI ----
    fun prepare(ctx: Context, sport: Sport, routeId: String?) = send(ctx, RecordingService.ACTION_PREPARE) {
        putExtra("sport", sport.name); putExtra("route", routeId)
    }
    fun start(ctx: Context) = send(ctx, RecordingService.ACTION_START)
    fun pause(ctx: Context) = send(ctx, RecordingService.ACTION_PAUSE)
    fun resume(ctx: Context) = send(ctx, RecordingService.ACTION_RESUME)
    fun finish(ctx: Context) = send(ctx, RecordingService.ACTION_FINISH)
    fun cancel(ctx: Context) = send(ctx, RecordingService.ACTION_CANCEL)
    fun restore(ctx: Context) = send(ctx, RecordingService.ACTION_RESTORE)
    fun shareLocationNow(ctx: Context) = send(ctx, RecordingService.ACTION_SAFETY_NOW)

    private fun send(ctx: Context, action: String, extras: Intent.() -> Unit = {}) {
        // A foreground location service may only start with location permission; without it
        // Android would kill the app for not calling startForeground in time.
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val i = Intent(ctx, RecordingService::class.java).setAction(action).apply(extras)
        ContextCompat.startForegroundService(ctx, i)
    }
}

/**
 * Foreground service that records GPS with the screen off. Uses the phone's own GPS through
 * LocationManager (no Google Play services needed), appends every point to disk as it arrives,
 * speaks splits, and sends safety check-ins.
 */
class RecordingService : LifecycleService() {
    companion object {
        const val ACTION_PREPARE = "prepare"
        const val ACTION_START = "start"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_FINISH = "finish"
        const val ACTION_CANCEL = "cancel"
        const val ACTION_RESTORE = "restore"
        const val ACTION_SAFETY_NOW = "safety_now"
        const val CHANNEL = "recording"
        private const val NOTIF_ID = 42

        fun createChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while an activity is being recorded"
                setShowBadge(false)
            })
        }
    }

    private lateinit var lm: LocationManager
    private var listening = false
    private var appender: Store.TrackAppender? = null
    private var voice: VoiceCues? = null
    private var wake: PowerManager.WakeLock? = null
    private var safetyJob: Job? = null
    private var tickerJob: Job? = null
    private var lastNotifUpdate = 0L

    private val core = RecorderCore(
        onPoint = { p -> try { appender?.append(p) } catch (_: Exception) { } },
        onSplit = { idx, ms, st ->
            val s = Repo.settings()
            if (s.voiceCues) voice?.split(idx, ms, st.activeMs, st.distanceM, st.sport, s.units)
        },
    )

    // A full object (not a lambda): Android 8–9 still call these older callbacks, and a lambda
    // that doesn't implement them crashes there with AbstractMethodError.
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate() {
        super.onCreate()
        Repo.init(applicationContext)
        lm = getSystemService(LocationManager::class.java)
        Recording.serviceRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!goForeground()) { stopSelf(); return START_NOT_STICKY }
        when (intent?.action) {
            ACTION_PREPARE -> {
                if (core.state.isLive) return START_STICKY // already recording: ignore
                val sport = Sport.entries.firstOrNull { it.name == intent.getStringExtra("sport") } ?: Sport.RUN
                val routeId = intent.getStringExtra("route")
                val route = Repo.state.value.route(routeId)
                core.prepare(sport, routeId, route?.points.orEmpty(), Fmt.splitMeters(Repo.settings().units))
                startLocation()
            }
            ACTION_START -> startRecording()
            ACTION_PAUSE -> core.pause(System.currentTimeMillis())
            ACTION_RESUME -> core.resume(System.currentTimeMillis())
            ACTION_FINISH -> { finishRecording(); return START_NOT_STICKY }
            ACTION_CANCEL -> if (!core.state.isLive) { shutdown(); return START_NOT_STICKY }
            ACTION_SAFETY_NOW -> sendSafetyUpdate(force = true)
            ACTION_RESTORE, null -> {
                // null = the system restarted us after killing the process mid-recording.
                // Wait for settings/routes to load from disk before rebuilding the session.
                lifecycleScope.launch {
                    Repo.loaded.first { it }
                    if (!restoreFromDisk()) shutdown() else publish()
                }
            }
        }
        publish()
        return START_STICKY
    }

    private fun goForeground(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            true
        } catch (_: Exception) { false } // e.g. started from background on Android 12+ without an exemption
    }

    @SuppressLint("MissingPermission") // checked in goForeground()
    private fun startLocation() {
        if (listening) return
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            listening = true
        } catch (_: Exception) { /* no GPS hardware: the Record screen shows "Waiting for GPS" */ }
    }

    private fun stopLocation() {
        if (listening) lm.removeUpdates(listener)
        listening = false
    }

    private fun onLocation(loc: Location) {
        val ele = when {
            Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude() -> loc.mslAltitudeMeters
            loc.hasAltitude() -> loc.altitude
            else -> Double.NaN
        }
        // Prefer the device clock: some GPS chips report times off by whole weeks (GPS week rollover).
        val t = if (kotlin.math.abs(loc.time - System.currentTimeMillis()) < 60_000) loc.time else System.currentTimeMillis()
        core.onFix(TrackPoint(t, loc.latitude, loc.longitude, ele, if (loc.hasAccuracy()) loc.accuracy else 50f))
        publish()
    }

    private fun startRecording() {
        if (core.state.status != RecStatus.READY) return
        val now = System.currentTimeMillis()
        val s = core.state
        appender = Repo.store.beginRecording(s.sport, now, s.routeId)
        core.start(now, Repo.settings().autoPause)
        beginLiveWork()
        val set = Repo.settings()
        if (set.safetyEnabled && set.safetyContacts.isNotEmpty()) {
            val text = SafetyText.started(set.athleteName, s.sport, s.lastLat, s.lastLon, set.safetyIntervalMin)
            noteSafety(Safety.send(this, set.safetyContacts, text))
        }
        if (set.voiceCues) voice?.say("Recording started")
    }

    private fun restoreFromDisk(): Boolean {
        if (core.state.isLive) return true
        val ip = Repo.store.loadInProgress() ?: return false
        val set = Repo.settings()
        val route = Repo.state.value.route(ip.routeId)
        core.restore(ip.sport, ip.startedAt, ip.routeId, route?.points.orEmpty(), ip.points,
            Fmt.splitMeters(set.units), set.autoPause, System.currentTimeMillis())
        appender = Repo.store.continueRecording()
        startLocation()
        beginLiveWork()
        return true
    }

    private fun beginLiveWork() {
        if (Repo.settings().voiceCues && voice == null) voice = VoiceCues(this)
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Stride:recording")
                .apply { setReferenceCounted(false); acquire(12 * 60 * 60 * 1000L) } // hard cap 12 h
        }
        tickerJob?.cancel()
        tickerJob = lifecycleScope.launch {
            while (isActive) { delay(1000); publish() } // keeps the timer/notification ticking without GPS
        }
        startSafetyLoop()
    }

    // ---- safety check-ins ---------------------------------------------------------------------

    private var lastSafetySent = 0L
    private var stillAlerted = false
    private var lowBatteryAlerted = false

    private fun startSafetyLoop() {
        safetyJob?.cancel()
        lastSafetySent = System.currentTimeMillis()
        safetyJob = lifecycleScope.launch {
            while (isActive) {
                delay(30_000)
                val set = Repo.settings()
                if (!set.safetyEnabled || set.safetyContacts.isEmpty()) continue
                val st = core.state
                val now = System.currentTimeMillis()
                val lat = st.lastLat ?: continue
                val lon = st.lastLon ?: continue
                if (set.safetyIntervalMin > 0 && now - lastSafetySent >= set.safetyIntervalMin * 60_000L) sendSafetyUpdate(false)

                // "Hasn't moved" alert (not while you've deliberately paused).
                if (set.stillAlertMin > 0 && st.status != RecStatus.PAUSED) {
                    val stillFor = now - core.lastMovedAt
                    if (!stillAlerted && stillFor >= set.stillAlertMin * 60_000L) {
                        stillAlerted = true
                        noteSafety(Safety.send(this@RecordingService, set.safetyContacts,
                            SafetyText.stoppedMoving(set.athleteName, lat, lon, (stillFor / 60_000).toInt())))
                    } else if (stillFor < 60_000) stillAlerted = false
                }
                val batt = Safety.batteryPercent(this@RecordingService)
                if (!lowBatteryAlerted && batt != null && batt <= 15) {
                    lowBatteryAlerted = true
                    noteSafety(Safety.send(this@RecordingService, set.safetyContacts, SafetyText.lowBattery(set.athleteName, lat, lon, batt)))
                }
            }
        }
    }

    private fun sendSafetyUpdate(force: Boolean) {
        val set = Repo.settings()
        val st = core.state
        val lat = st.lastLat ?: return
        val lon = st.lastLon ?: return
        if (!force && (!set.safetyEnabled || set.safetyContacts.isEmpty())) return
        val text = SafetyText.update(set.athleteName, lat, lon, st.distanceM, st.activeNow(System.currentTimeMillis()), set.units, Safety.batteryPercent(this))
        val n = Safety.send(this, set.safetyContacts, text)
        if (n == 0 && force && set.safetyContacts.isNotEmpty()) {
            // No SMS permission: hand the text to the messaging app instead.
            try { startActivity(Safety.composeIntent(set.safetyContacts, text)) } catch (_: Exception) { }
        }
        lastSafetySent = System.currentTimeMillis()
        noteSafety(n)
    }

    private fun noteSafety(sentTo: Int) {
        val note = if (sentTo > 0) "Location texted to $sentTo contact${if (sentTo == 1) "" else "s"} at " +
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        else if (!Safety.canAutoSend(this)) "Auto texts are off (no SMS permission). Tap “Share location” to send manually."
        else "Couldn't send safety text — check signal"
        core.setSafetyNote(note)
        publish()
    }

    // ---- finish / shutdown ----------------------------------------------------------------------

    private fun finishRecording() {
        val pts = core.finish(System.currentTimeMillis())
        Recording.finished = pts
        val set = Repo.settings()
        if (set.safetyEnabled && set.safetyContacts.isNotEmpty() && pts.size >= 2) {
            val st = core.state
            Safety.send(this, set.safetyContacts, SafetyText.finished(set.athleteName, st.sport, st.distanceM, st.activeMs, set.units))
        }
        publish()
        shutdown()
    }

    private fun shutdown() {
        stopLocation()
        safetyJob?.cancel(); tickerJob?.cancel()
        try { appender?.close() } catch (_: Exception) { }
        appender = null
        voice?.shutdown(); voice = null
        wake?.let { if (it.isHeld) it.release() }; wake = null
        if (core.state.status != RecStatus.FINISHED) { core.reset(); publish() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopLocation()
        try { appender?.close() } catch (_: Exception) { }
        voice?.shutdown()
        wake?.let { if (it.isHeld) it.release() }
        Recording.serviceRunning = false
        super.onDestroy()
    }

    // ---- UI / notification ------------------------------------------------------------------

    private fun publish() {
        val st = core.state
        Recording.publish(st)
        val now = System.currentTimeMillis()
        if (now - lastNotifUpdate > 5000 && st.status != RecStatus.FINISHED) {
            lastNotifUpdate = now
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val st = core.state
        val units = Repo.state.value.settings.units
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = when (st.status) {
            RecStatus.READY, RecStatus.IDLE -> "Getting GPS…"
            RecStatus.RECORDING -> "Recording ${st.sport.label}"
            RecStatus.PAUSED -> "Paused"
            RecStatus.AUTO_PAUSED -> "Auto-paused"
            RecStatus.FINISHED -> "Finished"
        }
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(title)
            .setContentText(if (st.isLive) "${Fmt.duration(st.activeNow(System.currentTimeMillis()))} · ${Fmt.distance(st.distanceM, units)}" else "Stride")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (st.status == RecStatus.RECORDING || st.status == RecStatus.AUTO_PAUSED) {
            b.addAction(0, "Pause", action(ACTION_PAUSE, 1))
        } else if (st.status == RecStatus.PAUSED) {
            b.addAction(0, "Resume", action(ACTION_RESUME, 2))
        }
        return b.build()
    }

    private fun action(a: String, code: Int) = PendingIntent.getService(this, code,
        Intent(this, RecordingService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}
