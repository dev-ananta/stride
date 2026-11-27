package com.stride.data

import com.stride.domain.Json
import com.stride.domain.Sport
import com.stride.domain.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Optional "follow roads/paths" when drawing a route: asks the free, public OSRM servers run by
 * FOSSGIS for OpenStreetMap (the same ones openstreetmap.org's Directions uses). No account or key.
 * Only the tapped waypoints are sent. If it fails (offline, server busy) the app falls back to
 * straight lines, so route drawing always works.
 */
object RouteSnap {
    private fun profile(s: Sport) = when (s) {
        Sport.RIDE, Sport.MOUNTAIN_BIKE, Sport.E_BIKE, Sport.INLINE_SKATE, Sport.WHEELCHAIR -> "routed-bike"
        else -> "routed-foot"
    }

    /** Road-following line through [waypoints] (at least 2), or null if the service couldn't be reached. */
    suspend fun snap(waypoints: List<TrackPoint>, sport: Sport): List<TrackPoint>? = withContext(Dispatchers.IO) {
        if (waypoints.size < 2) return@withContext null
        val coords = waypoints.take(80).joinToString(";") { String.format(Locale.US, "%.6f,%.6f", it.lon, it.lat) }
        val url = URL("https://routing.openstreetmap.de/${profile(sport)}/route/v1/driving/$coords?overview=full&geometries=geojson&steps=false")
        try {
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 12000
            c.setRequestProperty("User-Agent", "Stride/1.0 (Android)")
            if (c.responseCode != 200) return@withContext null
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val root = Json.parse(body) as? Map<*, *> ?: return@withContext null
            if (root["code"] != "Ok") return@withContext null
            val geom = ((root["routes"] as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("geometry") as? Map<*, *>
            val coordsOut = geom?.get("coordinates") as? List<*> ?: return@withContext null
            coordsOut.mapNotNull { pair ->
                val p = pair as? List<*> ?: return@mapNotNull null
                val lon = (p.getOrNull(0) as? Number)?.toDouble() ?: return@mapNotNull null
                val lat = (p.getOrNull(1) as? Number)?.toDouble() ?: return@mapNotNull null
                TrackPoint(0, lat, lon)
            }.takeIf { it.size >= 2 }
        } catch (_: Exception) { null }
    }
}
