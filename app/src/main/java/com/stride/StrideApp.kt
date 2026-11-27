package com.stride

import android.app.Application
import com.stride.data.Repo
import com.stride.record.RecordingService
import org.osmdroid.config.Configuration
import java.io.File

class StrideApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        RecordingService.createChannel(this)
        // OpenStreetMap's tile policy requires an identifying User-Agent; tiles are cached on the phone.
        Configuration.getInstance().apply {
            userAgentValue = "Stride/1.0 (Android; $packageName)"
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
            tileFileSystemCacheMaxBytes = 300L * 1024 * 1024
            tileFileSystemCacheTrimBytes = 250L * 1024 * 1024
        }
    }
}
